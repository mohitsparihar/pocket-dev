import * as crypto from 'node:crypto';
import * as fs from 'node:fs';

export const MAX_TEXT_SIZE = 1_000_000;

export function fingerprintBuffer(buf: Buffer): string {
  return 'sha256:' + crypto.createHash('sha256').update(buf).digest('hex');
}

export function fingerprintFile(filePath: string): string {
  const buf = fs.readFileSync(filePath);
  return fingerprintBuffer(buf);
}

export function isBinaryBuffer(buf: Buffer): boolean {
  const check = buf.subarray(0, Math.min(buf.length, 8000));
  for (let i = 0; i < check.length; i++) {
    const byte = check[i];
    if (byte === 0) return true;
    if (byte < 8) return true;
    if (byte >= 14 && byte < 32 && byte !== 27) return true;
  }
  return false;
}
