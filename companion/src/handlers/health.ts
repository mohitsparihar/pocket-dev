import { register, send } from '../router.js';
import { verifyToken } from '../auth.js';
import { getCapabilities } from '../providers/detect.js';
import { currentSeq } from '../db.js';

export function registerHealthRoutes(): void {
  register('GET', '/v1/health', (_req, res) => {
    // latestSeq lets a fresh client start the live stream without
    // downloading every event ever recorded.
    send(res, 200, { ok: true, version: '1', latestSeq: currentSeq() });
  });

  register('GET', '/v1/capabilities', (req, res) => {
    verifyToken(req);
    const caps = getCapabilities();
    send(res, 200, caps);
  });
}
