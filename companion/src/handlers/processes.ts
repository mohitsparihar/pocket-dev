import { register, send } from '../router.js';
import { verifyToken } from '../auth.js';
import { getDb, newId, now } from '../db.js';
import { readBody, parseBody } from '../util/idempotency.js';
import { throwHttp } from '../util/errors.js';
import { startManagedProcess, stopManagedProcess } from '../providers/manager.js';

interface ProcessRow {
  id: string;
  project_id: string;
  label: string;
  command: string;
  pid: number | null;
  state: string;
  created_at: number;
  updated_at: number;
}

interface ProjectRow { id: string; root_path: string; }

function requireProject(projectId: string): ProjectRow {
  const db = getDb();
  const p = db.prepare('SELECT id, root_path FROM projects WHERE id = ?').get(projectId) as unknown as ProjectRow | undefined;
  if (!p) throwHttp(404, 'NOT_FOUND', 'Project not found');
  return p;
}

function rowToJson(r: ProcessRow) {
  return {
    id: r.id,
    projectId: r.project_id,
    label: r.label,
    command: r.command,
    pid: r.pid,
    state: r.state,
    createdAt: r.created_at,
    updatedAt: r.updated_at,
  };
}

export function registerProcessRoutes(): void {
  register('GET', '/v1/processes', (req, res, _p, query) => {
    verifyToken(req);
    if (!query.projectId) throwHttp(400, 'VALIDATION', 'projectId is required');
    requireProject(query.projectId);
    const db = getDb();
    const rows = db
      .prepare('SELECT * FROM managed_processes WHERE project_id = ? ORDER BY created_at DESC')
      .all(query.projectId) as unknown as ProcessRow[];
    send(res, 200, rows.map(rowToJson));
  });

  register('POST', '/v1/processes', async (req, res) => {
    verifyToken(req);
    const buf = await readBody(req);
    const body = parseBody<{ projectId: string; label: string; command: string }>(buf);
    if (!body.projectId || !body.label || !body.command) {
      throwHttp(400, 'VALIDATION', 'projectId, label, and command are required');
    }

    const project = requireProject(body.projectId);
    const db = getDb();
    const id = newId();
    const ts = now();

    db.prepare(
      `INSERT INTO managed_processes (id, project_id, label, command, pid, state, created_at, updated_at)
       VALUES (?, ?, ?, ?, NULL, 'stopped', ?, ?)`
    ).run(id, body.projectId, body.label, body.command, ts, ts);

    setImmediate(() => startManagedProcess(id, project.root_path, body.command));

    const row = db.prepare('SELECT * FROM managed_processes WHERE id = ?').get(id) as unknown as ProcessRow;
    send(res, 201, rowToJson(row));
  });

  register('POST', '/v1/processes/:id/stop', async (req, res, params) => {
    verifyToken(req);
    const db = getDb();
    const row = db
      .prepare('SELECT * FROM managed_processes WHERE id = ?')
      .get(params.id) as unknown as ProcessRow | undefined;
    if (!row) throwHttp(404, 'NOT_FOUND', 'Process not found');

    stopManagedProcess(params.id);
    send(res, 200, { ok: true });
  });
}
