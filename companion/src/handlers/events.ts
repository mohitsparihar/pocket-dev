import { register, send } from '../router.js';
import { verifyToken } from '../auth.js';
import { getEventsAfter } from '../ws/broadcast.js';

export function registerEventRoutes(): void {
  register('GET', '/v1/events', (req, res, _p, query) => {
    verifyToken(req);
    const after = parseInt(query.after ?? '0', 10);
    const events = getEventsAfter(isNaN(after) ? 0 : after);
    send(res, 200, events);
  });
}
