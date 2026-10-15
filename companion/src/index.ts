import * as http from 'node:http';
import { WebSocketServer, WebSocket } from 'ws';
import { initDb } from './db.js';
import { loadOrCreatePairing, verifyToken } from './auth.js';
import { dispatch } from './router.js';
import { registerHealthRoutes } from './handlers/health.js';
import { registerPairRoutes } from './handlers/pair.js';
import { registerProjectRoutes } from './handlers/projects.js';
import { registerSessionRoutes } from './handlers/sessions.js';
import { registerEventRoutes } from './handlers/events.js';
import { registerFileRoutes } from './handlers/files.js';
import { registerGitRoutes } from './handlers/git.js';
import { registerProcessRoutes } from './handlers/processes.js';
import { registerDeployRoutes } from './handlers/deploy.js';
import { addEventClient, getEventsAfter, addPtyClient, emitEvent, startHeartbeat } from './ws/broadcast.js';
import { getDb, now } from './db.js';
import { getSessionProcess, resizeSession, sendPrompt } from './providers/manager.js';
import { send as sendJson } from './router.js';
import { apiError } from './util/errors.js';

const PORT = parseInt(process.env.POCKET_DEV_PORT ?? '8787', 10);
const HOST = '127.0.0.1';

// Sessions the previous companion process was running lost their process
// when it exited. Mark them so the phone shows the truth; the next prompt
// restarts the provider with --resume.
function reconcileSessions(): void {
  const db = getDb();
  const stale = db
    .prepare(`SELECT id FROM sessions WHERE state IN ('running', 'starting')`)
    .all() as Array<{ id: string }>;
  for (const { id } of stale) {
    db.prepare(`UPDATE sessions SET state = 'stopped', process_pid = NULL, updated_at = ? WHERE id = ?`).run(now(), id);
    emitEvent(id, 'status', { text: 'The companion restarted. Send a message to continue this chat.' });
    emitEvent(id, 'state_change', { state: 'stopped' });
  }
}

function main() {
  process.on('uncaughtException', (err) => console.error('Uncaught exception:', err));
  process.on('unhandledRejection', (err) => console.error('Unhandled rejection:', err));

  // Init database
  initDb();
  reconcileSessions();

  // Load or create pairing credentials
  loadOrCreatePairing();

  // Register all routes
  registerHealthRoutes();
  registerPairRoutes();
  registerProjectRoutes();
  registerSessionRoutes();
  registerEventRoutes();
  registerFileRoutes();
  registerGitRoutes();
  registerProcessRoutes();
  registerDeployRoutes();

  // Create HTTP server
  const server = http.createServer(async (req, res) => {
    // CORS headers for emulator access
    res.setHeader('Access-Control-Allow-Origin', '*');
    res.setHeader('Access-Control-Allow-Methods', 'GET,POST,PUT,PATCH,DELETE,OPTIONS');
    res.setHeader('Access-Control-Allow-Headers', 'Content-Type,Authorization,Idempotency-Key');

    if (req.method === 'OPTIONS') {
      res.writeHead(204);
      res.end();
      return;
    }

    await dispatch(req, res);
  });

  // WebSocket server (shares the HTTP server)
  const wss = new WebSocketServer({ noServer: true });

  server.on('upgrade', (req, socket, head) => {
    const url = req.url ?? '';

    // Event stream: GET /v1/events/stream?after=N
    if (url.startsWith('/v1/events/stream')) {
      // Auth check
      const authHeader = req.headers['authorization'] ?? '';
      const token = authHeader.startsWith('Bearer ') ? authHeader.slice(7).trim() : '';
      const pairing = loadOrCreatePairing();
      if (token !== pairing.token) {
        socket.write('HTTP/1.1 401 Unauthorized\r\n\r\n');
        socket.destroy();
        return;
      }

      wss.handleUpgrade(req, socket, head, (ws) => {
        const params = new URLSearchParams(url.split('?')[1] ?? '');
        const after = parseInt(params.get('after') ?? '0', 10);

        // Register first so nothing emitted during the replay is lost; the
        // phone drops duplicates by seq.
        addEventClient(ws);
        let cursor = isNaN(after) ? 0 : after;
        for (;;) {
          const missed = getEventsAfter(cursor);
          for (const ev of missed) {
            ws.send(JSON.stringify({ seq: ev.seq, sessionId: ev.sessionId, type: ev.type, payload: ev.payload, at: ev.at }));
          }
          if (missed.length === 0) break;
          cursor = missed[missed.length - 1].seq;
        }
      });
      return;
    }

    // PTY stream: GET /v1/pty/:sessionId
    const ptyMatch = /^\/v1\/pty\/([^/?]+)/.exec(url);
    if (ptyMatch) {
      const sessionId = ptyMatch[1];

      const authHeader = req.headers['authorization'] ?? '';
      const token = authHeader.startsWith('Bearer ') ? authHeader.slice(7).trim() : '';
      const pairing = loadOrCreatePairing();
      if (token !== pairing.token) {
        socket.write('HTTP/1.1 401 Unauthorized\r\n\r\n');
        socket.destroy();
        return;
      }

      wss.handleUpgrade(req, socket, head, (ws) => {
        const entry = getSessionProcess(sessionId);
        if (!entry) {
          ws.send(JSON.stringify({ type: 'error', message: 'Session not running' }));
          ws.close();
          return;
        }

        addPtyClient(sessionId, ws);

        ws.on('message', (raw: Buffer | string) => {
          try {
            const msg = JSON.parse(raw.toString('utf8')) as { type: string; data?: string; cols?: number; rows?: number };
            if (msg.type === 'input' && msg.data) {
              if (entry.usePty && entry.pty) {
                // eslint-disable-next-line @typescript-eslint/no-explicit-any
                (entry.pty as any).write(msg.data);
              } else if (entry.process?.stdin) {
                entry.process.stdin.write(msg.data);
              }
            } else if (msg.type === 'resize' && msg.cols && msg.rows) {
              resizeSession(sessionId, msg.cols, msg.rows);
            }
          } catch { /* ignore malformed frames */ }
        });
      });
      return;
    }

    socket.destroy();
  });

  startHeartbeat();

  server.listen(PORT, HOST, () => {
    console.log(`Pocket Dev companion listening on http://${HOST}:${PORT}`);
  });

  server.on('error', (err) => {
    console.error('Server error:', err);
    process.exit(1);
  });
}

main();
