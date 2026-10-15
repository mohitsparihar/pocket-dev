import { spawn, ChildProcess } from 'node:child_process';
import { getDb, now } from '../db.js';
import { getCapabilities } from './detect.js';
import { cursorModelId, eventsFromProviderLine, TurnUsage } from './streamEvents.js';
import { emitEvent, emitDelta, broadcastPtyOutput } from '../ws/broadcast.js';
import { throwHttp } from '../util/errors.js';

export interface SessionLaunch {
  model?: string;
  effort?: string;
  mode?: string;
  resumeId?: string;
  prompt?: string;
  /** Codex only: run `codex exec review` with these arguments instead of a prompt. */
  review?: string[];
}

interface SessionProcess {
  sessionId: string;
  process: ChildProcess;
  pty?: unknown; // node-pty IPty
  usePty: boolean;
  jsonStdin?: boolean;
  interrupted?: boolean;
  /** A Codex review run; its thread must not replace the chat's own. */
  review?: boolean;
  /** Claude's running cost total for this process, to turn it into per-turn amounts. */
  reportedCost?: number;
}

interface SessionUsage {
  costUsd: number;
  inputTokens: number;
  outputTokens: number;
  turns: number;
  contextTokens?: number;
  contextWindow?: number;
}

function recordUsage(sessionId: string, usage: TurnUsage): void {
  const db = getDb();
  const row = db.prepare('SELECT usage FROM sessions WHERE id = ?').get(sessionId) as { usage: string | null } | undefined;
  let total: SessionUsage = { costUsd: 0, inputTokens: 0, outputTokens: 0, turns: 0 };
  try {
    if (row?.usage) total = { ...total, ...(JSON.parse(row.usage) as SessionUsage) };
  } catch { /* start over */ }
  const entry = sessionProcesses.get(sessionId);
  if (usage.processCostUsd !== undefined) {
    const before = entry?.reportedCost ?? 0;
    // A lower total means the provider process restarted.
    total.costUsd += usage.processCostUsd >= before ? usage.processCostUsd - before : usage.processCostUsd;
    if (entry) entry.reportedCost = usage.processCostUsd;
  }
  total.inputTokens += usage.inputTokens;
  total.outputTokens += usage.outputTokens;
  total.turns += 1;
  if (usage.contextTokens !== undefined) total.contextTokens = usage.contextTokens;
  if (usage.contextWindow !== undefined) total.contextWindow = usage.contextWindow;
  db.prepare('UPDATE sessions SET usage = ? WHERE id = ?').run(JSON.stringify(total), sessionId);
}

const sessionProcesses = new Map<string, SessionProcess>();

interface ManagedProcessHandle {
  processId: string;
  process: ChildProcess;
}

const managedProcesses = new Map<string, ManagedProcessHandle>();

export function isSessionRunning(sessionId: string): boolean {
  return sessionProcesses.has(sessionId);
}

export function getSessionProcess(sessionId: string): SessionProcess | undefined {
  return sessionProcesses.get(sessionId);
}

function updateSessionState(sessionId: string, state: string): void {
  const db = getDb();
  const row = db.prepare('SELECT state FROM sessions WHERE id = ?').get(sessionId) as { state: string } | undefined;
  if (row?.state === state) return;
  db.prepare('UPDATE sessions SET state = ?, updated_at = ? WHERE id = ?').run(state, now(), sessionId);
  emitEvent(sessionId, 'state_change', { state });
}

function updateSessionPid(sessionId: string, pid: number | undefined): void {
  const db = getDb();
  db.prepare('UPDATE sessions SET process_pid = ?, updated_at = ? WHERE id = ?').run(pid ?? null, now(), sessionId);
}

function claudeModeArgs(mode: string): string[] {
  if (mode === 'auto') return ['--permission-mode', 'auto'];
  if (mode === 'plan') return ['--permission-mode', 'plan'];
  if (mode === 'ask') return ['--disallowed-tools', 'Bash,Edit,Write,NotebookEdit'];
  return ['--permission-mode', 'acceptEdits'];
}

function token(value: string | undefined): string {
  const text = (value ?? '').trim();
  if (!text || !/^[A-Za-z0-9._:-]+$/.test(text)) return '';
  return text;
}

function providerCommand(
  provider: string,
  workspacePath: string,
  launch: SessionLaunch
): { cmd: string; args: string[]; cwd: string; json: boolean } {
  const caps = getCapabilities();
  const model = token(launch.model);
  const effort = token(launch.effort);
  const mode = token(launch.mode) || 'agent';
  const resume = token(launch.resumeId);

  switch (provider) {
    case 'claude': {
      if (!caps.providers.claude.installed) {
        throwHttp(400, 'UNSUPPORTED', 'claude CLI is not installed');
      }
      const args = [
        '-p',
        '--input-format', 'stream-json',
        '--output-format', 'stream-json',
        '--verbose',
        '--include-partial-messages',
        ...claudeModeArgs(mode),
      ];
      if (model) args.push('--model', model);
      if (effort) args.push('--effort', effort);
      if (resume) args.push('--resume', resume);
      return { cmd: 'claude', args, cwd: workspacePath, json: true };
    }
    case 'agent': {
      if (!caps.providers.agent.installed) {
        throwHttp(400, 'UNSUPPORTED', 'cursor CLI is not installed');
      }
      const args = ['-p', '--output-format', 'stream-json', '--trust', '--workspace', workspacePath];
      if (mode === 'plan') args.push('--mode', 'plan');
      else if (mode === 'ask') args.push('--mode', 'ask');
      else if (mode === 'auto') args.push('--auto-review');
      else args.push('--force');
      const cursorModel = cursorModelId(model, effort);
      if (cursorModel) args.push('--model', cursorModel);
      if (resume) args.push('--resume', resume);
      if (launch.prompt) args.push(launch.prompt);
      return { cmd: caps.providers.agent.binary || 'agent', args, cwd: workspacePath, json: true };
    }
    case 'codex': {
      if (!caps.providers.codex.installed) {
        throwHttp(400, 'UNSUPPORTED', 'codex CLI is not installed');
      }
      if (launch.review) {
        const args = ['exec', 'review', '--json', '--skip-git-repo-check'];
        if (model) args.push('-m', model);
        args.push(...launch.review);
        return { cmd: 'codex', args, cwd: workspacePath, json: true };
      }
      const sandbox = mode === 'plan' || mode === 'ask' ? 'read-only' : 'workspace-write';
      const args = ['exec'];
      if (resume) args.push('resume', resume);
      else args.push('--skip-git-repo-check', '--sandbox', sandbox, '-C', workspacePath);
      args.push('--json', '--color', 'never');
      if (mode === 'auto') args.push('--approve-for-me');
      if (model) args.push('-m', model);
      if (effort) args.push('-c', `model_reasoning_effort="${effort}"`);
      if (launch.prompt) args.push(launch.prompt);
      return { cmd: 'codex', args, cwd: workspacePath, json: true };
    }
    case 'terminal': {
      return { cmd: 'bash', args: ['--login'], cwd: workspacePath, json: false };
    }
    default:
      throwHttp(400, 'UNSUPPORTED', `Unknown provider: ${provider}`);
  }
}

export function startSessionProcess(
  sessionId: string,
  provider: string,
  workspacePath: string,
  launch: SessionLaunch = {}
): void {
  if (sessionProcesses.has(sessionId)) return;

  if ((provider === 'agent' || provider === 'codex') && !launch.prompt) {
    updateSessionState(sessionId, 'awaiting_input');
    return;
  }

  const { cmd, args, cwd, json } = providerCommand(provider, workspacePath, launch);
  const caps = getCapabilities();

  updateSessionState(sessionId, 'starting');

  if (provider === 'terminal' && caps.pty) {
    try {
      // eslint-disable-next-line @typescript-eslint/no-var-requires
      const pty = require('node-pty') as typeof import('node-pty');
      const ptyProcess = pty.spawn(cmd, args, {
        name: 'xterm-256color',
        cols: 80,
        rows: 24,
        cwd,
        env: {
          ...process.env,
          PATH: `${process.env.HOME}/.local/bin:${process.env.PATH ?? ''}`,
        } as Record<string, string>,
      });

      ptyProcess.onData((data: string) => {
        emitEvent(sessionId, 'output', { text: data });
        broadcastPtyOutput(sessionId, data);
      });

      ptyProcess.onExit(({ exitCode }: { exitCode: number }) => {
        const current = sessionProcesses.get(sessionId);
        if (current?.pty !== ptyProcess) return;
        sessionProcesses.delete(sessionId);
        updateSessionPid(sessionId, undefined);
        updateSessionState(sessionId, exitCode === 0 ? 'completed' : 'failed');
      });

      sessionProcesses.set(sessionId, { sessionId, process: null as unknown as ChildProcess, pty: ptyProcess, usePty: true });
      updateSessionPid(sessionId, ptyProcess.pid);
      updateSessionState(sessionId, 'running');
      return;
    } catch {
      // Fall through to spawn
    }
  }

  // Fallback: detached process supervisor
  const pipeInput = provider === 'claude' || provider === 'terminal';
  const child = spawn(cmd, args, {
    cwd,
    stdio: [pipeInput ? 'pipe' : 'ignore', 'pipe', 'pipe'],
    detached: true,
    env: {
      ...process.env,
      TERM: 'xterm-256color',
      PATH: `${process.env.HOME}/.local/bin:${process.env.PATH ?? ''}`,
    },
  });

  child.unref();

  const jsonStdin = provider === 'claude';
  attachReadableOutput(sessionId, child.stdout, json);
  attachStderr(sessionId, child.stderr);

  child.on('exit', (code) => {
    const current = sessionProcesses.get(sessionId);
    if (current?.process !== child) return;
    sessionProcesses.delete(sessionId);
    updateSessionPid(sessionId, undefined);
    if (current.interrupted) {
      emitEvent(sessionId, 'status', { text: 'Stopped' });
      updateSessionState(sessionId, 'awaiting_input');
      return;
    }
    const finished = provider === 'terminal' ? 'completed' : 'awaiting_input';
    updateSessionState(sessionId, code === 0 ? finished : 'failed');
  });

  child.on('error', (err) => {
    const current = sessionProcesses.get(sessionId);
    if (current?.process !== child) return;
    emitEvent(sessionId, 'error', { text: err.message });
    sessionProcesses.delete(sessionId);
    updateSessionPid(sessionId, undefined);
    updateSessionState(sessionId, 'failed');
  });

  sessionProcesses.set(sessionId, { sessionId, process: child, usePty: false, jsonStdin, review: !!launch.review });
  updateSessionPid(sessionId, child.pid);
  // Claude waits on stdin until sendPrompt; the other providers start working at once.
  updateSessionState(sessionId, jsonStdin ? 'awaiting_input' : 'running');
}

export function sendPrompt(sessionId: string, text: string): void {
  const entry = sessionProcesses.get(sessionId);
  if (!entry) throwHttp(409, 'BUSY', 'Session process is not running');

  if (entry.usePty && entry.pty) {
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    (entry.pty as any).write(text + '\n');
  } else if (entry.process?.stdin) {
    const line = entry.jsonStdin
      ? JSON.stringify({
          type: 'user',
          message: { role: 'user', content: [{ type: 'text', text }] },
        })
      : text;
    entry.process.stdin.write(line + '\n');
  }
  // A persistent Claude process goes back to awaiting_input when the turn's
  // result arrives, so mark it running for this turn.
  if (entry.jsonStdin) updateSessionState(sessionId, 'running');
}

function attachReadableOutput(sessionId: string, stream: NodeJS.ReadableStream | null, json: boolean): void {
  let pending = '';
  stream?.on('data', (data: Buffer) => {
    pending += data.toString('utf8');
    const lines = pending.split(/\r?\n/);
    pending = lines.pop() ?? '';
    for (const line of lines) {
      if (json) publishProviderLine(sessionId, line);
      else if (line.trim()) emitEvent(sessionId, 'output', { text: line });
    }
  });
}

function attachStderr(sessionId: string, stream: NodeJS.ReadableStream | null): void {
  let pending = '';
  stream?.on('data', (data: Buffer) => {
    pending += data.toString('utf8');
    const lines = pending.split(/\r?\n/);
    pending = lines.pop() ?? '';
    for (const line of lines) {
      const text = line.trim();
      if (!text || text.length > 500) continue;
      if (/error|not recognized|unknown option|invalid/i.test(text)) {
        emitEvent(sessionId, 'error', { text });
      }
    }
  });
}

function publishProviderLine(sessionId: string, line: string): void {
  const events = eventsFromProviderLine(line);
  if (events.length === 0) return;
  const db = getDb();
  for (const event of events) {
    if (event.type === 'delta') {
      emitDelta(sessionId, event.text);
      continue;
    }
    if (event.type === 'done') {
      if (event.usage) recordUsage(sessionId, event.usage);
      if (event.text) emitEvent(sessionId, 'status', { text: event.text });
      updateSessionState(sessionId, 'awaiting_input');
      continue;
    }
    if (event.providerSessionId && !sessionProcesses.get(sessionId)?.review) {
      db.prepare('UPDATE sessions SET provider_session_id = ?, updated_at = ? WHERE id = ?').run(
        event.providerSessionId,
        now(),
        sessionId
      );
    }
    if (event.info) {
      db.prepare('UPDATE sessions SET provider_info = ? WHERE id = ?').run(JSON.stringify(event.info), sessionId);
    }
    if (event.commands) {
      db.prepare('UPDATE sessions SET slash_commands = ? WHERE id = ?').run(JSON.stringify(event.commands), sessionId);
    }
    if (event.model) {
      db.prepare('UPDATE sessions SET model = ?, updated_at = ? WHERE id = ?').run(event.model, now(), sessionId);
    }
    if (event.text) emitEvent(sessionId, event.type, { text: event.text });
  }
}

export function resizeSession(sessionId: string, cols: number, rows: number): void {
  const entry = sessionProcesses.get(sessionId);
  if (!entry || !entry.usePty || !entry.pty) return;
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  (entry.pty as any).resize(cols, rows);
}

export function interruptSession(sessionId: string): void {
  const entry = sessionProcesses.get(sessionId);
  if (!entry) return;

  if (entry.usePty && entry.pty) {
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    (entry.pty as any).write('\x03');
    return;
  }
  // Writing ^C into a JSON stdin does nothing, so signal the process group.
  // The next prompt restarts the provider with --resume.
  if (!entry.process?.pid) return;
  entry.interrupted = true;
  try {
    process.kill(-entry.process.pid, 'SIGINT');
  } catch {
    entry.process.kill('SIGINT');
  }
  const pid = entry.process.pid;
  setTimeout(() => {
    if (sessionProcesses.get(sessionId)?.process?.pid !== pid) return;
    try { process.kill(-pid, 'SIGKILL'); } catch { /* already gone */ }
  }, 5_000).unref();
}

export function closeSession(sessionId: string): void {
  const entry = sessionProcesses.get(sessionId);
  if (!entry) return;

  if (entry.usePty && entry.pty) {
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    (entry.pty as any).kill();
  } else if (entry.process) {
    try {
      if (entry.process.pid) process.kill(-entry.process.pid, 'SIGTERM');
    } catch {
      entry.process.kill();
    }
  }

  sessionProcesses.delete(sessionId);
  updateSessionPid(sessionId, undefined);
  updateSessionState(sessionId, 'stopped');
}

// Managed dev processes

export function startManagedProcess(processId: string, projectRoot: string, command: string): void {
  if (managedProcesses.has(processId)) return;

  const child = spawn(command, [], {
    cwd: projectRoot,
    stdio: ['ignore', 'pipe', 'pipe'],
    detached: true,
    shell: true,
    env: { ...process.env },
  });

  child.unref();

  const db = getDb();

  const appendLog = (text: string) => {
    db.prepare('UPDATE managed_processes SET updated_at = ? WHERE id = ?').run(now(), processId);
    // Emit as a generic event so the client can watch logs
    emitEvent(processId, 'process_output', { processId, text });
  };

  child.stdout?.on('data', (d: Buffer) => appendLog(d.toString('utf8')));
  child.stderr?.on('data', (d: Buffer) => appendLog(d.toString('utf8')));

  child.on('exit', (code) => {
    managedProcesses.delete(processId);
    db.prepare('UPDATE managed_processes SET state = ?, pid = NULL, updated_at = ? WHERE id = ?').run(
      code === 0 ? 'stopped' : 'failed',
      now(),
      processId
    );
  });

  child.on('error', () => {
    managedProcesses.delete(processId);
    db.prepare('UPDATE managed_processes SET state = ?, pid = NULL, updated_at = ? WHERE id = ?').run(
      'failed',
      now(),
      processId
    );
  });

  managedProcesses.set(processId, { processId, process: child });
  db.prepare('UPDATE managed_processes SET state = ?, pid = ?, updated_at = ? WHERE id = ?').run(
    'running',
    child.pid ?? null,
    now(),
    processId
  );
}

export function stopManagedProcess(processId: string): void {
  const entry = managedProcesses.get(processId);
  if (!entry) return;

  try {
    if (entry.process.pid) process.kill(-entry.process.pid, 'SIGTERM');
  } catch {
    entry.process.kill();
  }

  managedProcesses.delete(processId);

  const db = getDb();
  db.prepare('UPDATE managed_processes SET state = ?, pid = NULL, updated_at = ? WHERE id = ?').run(
    'stopped',
    now(),
    processId
  );
}
