import { spawn } from 'node:child_process';
import { register, send } from '../router.js';
import { verifyToken } from '../auth.js';
import { getDb, newId, now } from '../db.js';
import { readBody, parseBody, checkIdempotency, storeIdempotency, bodyHash } from '../util/idempotency.js';
import { throwHttp } from '../util/errors.js';

interface RecipeRow {
  id: string;
  project_id: string;
  name: string;
  command: string;
  working_dir: string | null;
  health_check_url: string | null;
  created_at: number;
}

interface RunRow {
  id: string;
  project_id: string;
  recipe_id: string;
  idempotency_key: string | null;
  state: string;
  logs: string;
  started_at: number | null;
  completed_at: number | null;
}

interface ProjectRow { id: string; root_path: string; }

function requireProject(projectId: string): ProjectRow {
  const db = getDb();
  const p = db.prepare('SELECT id, root_path FROM projects WHERE id = ?').get(projectId) as unknown as ProjectRow | undefined;
  if (!p) throwHttp(404, 'NOT_FOUND', 'Project not found');
  return p;
}

function recipeToJson(r: RecipeRow) {
  return {
    id: r.id,
    projectId: r.project_id,
    name: r.name,
    command: r.command,
    workingDir: r.working_dir,
    healthCheckUrl: r.health_check_url,
    createdAt: r.created_at,
  };
}

function runToJson(r: RunRow) {
  return {
    id: r.id,
    projectId: r.project_id,
    recipeId: r.recipe_id,
    idempotencyKey: r.idempotency_key,
    state: r.state,
    logs: r.logs,
    startedAt: r.started_at,
    completedAt: r.completed_at,
  };
}

export function registerDeployRoutes(): void {
  register('GET', '/v1/deploy/recipes', (req, res, _p, query) => {
    verifyToken(req);
    if (!query.projectId) throwHttp(400, 'VALIDATION', 'projectId is required');
    requireProject(query.projectId);
    const db = getDb();
    const rows = db
      .prepare('SELECT * FROM deploy_recipes WHERE project_id = ? ORDER BY created_at DESC')
      .all(query.projectId) as unknown as RecipeRow[];
    send(res, 200, rows.map(recipeToJson));
  });

  register('POST', '/v1/deploy/recipes', async (req, res) => {
    verifyToken(req);
    const buf = await readBody(req);
    const body = parseBody<{
      projectId: string;
      name: string;
      command: string;
      workingDir?: string;
      healthCheckUrl?: string;
    }>(buf);
    if (!body.projectId || !body.name || !body.command) {
      throwHttp(400, 'VALIDATION', 'projectId, name, and command are required');
    }

    requireProject(body.projectId);
    const db = getDb();
    const id = newId();

    db.prepare(
      `INSERT INTO deploy_recipes (id, project_id, name, command, working_dir, health_check_url, created_at)
       VALUES (?, ?, ?, ?, ?, ?, ?)`
    ).run(id, body.projectId, body.name, body.command, body.workingDir ?? null, body.healthCheckUrl ?? null, now());

    const row = db.prepare('SELECT * FROM deploy_recipes WHERE id = ?').get(id) as unknown as RecipeRow;
    send(res, 201, recipeToJson(row));
  });

  register('POST', '/v1/deploy/runs', async (req, res) => {
    verifyToken(req);
    const buf = await readBody(req);
    const ikey = req.headers['idempotency-key'] as string | undefined;
    const endpoint = 'POST /v1/deploy/runs';

    if (ikey) {
      const hash = bodyHash(buf);
      const cached = checkIdempotency(ikey, endpoint, hash);
      if (cached) { send(res, cached.statusCode, cached.body); return; }

      // Also check by idempotency_key directly in deploy_runs
      const db = getDb();
      const existing = db
        .prepare('SELECT * FROM deploy_runs WHERE idempotency_key = ?')
        .get(ikey) as unknown as RunRow | undefined;
      if (existing) {
        send(res, 200, runToJson(existing));
        return;
      }
    }

    const body = parseBody<{ projectId: string; recipeId: string }>(buf);
    if (!body.projectId || !body.recipeId) {
      throwHttp(400, 'VALIDATION', 'projectId and recipeId are required');
    }

    const project = requireProject(body.projectId);
    const db = getDb();
    const recipe = db
      .prepare('SELECT * FROM deploy_recipes WHERE id = ? AND project_id = ?')
      .get(body.recipeId, body.projectId) as unknown as RecipeRow | undefined;
    if (!recipe) throwHttp(404, 'NOT_FOUND', 'Recipe not found');

    const id = newId();
    const startedAt = now();

    db.prepare(
      `INSERT INTO deploy_runs (id, project_id, recipe_id, idempotency_key, state, logs, started_at)
       VALUES (?, ?, ?, ?, 'running', '', ?)`
    ).run(id, body.projectId, body.recipeId, ikey ?? null, startedAt);

    const cwd = recipe.working_dir ?? project.root_path;

    // Run the deploy command asynchronously
    setImmediate(() => {
      const child = spawn(recipe.command, [], {
        cwd,
        shell: true,
        stdio: ['ignore', 'pipe', 'pipe'],
      });

      let logs = '';
      const appendLog = (text: string) => {
        logs += text;
        db.prepare('UPDATE deploy_runs SET logs = ? WHERE id = ?').run(logs, id);
      };

      child.stdout?.on('data', (d: Buffer) => appendLog(d.toString('utf8')));
      child.stderr?.on('data', (d: Buffer) => appendLog(d.toString('utf8')));

      child.on('exit', (code) => {
        const state = code === 0 ? 'completed' : 'failed';
        db.prepare('UPDATE deploy_runs SET state = ?, completed_at = ? WHERE id = ?').run(state, now(), id);
      });

      child.on('error', (err) => {
        appendLog(`\nError: ${err.message}`);
        db.prepare('UPDATE deploy_runs SET state = ?, completed_at = ? WHERE id = ?').run('failed', now(), id);
      });
    });

    const row = db.prepare('SELECT * FROM deploy_runs WHERE id = ?').get(id) as unknown as RunRow;
    const result = runToJson(row);

    if (ikey) storeIdempotency(ikey, endpoint, bodyHash(buf), 201, result);
    send(res, 201, result);
  });

  register('GET', '/v1/deploy/runs/:id', (req, res, params) => {
    verifyToken(req);
    const db = getDb();
    const row = db
      .prepare('SELECT * FROM deploy_runs WHERE id = ?')
      .get(params.id) as unknown as RunRow | undefined;
    if (!row) throwHttp(404, 'NOT_FOUND', 'Run not found');
    send(res, 200, runToJson(row));
  });
}
