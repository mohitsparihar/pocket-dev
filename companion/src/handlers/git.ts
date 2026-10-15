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

async function git(cwd: string, args: string[]): Promise<{ stdout: string; stderr: string }> {
  try {
    const result = await execFileAsync('git', args, { cwd, timeout: 30000 });
    return { stdout: result.stdout, stderr: result.stderr };
  } catch (e: unknown) {
    const err = e as { stdout?: string; stderr?: string; message?: string };
    return {
      stdout: err.stdout ?? '',
      stderr: err.stderr ?? err.message ?? String(e),
    };
  }
}

export function registerGitRoutes(): void {
  register('GET', '/v1/git/status', async (req, res, _p, query) => {
    verifyToken(req);
    if (!query.projectId) throwHttp(400, 'VALIDATION', 'projectId is required');
    const project = requireProject(query.projectId);
    const result = await git(project.root_path, ['status', '--porcelain=v1', '--untracked-files=all']);
    send(res, 200, { status: result.stdout, stderr: result.stderr });
  });

  register('GET', '/v1/git/diff', async (req, res, _p, query) => {
    verifyToken(req);
    if (!query.projectId) throwHttp(400, 'VALIDATION', 'projectId is required');
    const project = requireProject(query.projectId);

    const base = query.base ?? 'worktree';
    const filePath = query.path;

    let args: string[];
    if (base === 'worktree') {
      args = ['diff'];
    } else if (base === 'index') {
      args = ['diff', '--cached'];
    } else {
      // Treat base as a commit ref
      args = ['diff', base];
    }

    if (filePath) {
      const absPath = safePath(project.root_path, filePath);
      args.push('--', absPath);
    }

    const result = await git(project.root_path, args);
    send(res, 200, { diff: result.stdout, stderr: result.stderr });
  });

  register('GET', '/v1/git/branches', async (req, res, _p, query) => {
    verifyToken(req);
    if (!query.projectId) throwHttp(400, 'VALIDATION', 'projectId is required');
    const project = requireProject(query.projectId);
    const result = await git(project.root_path, ['branch', '-a', '--format=%(refname:short) %(objectname:short) %(HEAD)']);
    const branches = result.stdout
      .split('\n')
      .filter(Boolean)
      .map((line) => {
        const parts = line.split(' ');
        return {
          name: parts[0] ?? '',
          sha: parts[1] ?? '',
          current: parts[2] === '*',
        };
      });
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
    const addResult = await git(project.root_path, ['add', '--', ...absPaths]);
    if (addResult.stderr && addResult.stderr.trim()) {
      throwHttp(400, 'VALIDATION', `git add failed: ${addResult.stderr.trim()}`);
    }

    const commitResult = await git(project.root_path, ['commit', '-m', body.message]);
    if (commitResult.stderr.includes('nothing to commit') || commitResult.stdout.includes('nothing to commit')) {
      throwHttp(400, 'VALIDATION', 'Nothing to commit');
    }
    if (!commitResult.stdout && commitResult.stderr) {
      const result = { ok: false, stderr: commitResult.stderr };
      if (ikey) storeIdempotency(ikey, endpoint, bodyHash(buf), 400, result);
      throwHttp(400, 'VALIDATION', commitResult.stderr.trim());
    }

    const result = { ok: true, stdout: commitResult.stdout, stderr: commitResult.stderr };
    if (ikey) storeIdempotency(ikey, endpoint, bodyHash(buf), 200, result);
    send(res, 200, result);
  });

  register('POST', '/v1/git/push', async (req, res) => {
    verifyToken(req);
    const buf = await readBody(req);
    const body = parseBody<{ projectId: string }>(buf);
    if (!body.projectId) throwHttp(400, 'VALIDATION', 'projectId is required');

    const project = requireProject(body.projectId);
    const result = await git(project.root_path, ['push']);
    send(res, 200, { ok: true, stdout: result.stdout, stderr: result.stderr });
  });

  register('POST', '/v1/git/pull', async (req, res) => {
    verifyToken(req);
    const buf = await readBody(req);
    const body = parseBody<{ projectId: string }>(buf);
    if (!body.projectId) throwHttp(400, 'VALIDATION', 'projectId is required');

    const project = requireProject(body.projectId);
    const result = await git(project.root_path, ['pull']);
    send(res, 200, { ok: true, stdout: result.stdout, stderr: result.stderr });
  });
}
