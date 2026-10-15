import { register, send } from '../router.js';
import { getPairingData } from '../auth.js';
import { readBody, parseBody } from '../util/idempotency.js';
import { throwHttp } from '../util/errors.js';

export function registerPairRoutes(): void {
  register('POST', '/v1/pair', async (req, res) => {
    const buf = await readBody(req);
    const body = parseBody<{ setupCode: string }>(buf);
    if (!body.setupCode) throwHttp(400, 'VALIDATION', 'setupCode is required');

    const pairing = getPairingData();
    if (body.setupCode !== pairing.setupCode) {
      throwHttp(401, 'UNAUTHORIZED', 'Invalid setup code');
    }

    send(res, 200, { token: pairing.token });
  });
}
