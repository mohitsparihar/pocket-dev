import * as path from 'node:path';
import * as fs from 'node:fs';
import { execFileSync } from 'node:child_process';
import { register, send } from '../router.js';
import { verifyToken } from '../auth.js';
import { getDb, newId, now } from '../db.js';
import { readBody, parseBody, checkIdempotency, storeIdempotency, bodyHash } from '../util/idempotency.js';
import { throwHttp } from '../util/errors.js';
import { getCapabilities } from '../providers/detect.js';
import {
  startSessionProcess,
  sendPrompt,
  interruptSession,
  closeSession,
  isSessionRunning,
} from '../providers/manager.js';
import { emitEvent } from '../ws/broadcast.js';
import { expandCommand, listCommands } from '../providers/commands.js';

interface SessionRow {
  id: string;
  project_id: string;
  provider: string;
  title: string | null;
  state: string;
  connectivity: string;
  workspace: string;
  workspace_path: string | null;
  provider_session_id: string | null;
  model: string | null;
  effort: string | null;
  mode: string | null;
  process_pid: number | null;
  slash_commands: string | null;
  provider_info: string | null;
  usage: string | null;
  archived: number;
  created_at: number;
  updated_at: number;
}

interface ProjectRow {
  id: string;
  root_path: string;
}

interface ApprovalRow {
  id: string;
  session_id: string;
  description: string | null;
  action: string | null;
  allow: number | null;
  created_at: number;
  resolved_at: number | null;
}

interface EventRow {
  id: string;
  session_id: string;
  seq: number;
  type: string;
  payload: string;
  at: number;
}

function latestLine(sessionId: string): string {
  const row = getDb()
    .prepare(
      `SELECT type, payload FROM session_events
       WHERE session_id = ? AND type IN ('assistant', 'prompt', 'error')
       ORDER BY seq DESC LIMIT 1`
    )
    .get(sessionId) as { type: string; payload: string } | undefined;
  if (!row) return '';
  let text = '';
  try {
    const payload = JSON.parse(row.payload) as { text?: unknown };
    if (typeof payload.text === 'string') text = payload.text;
  } catch { /* ignore */ }
  text = promptBody(text).replace(/\s+/g, ' ').trim();
  if (text.length > 140) text = text.slice(0, 139) + '…';
  if (!text) return '';
  return row.type === 'prompt' ? `You: ${text}` : text;
}

// The phone prefixes prompts with a list of context files; titles and
// previews should show what the user actually asked.
function promptBody(text: string): string {
  if (!text.startsWith('Use these files as context:')) return text;
  const split = text.indexOf('\n\n');
  return split >= 0 ? text.slice(split + 2) : text;
}

export function titleFromPrompt(text: string): string {
  const line = promptBody(text).trim().split('\n')[0].trim();
  if (line.length <= 48) return line;
  const cut = line.slice(0, 48);
  const space = cut.lastIndexOf(' ');
  return (space > 24 ? cut.slice(0, space) : cut) + '…';
}

function sessionRowToJson(r: SessionRow, events?: EventRow[], approvals?: ApprovalRow[]) {
  const base = {
    id: r.id,
    projectId: r.project_id,
    provider: r.provider,
    title: r.title ?? '',
    model: r.model ?? '',
    effort: r.effort ?? '',
    mode: r.mode || 'agent',
    state: r.state,
    connectivity: r.connectivity,
    workspace: r.workspace,
    workspacePath: r.workspace_path ?? '',
    providerSessionId: r.provider_session_id,
    processPid: r.process_pid,
    archived: r.archived === 1,
    latestLine: latestLine(r.id),
    createdAt: r.created_at,
    updatedAt: r.updated_at,
  };

  if (!events && !approvals) return base;

  return {
    ...base,
    events: (events ?? []).map((e) => ({
      id: e.id,
      sessionId: e.session_id,
      seq: e.seq,
      type: e.type,
      payload: JSON.parse(e.payload) as Record<string, unknown>,
      at: e.at,
    })),
    approvals: (approvals ?? []).map((a) => ({
      id: a.id,
      sessionId: a.session_id,
      description: a.description,
      action: a.action ? (JSON.parse(a.action) as unknown) : null,
      allow: a.allow,
      createdAt: a.created_at,
      resolvedAt: a.resolved_at,
    })),
  };
}

function cleanSetting(value: string | undefined): string {
  const text = (value ?? '').trim();
  if (!text) return '';
  if (!/^[A-Za-z0-9._:-]+$/.test(text)) return '';
  return text;
}

function cleanEffort(value: string | undefined): string {
  const text = (value ?? '').trim().toLowerCase();
  if (text === 'extra' || text === 'extrahigh' || text === 'extra-high') return 'xhigh';
  return ['low', 'medium', 'high', 'xhigh', 'max'].includes(text) ? text : '';
}

function cleanMode(value: string | undefined): string {
  const text = (value ?? '').trim().toLowerCase();
  return ['agent', 'auto', 'plan', 'ask'].includes(text) ? text : 'agent';
}

function modeLabel(mode: string): string {
  if (mode === 'auto') return 'Auto';
  if (mode === 'plan') return 'Plan';
  if (mode === 'ask') return 'Ask';
  return 'Agent';
}

function git(cwd: string, args: string[]): string {
  return execFileSync('git', ['-C', cwd, ...args], { stdio: ['ignore', 'pipe', 'pipe'], timeout: 30_000 })
    .toString('utf8')
    .trim();
}

// A worktree chat gets its own branch checked out under .worktrees/. Folders
// that are not a git repository with at least one commit fall back to shared.
function resolveWorkspace(
  projectRoot: string,
  workspace: string,
  sessionId: string
): { workspace: string; path: string; note?: string } {
  if (workspace === 'shared') return { workspace, path: projectRoot };
  try {
    git(projectRoot, ['rev-parse', '--verify', 'HEAD']);
  } catch {
    return {
      workspace: 'shared',
      path: projectRoot,
      note: 'This folder has no git commits yet, so the chat works in the project folder directly',
    };
  }
  const wtPath = path.join(projectRoot, '.worktrees', sessionId);
  const branch = `pocket/${sessionId.slice(0, 8)}`;
  try {
    git(projectRoot, ['worktree', 'add', '-b', branch, wtPath]);
    const exclude = path.resolve(projectRoot, git(projectRoot, ['rev-parse', '--git-path', 'info/exclude']));
    const current = fs.existsSync(exclude) ? fs.readFileSync(exclude, 'utf8') : '';
    if (!current.split('\n').includes('.worktrees/')) {
      fs.mkdirSync(path.dirname(exclude), { recursive: true });
      fs.appendFileSync(exclude, `${current && !current.endsWith('\n') ? '\n' : ''}.worktrees/\n`);
    }
    return { workspace, path: wtPath, note: `Working on branch ${branch}` };
  } catch (err) {
    const msg = err instanceof Error ? err.message.split('\n')[0] : String(err);
    return { workspace: 'shared', path: projectRoot, note: `Could not create a worktree (${msg}), so the chat works in the project folder` };
  }
}

function commandRoots(session: SessionRow): string[] {
  const project = getDb().prepare('SELECT root_path FROM projects WHERE id = ?').get(session.project_id) as
    | { root_path: string }
    | undefined;
  return [session.workspace_path ?? '', project?.root_path ?? ''];
}

/** Claude's own list from this chat, or from the newest Claude chat in the project. */
function reportedCommands(session: SessionRow): string[] | null {
  const raw =
    session.slash_commands ??
    (getDb()
      .prepare(
        `SELECT slash_commands FROM sessions
         WHERE provider = 'claude' AND project_id = ? AND slash_commands IS NOT NULL
         ORDER BY updated_at DESC LIMIT 1`
      )
      .get(session.project_id) as { slash_commands: string } | undefined)?.slash_commands;
  if (!raw) return null;
  try {
    const parsed = JSON.parse(raw) as unknown;
    return Array.isArray(parsed) ? parsed.filter((c): c is string => typeof c === 'string') : null;
  } catch {
    return null;
  }
}

function parseJson(raw: string | null): unknown {
  if (!raw) return null;
  try {
    return JSON.parse(raw) as unknown;
  } catch {
    return null;
  }
}

export function registerSessionRoutes(): void {
  // What /status shows in the agents' own terminals, for the phone.
  register('GET', '/v1/sessions/:id/status', (req, res, params) => {
    verifyToken(req);
    const session = getDb().prepare('SELECT * FROM sessions WHERE id = ?').get(params.id) as unknown as SessionRow | undefined;
    if (!session) throwHttp(404, 'NOT_FOUND', 'Session not found');
    let branch = '';
    try {
      if (session.workspace_path) branch = git(session.workspace_path, ['rev-parse', '--abbrev-ref', 'HEAD']);
    } catch { /* not a git repository */ }
    send(res, 200, {
      provider: session.provider,
      model: session.model ?? '',
      effort: session.effort ?? '',
      mode: session.mode || 'agent',
      state: session.state,
      workspace: session.workspace,
      workspacePath: session.workspace_path ?? '',
      branch,
      providerSessionId: session.provider_session_id ?? '',
      running: isSessionRunning(session.id),
      createdAt: session.created_at,
      info: parseJson(session.provider_info),
      usage: parseJson(session.usage),
    });
  });

  register('GET', '/v1/sessions/:id/commands', (req, res, params) => {
    verifyToken(req);
    const session = getDb().prepare('SELECT * FROM sessions WHERE id = ?').get(params.id) as unknown as SessionRow | undefined;
    if (!session) throwHttp(404, 'NOT_FOUND', 'Session not found');
    const reported = session.provider === 'claude' ? reportedCommands(session) : null;
    send(res, 200, {
      provider: session.provider,
      // false until Claude has started once in this project; the list is then a best guess.
      reported: reported !== null,
      commands: listCommands(session.provider, commandRoots(session), reported),
    });
  });

  register('GET', '/v1/sessions', (req, res, _p, query) => {
    verifyToken(req);
    const db = getDb();
    const projectId = query.projectId;
    let rows: SessionRow[];
    if (projectId) {
      rows = db
        .prepare('SELECT * FROM sessions WHERE project_id = ? ORDER BY created_at DESC')
        .all(projectId) as unknown as SessionRow[];
    } else {
      rows = db.prepare('SELECT * FROM sessions ORDER BY created_at DESC').all() as unknown as SessionRow[];
    }
    send(res, 200, rows.map((r) => sessionRowToJson(r)));
  });

  register('POST', '/v1/sessions', async (req, res) => {
    verifyToken(req);
    const buf = await readBody(req);
    const ikey = req.headers['idempotency-key'] as string | undefined;
    const endpoint = 'POST /v1/sessions';

    if (ikey) {
      const hash = bodyHash(buf);
      const cached = checkIdempotency(ikey, endpoint, hash);
      if (cached) { send(res, cached.statusCode, cached.body); return; }
    }

    const body = parseBody<{
      projectId: string;
      provider: string;
      title?: string;
      workspace?: string;
      model?: string;
      effort?: string;
      mode?: string;
    }>(buf);

    if (!body.projectId || !body.provider) {
      throwHttp(400, 'VALIDATION', 'projectId and provider are required');
    }

    const provider = body.provider === 'cursor' ? 'agent' : body.provider;
    const VALID_PROVIDERS = ['claude', 'agent', 'codex', 'terminal'];
    if (!VALID_PROVIDERS.includes(provider)) {
      throwHttp(400, 'VALIDATION', `provider must be one of: ${VALID_PROVIDERS.join(', ')}`);
    }

    const db = getDb();
    const project = db.prepare('SELECT * FROM projects WHERE id = ?').get(body.projectId) as unknown as ProjectRow | undefined;
    if (!project) throwHttp(404, 'NOT_FOUND', 'Project not found');

    // Check if provider is installed
    const caps = getCapabilities();
    const providerKey = provider as keyof typeof caps.providers;
    if (providerKey !== 'terminal' && !caps.providers[providerKey]?.installed) {
      throwHttp(400, 'UNSUPPORTED', `${provider} is not installed`);
    }

    const id = newId();
    const ts = now();
    const resolved = resolveWorkspace(project.root_path, body.workspace === 'shared' ? 'shared' : 'worktree', id);
    const workspace = resolved.workspace;
    const workspacePath = resolved.path;
    const model = cleanSetting(body.model);
    const effort = cleanEffort(body.effort);
    const mode = cleanMode(body.mode);

    db.prepare(
      `INSERT INTO sessions (id, project_id, provider, title, model, effort, mode, state, connectivity, workspace, workspace_path, created_at, updated_at)
       VALUES (?, ?, ?, ?, ?, ?, ?, 'queued', 'offline', ?, ?, ?, ?)`
    ).run(id, body.projectId, provider, body.title?.trim() || null, model, effort, mode, workspace, workspacePath, ts, ts);
    if (resolved.note) emitEvent(id, 'status', { text: resolved.note });

    // Start the process asynchronously
    setImmediate(() => {
      try {
        startSessionProcess(id, provider, workspacePath, { model, effort, mode });
      } catch (err) {
        const msg = err instanceof Error ? err.message : String(err);
        db.prepare('UPDATE sessions SET state = ?, updated_at = ? WHERE id = ?').run('failed', now(), id);
        emitEvent(id, 'state_change', { state: 'failed', error: msg });
      }
    });

    const row = db.prepare('SELECT * FROM sessions WHERE id = ?').get(id) as unknown as SessionRow;
    const result = sessionRowToJson(row);

    if (ikey) storeIdempotency(ikey, endpoint, bodyHash(buf), 201, result);
    send(res, 201, result);
  });

  register('GET', '/v1/sessions/:id', (req, res, params, query) => {
    verifyToken(req);
    const db = getDb();
    const row = db.prepare('SELECT * FROM sessions WHERE id = ?').get(params.id) as unknown as SessionRow | undefined;
    if (!row) throwHttp(404, 'NOT_FOUND', 'Session not found');

    // ?limit=N returns the latest N events so long chats open quickly.
    const limit = parseInt(query.limit ?? '', 10);
    const events = (Number.isFinite(limit) && limit > 0
      ? db
          .prepare(
            `SELECT * FROM (SELECT * FROM session_events WHERE session_id = ? ORDER BY seq DESC LIMIT ?)
             ORDER BY seq ASC`
          )
          .all(params.id, limit)
      : db
          .prepare('SELECT * FROM session_events WHERE session_id = ? ORDER BY seq ASC')
          .all(params.id)) as unknown as EventRow[];
    const approvals = db
      .prepare('SELECT * FROM approvals WHERE session_id = ? ORDER BY created_at ASC')
      .all(params.id) as unknown as ApprovalRow[];

    send(res, 200, sessionRowToJson(row, events, approvals));
  });

  register('POST', '/v1/sessions/:id/prompt', async (req, res, params) => {
    verifyToken(req);
    const buf = await readBody(req);
    const ikey = req.headers['idempotency-key'] as string | undefined;
    const endpoint = `POST /v1/sessions/${params.id}/prompt`;

    if (ikey) {
      const hash = bodyHash(buf);
      const cached = checkIdempotency(ikey, endpoint, hash);
      if (cached) { send(res, cached.statusCode, cached.body); return; }
    }

    const body = parseBody<{ text: string }>(buf);
    if (!body.text) throwHttp(400, 'VALIDATION', 'text is required');

    const db = getDb();
    const session = db.prepare('SELECT * FROM sessions WHERE id = ?').get(params.id) as unknown as SessionRow | undefined;
    if (!session) throwHttp(404, 'NOT_FOUND', 'Session not found');

    // Cursor and Codex ignore slash commands headless; expand custom command
    // files here. The chat still shows what the user typed.
    const expansion = expandCommand(session.provider, body.text, commandRoots(session));
    const launch = {
      model: session.model ?? '',
      effort: session.effort ?? '',
      mode: session.mode || 'agent',
      resumeId: session.provider_session_id ?? '',
      prompt: expansion?.prompt ?? body.text,
      review: expansion?.review,
    };
    const workspacePath = session.workspace_path ?? '';
    if (session.provider === 'agent' || session.provider === 'codex') {
      if (isSessionRunning(params.id)) throwHttp(409, 'BUSY', 'The agent is still working');
      startSessionProcess(params.id, session.provider, workspacePath, launch);
    } else {
      if (!isSessionRunning(params.id)) {
        startSessionProcess(params.id, session.provider, workspacePath, launch);
      }
      if (!isSessionRunning(params.id)) throwHttp(409, 'BUSY', 'Session process is not running');
      sendPrompt(params.id, launch.prompt);
    }
    emitEvent(params.id, 'prompt', { text: body.text });
    if (!session.title) {
      db.prepare('UPDATE sessions SET title = ?, updated_at = ? WHERE id = ?').run(titleFromPrompt(body.text), now(), params.id);
    }

    const result = { ok: true };
    if (ikey) storeIdempotency(ikey, endpoint, bodyHash(buf), 200, result);
    send(res, 200, result);
  });

  register('POST', '/v1/sessions/:id/interrupt', async (req, res, params) => {
    verifyToken(req);
    const db = getDb();
    const session = db.prepare('SELECT * FROM sessions WHERE id = ?').get(params.id) as unknown as SessionRow | undefined;
    if (!session) throwHttp(404, 'NOT_FOUND', 'Session not found');

    interruptSession(params.id);
    send(res, 200, { ok: true });
  });

  register('POST', '/v1/sessions/:id/permission', async (req, res, params) => {
    verifyToken(req);
    const buf = await readBody(req);
    const body = parseBody<{ approvalId: string; allow: boolean }>(buf);
    if (!body.approvalId) throwHttp(400, 'VALIDATION', 'approvalId is required');

    const db = getDb();
    const approval = db
      .prepare('SELECT * FROM approvals WHERE id = ? AND session_id = ?')
      .get(body.approvalId, params.id) as unknown as ApprovalRow | undefined;
    if (!approval) throwHttp(404, 'NOT_FOUND', 'Approval not found');
    if (approval.allow !== null) throwHttp(409, 'CONFLICT', 'Approval already resolved');

    db.prepare('UPDATE approvals SET allow = ?, resolved_at = ? WHERE id = ?').run(
      body.allow ? 1 : 0,
      now(),
      body.approvalId
    );

    emitEvent(params.id, 'permission_response', { approvalId: body.approvalId, allow: body.allow });
    send(res, 200, { ok: true });
  });

  register('POST', '/v1/sessions/:id/resume', async (req, res, params) => {
    verifyToken(req);
    const db = getDb();
    const session = db.prepare('SELECT * FROM sessions WHERE id = ?').get(params.id) as unknown as SessionRow | undefined;
    if (!session) throwHttp(404, 'NOT_FOUND', 'Session not found');

    // Provider-native resume depends on the provider's capabilities.
    // For now, if the process isn't running, try restarting it.
    if (!isSessionRunning(params.id)) {
      const workspacePath = session.workspace_path ?? session.project_id;
      setImmediate(() => {
        try {
          startSessionProcess(params.id, session.provider, workspacePath, {
            model: session.model ?? '',
            effort: session.effort ?? '',
            mode: session.mode || 'agent',
            resumeId: session.provider_session_id ?? '',
          });
        } catch (err) {
          const msg = err instanceof Error ? err.message : String(err);
          emitEvent(params.id, 'state_change', { state: 'failed', error: msg });
        }
      });
    }

    send(res, 200, { ok: true });
  });

  register('POST', '/v1/sessions/:id/close', async (req, res, params) => {
    verifyToken(req);
    const db = getDb();
    const session = db.prepare('SELECT * FROM sessions WHERE id = ?').get(params.id) as unknown as SessionRow | undefined;
    if (!session) throwHttp(404, 'NOT_FOUND', 'Session not found');

    closeSession(params.id);
    send(res, 200, { ok: true });
  });

  register('POST', '/v1/sessions/:id/settings', async (req, res, params) => {
    verifyToken(req);
    const db = getDb();
    const session = db.prepare('SELECT * FROM sessions WHERE id = ?').get(params.id) as unknown as SessionRow | undefined;
    if (!session) throwHttp(404, 'NOT_FOUND', 'Session not found');

    const buf = await readBody(req);
    const body = parseBody<{ model?: string; effort?: string; mode?: string }>(buf);
    const model = body.model !== undefined ? cleanSetting(body.model) : (session.model ?? '');
    const effort = body.effort !== undefined ? cleanEffort(body.effort) : (session.effort ?? '');
    const mode = body.mode !== undefined ? cleanMode(body.mode) : (session.mode || 'agent');
    if (body.model && !model) throwHttp(400, 'VALIDATION', 'Unrecognized model');
    if (body.effort && !effort) throwHttp(400, 'VALIDATION', 'Unrecognized effort');

    db.prepare('UPDATE sessions SET model = ?, effort = ?, mode = ?, updated_at = ? WHERE id = ?').run(
      model, effort, mode, now(), params.id
    );

    if (session.provider === 'claude') {
      if (isSessionRunning(params.id)) closeSession(params.id);
      startSessionProcess(params.id, 'claude', session.workspace_path ?? '', {
        model,
        effort,
        mode,
        resumeId: session.provider_session_id ?? '',
      });
    }

    const summary = [modeLabel(mode), model, effort].filter(Boolean).join(' · ');
    emitEvent(params.id, 'status', { text: summary ? `Using ${summary}` : 'Using the default settings' });
    send(res, 200, { ok: true, model, effort, mode });
  });

  register('PATCH', '/v1/sessions/:id', async (req, res, params) => {
    verifyToken(req);
    const db = getDb();
    const session = db.prepare('SELECT * FROM sessions WHERE id = ?').get(params.id) as unknown as SessionRow | undefined;
    if (!session) throwHttp(404, 'NOT_FOUND', 'Session not found');

    const buf = await readBody(req);
    const body = parseBody<{ title?: string; archived?: boolean }>(buf);

    const title = 'title' in body ? (body.title ?? null) : session.title;
    const archived = 'archived' in body ? (body.archived ? 1 : 0) : session.archived;

    db.prepare('UPDATE sessions SET title = ?, archived = ?, updated_at = ? WHERE id = ?').run(
      title, archived, now(), params.id
    );

    const updated = db.prepare('SELECT * FROM sessions WHERE id = ?').get(params.id) as unknown as SessionRow;
    send(res, 200, sessionRowToJson(updated));
  });
}
