import * as fs from 'node:fs';
import { register, send } from '../router.js';
import { verifyToken } from '../auth.js';
import { getDb, newId, now } from '../db.js';
import { readBody, parseBody, checkIdempotency, storeIdempotency, bodyHash } from '../util/idempotency.js';
import { throwHttp } from '../util/errors.js';

interface ProjectRow {
  id: string;
  name: string;
  root_path: string;
  dev_command: string | null;
  expected_ports: string;
  created_at: number;
  updated_at: number;
}

function projectRowToJson(r: ProjectRow) {
  const ports = JSON.parse(r.expected_ports) as number[];
  return {
    id: r.id,
    name: r.name,
    rootPath: r.root_path,
    folder: r.root_path,
    branch: '',
    devCommand: r.dev_command ?? '',
    testCommand: '',
    expectedPorts: ports,
    ports,
    createdAt: r.created_at,
    updatedAt: r.updated_at,
  };
}

export function registerProjectRoutes(): void {
  register('GET', '/v1/projects', (req, res) => {
    verifyToken(req);
    const db = getDb();
    const rows = db.prepare('SELECT * FROM projects ORDER BY created_at DESC').all() as unknown as ProjectRow[];
    send(res, 200, rows.map(projectRowToJson));
  });

  register('POST', '/v1/projects', async (req, res) => {
    verifyToken(req);
    const buf = await readBody(req);
    const ikey = req.headers['idempotency-key'] as string | undefined;
    const endpoint = 'POST /v1/projects';

    if (ikey) {
      const hash = bodyHash(buf);
      const cached = checkIdempotency(ikey, endpoint, hash);
      if (cached) {
        send(res, cached.statusCode, cached.body);
        return;
      }
    }

    const body = parseBody<{ name: string; rootPath: string; devCommand?: string; expectedPorts?: number[] }>(buf);

    if (!body.name || !body.rootPath) {
      throwHttp(400, 'VALIDATION', 'name and rootPath are required');
    }
    if (!fs.existsSync(body.rootPath)) {
      throwHttp(400, 'VALIDATION', 'rootPath does not exist');
    }

    const db = getDb();
    const id = newId();
    const ts = now();

    db.prepare(
      `INSERT INTO projects (id, name, root_path, dev_command, expected_ports, created_at, updated_at)
       VALUES (?, ?, ?, ?, ?, ?, ?)`
    ).run(id, body.name, body.rootPath, body.devCommand ?? null, JSON.stringify(body.expectedPorts ?? []), ts, ts);

    const project = db.prepare('SELECT * FROM projects WHERE id = ?').get(id) as unknown as ProjectRow;
    const result = projectRowToJson(project);

    if (ikey) {
      storeIdempotency(ikey, endpoint, bodyHash(buf), 201, result);
    }

    send(res, 201, result);
  });

  register('GET', '/v1/projects/:id', (req, res, params) => {
    verifyToken(req);
    const db = getDb();
    const row = db.prepare('SELECT * FROM projects WHERE id = ?').get(params.id) as unknown as ProjectRow | undefined;
    if (!row) throwHttp(404, 'NOT_FOUND', 'Project not found');
    send(res, 200, projectRowToJson(row));
  });

  register('PATCH', '/v1/projects/:id', async (req, res, params) => {
    verifyToken(req);
    const db = getDb();
    const existing = db.prepare('SELECT * FROM projects WHERE id = ?').get(params.id) as unknown as ProjectRow | undefined;
    if (!existing) throwHttp(404, 'NOT_FOUND', 'Project not found');

    const buf = await readBody(req);
    const body = parseBody<{ name?: string; devCommand?: string; expectedPorts?: number[] }>(buf);

    const name = body.name ?? existing.name;
    const devCommand = 'devCommand' in body ? (body.devCommand ?? null) : existing.dev_command;
    const expectedPorts = body.expectedPorts !== undefined
      ? JSON.stringify(body.expectedPorts)
      : existing.expected_ports;

    db.prepare(
      `UPDATE projects SET name = ?, dev_command = ?, expected_ports = ?, updated_at = ? WHERE id = ?`
    ).run(name, devCommand, expectedPorts, now(), params.id);

    const updated = db.prepare('SELECT * FROM projects WHERE id = ?').get(params.id) as unknown as ProjectRow;
    send(res, 200, projectRowToJson(updated));
  });
}
