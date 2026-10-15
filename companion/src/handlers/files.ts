import * as fs from 'node:fs';
import * as path from 'node:path';
import { register, send } from '../router.js';
import { verifyToken } from '../auth.js';
import { getDb } from '../db.js';
import { readBody, parseBody } from '../util/idempotency.js';
import { throwHttp } from '../util/errors.js';
import { assertNotRoot, safePath } from '../util/paths.js';
import { fingerprintFile, fingerprintBuffer, isBinaryBuffer, MAX_TEXT_SIZE } from '../util/fingerprint.js';

interface ProjectRow { id: string; root_path: string; }

function requireProject(projectId: string): ProjectRow {
  const db = getDb();
  const p = db.prepare('SELECT id, root_path FROM projects WHERE id = ?').get(projectId) as unknown as ProjectRow | undefined;
  if (!p) throwHttp(404, 'NOT_FOUND', 'Project not found');
  return p;
}

export function registerFileRoutes(): void {
  // Directory listing
  register('GET', '/v1/files', (req, res, _p, query) => {
    verifyToken(req);
    if (!query.projectId) throwHttp(400, 'VALIDATION', 'projectId is required');
    const project = requireProject(query.projectId);
    const relPath = query.path ?? '';
    const absPath = safePath(project.root_path, relPath || '.');

    let stat: fs.Stats;
    try {
      stat = fs.statSync(absPath);
    } catch {
      throwHttp(404, 'NOT_FOUND', 'Path not found');
    }

    if (!stat.isDirectory()) {
      throwHttp(400, 'VALIDATION', 'Path is not a directory');
    }

    const entries = fs.readdirSync(absPath, { withFileTypes: true }).map((dirent) => {
      const entryPath = path.join(absPath, dirent.name);
      let size = 0;
      let symlink = false;
      let symlinkTarget: string | undefined;
      try {
        const lst = fs.lstatSync(entryPath);
        symlink = lst.isSymbolicLink();
        if (symlink) symlinkTarget = fs.readlinkSync(entryPath);
        const st = fs.statSync(entryPath);
        size = st.isFile() ? st.size : 0;
      } catch { /* ignore */ }
      return {
        name: dirent.name,
        type: dirent.isDirectory() ? 'directory' : dirent.isSymbolicLink() ? 'symlink' : 'file',
        size,
        symlink,
        symlinkTarget,
      };
    });

    send(res, 200, {
      path: path.relative(project.root_path, absPath) || '.',
      entries,
    });
  });

  // File content
  register('GET', '/v1/files/content', (req, res, _p, query) => {
    verifyToken(req);
    if (!query.projectId || !query.path) throwHttp(400, 'VALIDATION', 'projectId and path are required');
    const project = requireProject(query.projectId);
    const absPath = safePath(project.root_path, query.path);

    let buf: Buffer;
    try {
      buf = fs.readFileSync(absPath);
    } catch {
      throwHttp(404, 'NOT_FOUND', 'File not found');
    }

    const fingerprint = fingerprintBuffer(buf);
    const binary = isBinaryBuffer(buf) || buf.length > MAX_TEXT_SIZE;
    const truncated = buf.length > MAX_TEXT_SIZE && !binary;

    if (binary) {
      send(res, 200, {
        path: query.path,
        fingerprint,
        binary: true,
        truncated: false,
        text: null,
      });
      return;
    }

    const text = truncated
      ? buf.subarray(0, MAX_TEXT_SIZE).toString('utf8')
      : buf.toString('utf8');

    send(res, 200, { path: query.path, text, fingerprint, binary: false, truncated });
  });

  // Save (version-checked)
  register('PUT', '/v1/files/content', async (req, res) => {
    verifyToken(req);
    const buf = await readBody(req);
    const body = parseBody<{
      projectId: string;
      path: string;
      text: string;
      expectedFingerprint: string;
    }>(buf);

    if (!body.projectId || !body.path || body.text === undefined || body.text === null) {
      throwHttp(400, 'VALIDATION', 'projectId, path, and text are required');
    }

    const project = requireProject(body.projectId);
    const absPath = safePath(project.root_path, body.path);

    // Check current fingerprint
    if (fs.existsSync(absPath)) {
      const current = fingerprintFile(absPath);
      if (current !== body.expectedFingerprint) {
        let currentText: string | null = null;
        try {
          const currentBuf = fs.readFileSync(absPath);
          if (!isBinaryBuffer(currentBuf) && currentBuf.length <= MAX_TEXT_SIZE) {
            currentText = currentBuf.toString('utf8');
          }
        } catch { /* ignore */ }

        send(res, 409, {
          error: {
            code: 'REMOTE_CHANGED',
            message: 'File was modified since your last read',
            recoverable: true,
            details: { fingerprint: current, text: currentText },
          },
        });
        return;
      }
    } else if (body.expectedFingerprint && body.expectedFingerprint !== '') {
      // Client expects a specific fingerprint but file doesn't exist
      throwHttp(409, 'REMOTE_CHANGED', 'File no longer exists on disk');
    }

    // Atomic write via temp file
    const dir = path.dirname(absPath);
    fs.mkdirSync(dir, { recursive: true });
    const tmp = absPath + '.pdtmp';
    fs.writeFileSync(tmp, body.text, 'utf8');
    fs.renameSync(tmp, absPath);

    const newFingerprint = fingerprintFile(absPath);
    send(res, 200, { path: body.path, fingerprint: newFingerprint });
  });

  register('POST', '/v1/files/mkdir', async (req, res) => {
    verifyToken(req);
    const buf = await readBody(req);
    const body = parseBody<{ projectId: string; path: string }>(buf);
    if (!body.projectId || !body.path) throwHttp(400, 'VALIDATION', 'projectId and path are required');

    const project = requireProject(body.projectId);
    const absPath = safePath(project.root_path, body.path);
    fs.mkdirSync(absPath, { recursive: true });
    send(res, 200, { ok: true, path: body.path });
  });

  register('POST', '/v1/files/rename', async (req, res) => {
    verifyToken(req);
    const buf = await readBody(req);
    const body = parseBody<{ projectId: string; path: string; newName: string }>(buf);
    if (!body.projectId || !body.path || !body.newName) {
      throwHttp(400, 'VALIDATION', 'projectId, path, and newName are required');
    }

    const project = requireProject(body.projectId);
    const absPath = safePath(project.root_path, body.path);
    assertNotRoot(project.root_path, absPath);
    const newPath = safePath(project.root_path, path.join(path.dirname(body.path), body.newName));

    if (!fs.existsSync(absPath)) throwHttp(404, 'NOT_FOUND', 'Path not found');
    fs.renameSync(absPath, newPath);
    send(res, 200, { ok: true });
  });

  register('POST', '/v1/files/move', async (req, res) => {
    verifyToken(req);
    const buf = await readBody(req);
    const body = parseBody<{ projectId: string; path: string; destination: string }>(buf);
    if (!body.projectId || !body.path || !body.destination) {
      throwHttp(400, 'VALIDATION', 'projectId, path, and destination are required');
    }

    const project = requireProject(body.projectId);
    const absPath = safePath(project.root_path, body.path);
    const destPath = safePath(project.root_path, body.destination);
    assertNotRoot(project.root_path, absPath);

    if (!fs.existsSync(absPath)) throwHttp(404, 'NOT_FOUND', 'Path not found');
    fs.mkdirSync(path.dirname(destPath), { recursive: true });
    fs.renameSync(absPath, destPath);
    send(res, 200, { ok: true });
  });

  register('POST', '/v1/files/copy', async (req, res) => {
    verifyToken(req);
    const buf = await readBody(req);
    const body = parseBody<{ projectId: string; path: string; destination: string }>(buf);
    if (!body.projectId || !body.path || !body.destination) {
      throwHttp(400, 'VALIDATION', 'projectId, path, and destination are required');
    }

    const project = requireProject(body.projectId);
    const absPath = safePath(project.root_path, body.path);
    const destPath = safePath(project.root_path, body.destination);
    assertNotRoot(project.root_path, absPath);

    if (!fs.existsSync(absPath)) throwHttp(404, 'NOT_FOUND', 'Path not found');
    fs.mkdirSync(path.dirname(destPath), { recursive: true });
    fs.cpSync(absPath, destPath, { recursive: true });
    send(res, 200, { ok: true });
  });

  register('POST', '/v1/files/delete', async (req, res) => {
    verifyToken(req);
    const buf = await readBody(req);
    const body = parseBody<{ projectId: string; paths: string[] }>(buf);
    if (!body.projectId || !Array.isArray(body.paths)) {
      throwHttp(400, 'VALIDATION', 'projectId and paths[] are required');
    }

    const project = requireProject(body.projectId);
    // Check every path before deleting any, so one bad path deletes nothing.
    const targets = body.paths.map((p) => safePath(project.root_path, p));
    for (const absPath of targets) assertNotRoot(project.root_path, absPath);
    for (const absPath of targets) {
      if (fs.existsSync(absPath)) {
        fs.rmSync(absPath, { recursive: true, force: true });
      }
    }
    send(res, 200, { ok: true });
  });
}
