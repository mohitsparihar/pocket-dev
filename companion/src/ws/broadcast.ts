import { WebSocket } from 'ws';
import { getDb, newId, nextSeq, now } from '../db.js';

export interface SessionEvent {
  id: string;
  sessionId: string;
  seq: number;
  type: string;
  payload: Record<string, unknown>;
  at: number;
}

// Set of clients listening to the event stream
const eventClients = new Set<WebSocket>();

const alive = new WeakMap<WebSocket, boolean>();

export function addEventClient(ws: WebSocket): void {
  eventClients.add(ws);
  alive.set(ws, true);
  ws.on('pong', () => alive.set(ws, true));
  ws.on('close', () => eventClients.delete(ws));
  ws.on('error', () => eventClients.delete(ws));
}

// Phones drop off networks without closing sockets. Ping every client and
// terminate the ones that did not answer the previous ping.
export function startHeartbeat(intervalMs = 25_000): NodeJS.Timeout {
  const timer = setInterval(() => {
    for (const ws of eventClients) {
      if (alive.get(ws) === false) {
        eventClients.delete(ws);
        ws.terminate();
        continue;
      }
      alive.set(ws, false);
      try { ws.ping(); } catch { eventClients.delete(ws); }
    }
  }, intervalMs);
  timer.unref();
  return timer;
}

function sendToEventClients(frame: string): void {
  for (const client of eventClients) {
    if (client.readyState === WebSocket.OPEN) {
      try {
        client.send(frame);
      } catch {
        eventClients.delete(client);
      }
    }
  }
}

// Live text deltas are only useful while they arrive, so they are coalesced
// and broadcast with seq 0 instead of being stored. The final assistant
// message is stored as a normal event.
const pendingDeltas = new Map<string, string>();
let deltaTimer: NodeJS.Timeout | null = null;

export function emitDelta(sessionId: string, text: string): void {
  pendingDeltas.set(sessionId, (pendingDeltas.get(sessionId) ?? '') + text);
  if (deltaTimer) return;
  deltaTimer = setTimeout(flushDeltas, 120);
}

export function flushDeltas(): void {
  if (deltaTimer) clearTimeout(deltaTimer);
  deltaTimer = null;
  const at = now();
  for (const [sessionId, text] of pendingDeltas) {
    sendToEventClients(JSON.stringify({ seq: 0, sessionId, type: 'delta', payload: { text }, at }));
  }
  pendingDeltas.clear();
}

export function emitEvent(
  sessionId: string,
  type: string,
  payload: Record<string, unknown> = {}
): SessionEvent {
  const db = getDb();
  const id = newId();
  const seq = nextSeq();
  const at = now();

  db.prepare(
    `INSERT INTO session_events (id, session_id, seq, type, payload, at)
     VALUES (?, ?, ?, ?, ?, ?)`
  ).run(id, sessionId, seq, type, JSON.stringify(payload), at);

  // Deltas must reach the phone before the stored event that replaces them.
  if (pendingDeltas.has(sessionId)) flushDeltas();

  const event: SessionEvent = { id, sessionId, seq, type, payload, at };
  sendToEventClients(JSON.stringify({ seq, sessionId, type, payload, at }));

  return event;
}

export function getEventsAfter(after: number, limit = 2000): SessionEvent[] {
  const db = getDb();
  const rows = db
    .prepare(
      `SELECT id, session_id, seq, type, payload, at
       FROM session_events WHERE seq > ? ORDER BY seq ASC LIMIT ?`
    )
    .all(after, limit) as Array<{
    id: string;
    session_id: string;
    seq: number;
    type: string;
    payload: string;
    at: number;
  }>;

  return rows.map((r) => ({
    id: r.id,
    sessionId: r.session_id,
    seq: r.seq,
    type: r.type,
    payload: JSON.parse(r.payload) as Record<string, unknown>,
    at: r.at,
  }));
}

// Per-session PTY WebSocket registry
const ptyClients = new Map<string, Set<WebSocket>>();

export function addPtyClient(sessionId: string, ws: WebSocket): void {
  if (!ptyClients.has(sessionId)) ptyClients.set(sessionId, new Set());
  ptyClients.get(sessionId)!.add(ws);
  ws.on('close', () => ptyClients.get(sessionId)?.delete(ws));
  ws.on('error', () => ptyClients.get(sessionId)?.delete(ws));
}

export function broadcastPtyOutput(sessionId: string, data: string): void {
  const clients = ptyClients.get(sessionId);
  if (!clients) return;
  const frame = JSON.stringify({ type: 'output', data });
  for (const client of clients) {
    if (client.readyState === WebSocket.OPEN) {
      try {
        client.send(frame);
      } catch {
        clients.delete(client);
      }
    }
  }
}
