import * as path from 'node:path';
import * as fs from 'node:fs';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { register, send } from '../router.js';
import { verifyToken } from '../auth.js';
import { getDb } from '../db.js';
import { readBody, parseBody, checkIdempotency, storeIdempotency, bodyHash } from '../util/idempotency.js';
import { throwHttp } from '../util/errors.js';
import { safePath } from '../util/paths.js';

const execFileAsync = promisify(execFile);

interface ProjectRow { id: string; root_path: string; }

function requireProject(projectId: string): ProjectRow {
  const db = getDb();
  const p = db.prepare('SELECT id, root_path FROM projects WHERE id = ?').get(projectId) as unknown as ProjectRow | undefined;
  if (!p) throwHttp(404, 'NOT_FOUND', 'Project not found');
  return p;
}

interface GitRun {
  ok: boolean;
  stdout: string;
  stderr: string;
}

// Git writes progress and warnings to stderr even when it succeeds, so success
// is the exit code, never "stderr is empty".
function notARepo(stderr: string): string {
  if (/not a git repository/i.test(stderr)) return 'This project folder is not a git repository';
  return stderr.trim() || 'git failed';
}

interface StatusFile {
  path: string;
  status: string;
  staged: boolean;
}

/**
 * Parses `git status --porcelain=v1 -z --branch` into what the phone shows:
 * the branch, ahead/behind counts, and one entry per path.
 */
export function parseStatus(out: string): { branch: string; ahead: number; behind: number; files: StatusFile[] } {
  const entries = out.split('\0');
  let branch = '';
  let ahead = 0;
  let behind = 0;
  const files: StatusFile[] = [];
  for (let i = 0; i < entries.length; i++) {
    const entry = entries[i];
    if (!entry) continue;
    if (entry.startsWith('## ')) {
      const head = entry.slice(3);
      branch = head.startsWith('No commits yet on ') ? head.slice('No commits yet on '.length) : head.split('...')[0].split(' ')[0];
      if (branch === 'HEAD') branch = 'detached HEAD';
      ahead = Number(/ahead (\d+)/.exec(head)?.[1] ?? 0);
      behind = Number(/behind (\d+)/.exec(head)?.[1] ?? 0);
      continue;
    }
    const x = entry[0];
    const y = entry[1];
    const path = entry.slice(3);
    // A rename or copy is followed by its original path; skip that entry.
    if (x === 'R' || x === 'C') i++;
    const code = x === '?' ? '?' : x === 'U' || y === 'U' || (x === 'A' && y === 'A') || (x === 'D' && y === 'D') ? 'U' : y !== ' ' ? y : x;
    const status =
      { '?': 'untracked', M: 'modified', A: 'added', D: 'deleted', R: 'renamed', C: 'copied', U: 'conflict', T: 'modified' }[code] ??
      'modified';
    files.push({ path, status, staged: x !== ' ' && x !== '?' });
  }
  return { branch, ahead, behind, files };
}

async function git(cwd: string, args: string[], timeout = 30000): Promise<GitRun> {
  try {
    const result = await execFileAsync('git', args, { cwd, timeout });
    return { ok: true, stdout: result.stdout, stderr: result.stderr };
  } catch (e: unknown) {
    const err = e as { stdout?: string; stderr?: string; message?: string };
    return {
      ok: false,
      stdout: err.stdout ?? '',
      stderr: err.stderr || err.message || String(e),
    };
  }
}

/** The phone shows `message`; keep the raw streams for detail. */
function gitResult(run: GitRun, success: string) {
  const output = [run.stdout, run.stderr].map((s) => s.trim()).filter(Boolean).join('\n');
  return {
    ok: run.ok,
    message: run.ok ? (output ? `${success}\n${output}` : success) : output || 'git failed',
    stdout: run.stdout,
    stderr: run.stderr,
  };
}

export function registerGitRoutes(): void {
  register('GET', '/v1/git/status', async (req, res, _p, query) => {
    verifyToken(req);
    if (!query.projectId) throwHttp(400, 'VALIDATION', 'projectId is required');
    const project = requireProject(query.projectId);
    const result = await git(project.root_path, ['status', '--porcelain=v1', '-z', '--branch', '--untracked-files=all']);
    if (!result.ok) throwHttp(400, 'VALIDATION', notARepo(result.stderr));
    send(res, 200, parseStatus(result.stdout));
  });

  register('GET', '/v1/git/diff', async (req, res, _p, query) => {
    verifyToken(req);
    if (!query.projectId) throwHttp(400, 'VALIDATION', 'projectId is required');
    const project = requireProject(query.projectId);

    const base = query.base ?? 'worktree';
    let args: string[];
    if (base === 'worktree') {
      args = ['diff'];
    } else if (base === 'index') {
      args = ['diff', '--cached'];
    } else {
      // The phone sends base=commit with the ref in `commit`; older clients put the ref in base.
      const ref = (base === 'commit' ? query.commit : base)?.trim() ?? '';
      if (!/^[\w./~^@{}-]+$/.test(ref) || ref.startsWith('-')) throwHttp(400, 'VALIDATION', 'Enter a commit hash, branch or tag');
      args = ['diff', ref];
    }

    let absPath = '';
    if (query.path) {
      absPath = safePath(project.root_path, query.path);
      args.push('--', absPath);
    }

    let result = await git(project.root_path, args);
    // A new file git doesn't track yet has no diff; show its whole content as added.
    if (result.ok && !result.stdout && absPath && base === 'worktree') {
      const tracked = await git(project.root_path, ['ls-files', '--error-unmatch', '--', absPath]);
      if (!tracked.ok && fs.existsSync(absPath) && fs.statSync(absPath).isFile()) {
        // Relative to the project, so the diff header reads like any other file's.
        const added = await git(project.root_path, ['diff', '--no-index', '--', '/dev/null', path.relative(project.root_path, absPath)]);
        result = { ok: true, stdout: added.stdout, stderr: '' };
      }
    }
    if (!result.ok) throwHttp(400, 'VALIDATION', notARepo(result.stderr));
    send(res, 200, { diff: result.stdout, stderr: result.stderr });
  });

  register('GET', '/v1/git/log', async (req, res, _p, query) => {
    verifyToken(req);
    if (!query.projectId) throwHttp(400, 'VALIDATION', 'projectId is required');
    const project = requireProject(query.projectId);
    const limit = Math.min(Math.max(parseInt(query.limit ?? '30', 10) || 30, 1), 200);
    // Unit and record separators can't appear in commit metadata.
    const result = await git(project.root_path, ['log', `-${limit}`, '--format=%H%x1f%h%x1f%an%x1f%at%x1f%s%x1e']);
    if (!result.ok) {
      // A repository with no commits yet has an empty history, not an error.
      if (/does not have any commits|bad default revision/.test(result.stderr)) {
        send(res, 200, []);
        return;
      }
      throwHttp(400, 'VALIDATION', notARepo(result.stderr));
    }
    const commits = result.stdout
      .split('\x1e')
      .map((r) => r.trim())
      .filter(Boolean)
      .map((record) => {
        const [sha, shortSha, author, at, subject] = record.split('\x1f');
        return { sha, shortSha, author, at: Number(at) * 1000, subject };
      });
    send(res, 200, commits);
  });

  register('GET', '/v1/git/branches', async (req, res, _p, query) => {
    verifyToken(req);
    if (!query.projectId) throwHttp(400, 'VALIDATION', 'projectId is required');
    const project = requireProject(query.projectId);
    const result = await git(project.root_path, ['branch', '-a', '--format=%(refname)%09%(refname:short)%09%(objectname:short)%09%(HEAD)']);
    const branches = result.stdout
      .split('\n')
      .filter(Boolean)
      .map((line) => {
        const [ref = '', name = '', sha = '', head = ''] = line.split('\t');
        return { name, sha, current: head === '*', remote: ref.startsWith('refs/remotes/'), ref };
      })
      // origin/HEAD only points at another remote branch.
      .filter((b) => !(b.remote && b.ref.endsWith('/HEAD')))
      .map(({ ref: _ref, ...b }) => b);
    send(res, 200, { branches, stderr: result.stderr });
  });

  register('POST', '/v1/git/commit', async (req, res) => {
    verifyToken(req);
    const buf = await readBody(req);
    const ikey = req.headers['idempotency-key'] as string | undefined;
    const endpoint = 'POST /v1/git/commit';

    if (ikey) {
      const hash = bodyHash(buf);
      const cached = checkIdempotency(ikey, endpoint, hash);
      if (cached) { send(res, cached.statusCode, cached.body); return; }
    }

    const body = parseBody<{ projectId: string; paths: string[]; message: string }>(buf);
    if (!body.projectId || !Array.isArray(body.paths) || !body.message) {
      throwHttp(400, 'VALIDATION', 'projectId, paths[], and message are required');
    }
    if (body.paths.length === 0) {
      throwHttp(400, 'VALIDATION', 'paths must not be empty');
    }

    const project = requireProject(body.projectId);

    // Validate each path is inside the project root
    const absPaths = body.paths.map((p) => safePath(project.root_path, p));

    // Stage only the specified paths — never git add -A
    // Literal pathspecs: a file named a[1].txt must not also match a1.txt.
    const addResult = await git(project.root_path, ['--literal-pathspecs', 'add', '--', ...absPaths]);
    if (!addResult.ok) {
      throwHttp(400, 'VALIDATION', `git add failed: ${addResult.stderr.trim()}`);
    }

    // The pathspec limits the commit to these files; anything else already
    // staged stays staged and is not included.
    const commitResult = await git(project.root_path, ['--literal-pathspecs', 'commit', '-m', body.message, '--', ...absPaths]);
    if (!commitResult.ok) {
      const output = `${commitResult.stdout}\n${commitResult.stderr}`;
      throwHttp(400, 'VALIDATION', output.includes('nothing to commit') || output.includes('no changes added') ? 'Nothing to commit' : commitResult.stderr.trim() || 'git commit failed');
    }

    const result = gitResult(commitResult, 'Committed');
    if (ikey) storeIdempotency(ikey, endpoint, bodyHash(buf), 200, result);
    send(res, 200, result);
  });

  register('POST', '/v1/git/push', async (req, res) => {
    verifyToken(req);
    const buf = await readBody(req);
    const body = parseBody<{ projectId: string }>(buf);
    if (!body.projectId) throwHttp(400, 'VALIDATION', 'projectId is required');

    const project = requireProject(body.projectId);
    const result = gitResult(await git(project.root_path, ['push'], 120000), 'Pushed');
    send(res, 200, result);
  });

  register('POST', '/v1/git/pull', async (req, res) => {
    verifyToken(req);
    const buf = await readBody(req);
    const body = parseBody<{ projectId: string }>(buf);
    if (!body.projectId) throwHttp(400, 'VALIDATION', 'projectId is required');

    const project = requireProject(body.projectId);
    const result = gitResult(await git(project.root_path, ['pull'], 120000), 'Pulled');
    send(res, 200, result);
  });
}
