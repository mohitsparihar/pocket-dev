import * as fs from 'node:fs';
import * as path from 'node:path';
import * as crypto from 'node:crypto';
import * as http from 'node:http';
import { POCKET_DIR } from './db.js';
import { throwHttp } from './util/errors.js';

const PAIRING_FILE = path.join(POCKET_DIR, 'pairing.json');

interface PairingData {
  setupCode: string;
  token: string;
}

let _pairing: PairingData | null = null;

function wordList(): string[] {
  // Minimal word list for a readable 3-word code
  return [
    'apple', 'brave', 'cedar', 'delta', 'ember', 'frost', 'globe', 'honey',
    'ivory', 'jade', 'kite', 'lemon', 'maple', 'noble', 'ocean', 'pearl',
    'queen', 'river', 'stone', 'tiger', 'ultra', 'vigor', 'waltz', 'xenon',
    'yacht', 'zebra', 'alpha', 'bravo', 'cloud', 'dawn', 'eagle', 'flame',
    'grant', 'haven', 'image', 'joint', 'karma', 'lunar', 'metro', 'north',
  ];
}

function randomWord(): string {
  const list = wordList();
  const idx = crypto.randomInt(list.length);
  return list[idx];
}

export function loadOrCreatePairing(): PairingData {
  if (_pairing) return _pairing;

  if (fs.existsSync(PAIRING_FILE)) {
    try {
      const raw = fs.readFileSync(PAIRING_FILE, 'utf8');
      _pairing = JSON.parse(raw) as PairingData;
    } catch {
      // Fall through to create new
    }
  }

  if (!_pairing) {
    const setupCode = `${randomWord()}-${randomWord()}-${randomWord()}`;
    const token = process.env.POCKET_DEV_TOKEN ?? crypto.randomBytes(32).toString('hex');
    _pairing = { setupCode, token };
    fs.mkdirSync(POCKET_DIR, { recursive: true });
    fs.writeFileSync(PAIRING_FILE, JSON.stringify(_pairing, null, 2));
    console.log(`\nPocket Dev pairing code: ${setupCode}`);
    console.log(`Token written to: ${PAIRING_FILE}\n`);
  }

  return _pairing;
}

export function getToken(): string {
  return loadOrCreatePairing().token;
}

export function verifyToken(req: http.IncomingMessage): void {
  const auth = req.headers['authorization'] ?? '';
  const token = auth.startsWith('Bearer ') ? auth.slice(7).trim() : '';
  if (!token || token !== getToken()) {
    throwHttp(401, 'UNAUTHORIZED', 'Invalid or missing token');
  }
}

export function getPairingData(): PairingData {
  return loadOrCreatePairing();
}
