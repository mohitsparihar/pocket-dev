import * as crypto from 'node:crypto';
import * as http from 'node:http';
import { getDb, now } from '../db.js';
import { throwHttp } from './errors.js';

export function bodyHash(body: Buffer): string {
  return crypto.createHash('sha256').update(body).digest('hex');
}

interface IdempotencyRecord {
  ikey: string;
  endpoint: string;
  body_hash: string;
  response: string;
  status_code: number;
  created_at: number;
}

export function checkIdempotency(
  key: string,
  endpoint: string,
  hash: string
): { statusCode: number; body: unknown } | null {
  const db = getDb();
  const row = db
    .prepare('SELECT * FROM idempotency_keys WHERE ikey = ? AND endpoint = ?')
    .get(key, endpoint) as IdempotencyRecord | undefined;

  if (!row) return null;

  if (row.body_hash !== hash) {
    throwHttp(409, 'CONFLICT', 'Idempotency key reused with different body');
  }

  return { statusCode: row.status_code, body: JSON.parse(row.response) };
}

export function storeIdempotency(
  key: string,
  endpoint: string,
  hash: string,
  statusCode: number,
  body: unknown
): void {
  const db = getDb();
  db.prepare(
    `INSERT OR REPLACE INTO idempotency_keys (ikey, endpoint, body_hash, response, status_code, created_at)
     VALUES (?, ?, ?, ?, ?, ?)`
  ).run(key, endpoint, hash, JSON.stringify(body), statusCode, now());
}

export function readBody(req: http.IncomingMessage): Promise<Buffer> {
  return new Promise((resolve, reject) => {
    const chunks: Buffer[] = [];
    req.on('data', (chunk: Buffer) => chunks.push(chunk));
    req.on('end', () => resolve(Buffer.concat(chunks)));
    req.on('error', reject);
  });
}

export function parseBody<T = unknown>(buf: Buffer): T {
  if (!buf.length) return {} as T;
  try {
    return JSON.parse(buf.toString('utf8')) as T;
  } catch {
    throwHttp(400, 'VALIDATION', 'Request body must be valid JSON');
  }
}
