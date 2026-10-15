import { DatabaseSync } from 'node:sqlite';
import * as os from 'node:os';
import * as path from 'node:path';
import * as fs from 'node:fs';
import * as crypto from 'node:crypto';

export const POCKET_DIR = path.join(os.homedir(), '.pocket-dev');
export const DB_PATH = path.join(POCKET_DIR, 'pocket.db');

let _db: DatabaseSync | null = null;
let _globalSeq = 0;

export function getDb(): DatabaseSync {
  if (!_db) throw new Error('DB not initialized');
  return _db;
}

export function currentSeq(): number {
  return _globalSeq;
}

export function nextSeq(): number {
  return ++_globalSeq;
}

export function newId(): string {
  return crypto.randomUUID();
}

export function now(): number {
  return Date.now();
}

function addColumn(table: string, column: string, decl: string): void {
  if (!_db) return;
  const cols = _db.prepare(`PRAGMA table_info(${table})`).all() as Array<{ name: string }>;
  if (!cols.some((col) => col.name === column)) {
    _db.exec(`ALTER TABLE ${table} ADD COLUMN ${column} ${decl}`);
  }
}

export function initDb(): DatabaseSync {
  fs.mkdirSync(POCKET_DIR, { recursive: true });
  _db = new DatabaseSync(DB_PATH);

  _db.exec(`PRAGMA journal_mode=WAL`);
  _db.exec(`PRAGMA foreign_keys=ON`);

  _db.exec(`
    CREATE TABLE IF NOT EXISTS projects (
      id TEXT PRIMARY KEY,
      name TEXT NOT NULL,
      root_path TEXT NOT NULL,
      dev_command TEXT,
      expected_ports TEXT NOT NULL DEFAULT '[]',
      created_at INTEGER NOT NULL,
      updated_at INTEGER NOT NULL
    )
  `);

  _db.exec(`
    CREATE TABLE IF NOT EXISTS sessions (
      id TEXT PRIMARY KEY,
      project_id TEXT NOT NULL REFERENCES projects(id),
      provider TEXT NOT NULL,
      title TEXT,
      state TEXT NOT NULL DEFAULT 'queued',
      connectivity TEXT NOT NULL DEFAULT 'offline',
      workspace TEXT NOT NULL DEFAULT 'worktree',
      workspace_path TEXT,
      provider_session_id TEXT,
      process_pid INTEGER,
      archived INTEGER NOT NULL DEFAULT 0,
      created_at INTEGER NOT NULL,
      updated_at INTEGER NOT NULL
    )
  `);

  _db.exec(`
    CREATE TABLE IF NOT EXISTS session_events (
      id TEXT PRIMARY KEY,
      session_id TEXT NOT NULL REFERENCES sessions(id),
      seq INTEGER NOT NULL UNIQUE,
      type TEXT NOT NULL,
      payload TEXT NOT NULL DEFAULT '{}',
      at INTEGER NOT NULL
    )
  `);

  _db.exec(`
    CREATE TABLE IF NOT EXISTS approvals (
      id TEXT PRIMARY KEY,
      session_id TEXT NOT NULL REFERENCES sessions(id),
      description TEXT,
      action TEXT,
      allow INTEGER,
      created_at INTEGER NOT NULL,
      resolved_at INTEGER
    )
  `);

  _db.exec(`
    CREATE TABLE IF NOT EXISTS idempotency_keys (
      ikey TEXT NOT NULL,
      endpoint TEXT NOT NULL,
      body_hash TEXT NOT NULL,
      response TEXT NOT NULL,
      status_code INTEGER NOT NULL,
      created_at INTEGER NOT NULL,
      PRIMARY KEY (ikey, endpoint)
    )
  `);

  _db.exec(`
    CREATE TABLE IF NOT EXISTS managed_processes (
      id TEXT PRIMARY KEY,
      project_id TEXT NOT NULL REFERENCES projects(id),
      label TEXT NOT NULL,
      command TEXT NOT NULL,
      pid INTEGER,
      state TEXT NOT NULL DEFAULT 'stopped',
      created_at INTEGER NOT NULL,
      updated_at INTEGER NOT NULL
    )
  `);

  _db.exec(`
    CREATE TABLE IF NOT EXISTS deploy_recipes (
      id TEXT PRIMARY KEY,
      project_id TEXT NOT NULL REFERENCES projects(id),
      name TEXT NOT NULL,
      command TEXT NOT NULL,
      working_dir TEXT,
      health_check_url TEXT,
      created_at INTEGER NOT NULL
    )
  `);

  _db.exec(`
    CREATE TABLE IF NOT EXISTS deploy_runs (
      id TEXT PRIMARY KEY,
      project_id TEXT NOT NULL REFERENCES projects(id),
      recipe_id TEXT NOT NULL REFERENCES deploy_recipes(id),
      idempotency_key TEXT,
      state TEXT NOT NULL DEFAULT 'pending',
      logs TEXT NOT NULL DEFAULT '',
      started_at INTEGER,
      completed_at INTEGER
    )
  `);

  _db.exec(`
    CREATE TABLE IF NOT EXISTS prompt_ops (
      ikey TEXT PRIMARY KEY,
      session_id TEXT NOT NULL,
      text TEXT NOT NULL,
      state TEXT NOT NULL,
      created_at INTEGER NOT NULL
    )
  `);

  _db.exec(`CREATE INDEX IF NOT EXISTS idx_session_events_session ON session_events (session_id, seq)`);

  addColumn('sessions', 'model', 'TEXT');
  addColumn('sessions', 'effort', 'TEXT');
  addColumn('sessions', 'mode', "TEXT NOT NULL DEFAULT 'agent'");
  addColumn('sessions', 'slash_commands', 'TEXT');
  addColumn('sessions', 'provider_info', 'TEXT');
  addColumn('sessions', 'usage', 'TEXT');
  addColumn('sessions', 'process_started', 'TEXT');

  // Recover global seq counter
  const row = _db.prepare('SELECT MAX(seq) as max_seq FROM session_events').get() as { max_seq: number | null };
  _globalSeq = row?.max_seq ?? 0;

  return _db;
}
