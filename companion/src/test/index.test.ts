import { test, describe, before, after, beforeEach } from 'node:test';
import assert from 'node:assert/strict';
import * as fs from 'node:fs';
import * as path from 'node:path';
import * as os from 'node:os';
import * as crypto from 'node:crypto';

// ─── Utility imports (tested directly, not over HTTP) ───────────────────────

// We test the utility functions directly because they contain the critical
// safety logic that must be correct regardless of HTTP layer.

// Inline the logic under test so we don't need the DB or HTTP server.
// Note: resolve tmpDir to handle /tmp → /private/tmp symlink on macOS.

function fingerprintBuffer(buf: Buffer): string {
  return 'sha256:' + crypto.createHash('sha256').update(buf).digest('hex');
}

function isBinaryBuffer(buf: Buffer): boolean {
  const check = buf.subarray(0, Math.min(buf.length, 8000));
  for (let i = 0; i < check.length; i++) {
    const byte = check[i];
    if (byte === 0) return true;
    if (byte < 8) return true;
  }
  return false;
}

function safePath(projectRoot: string, userPath: string): string {
  const normalized = path.normalize(userPath);
  if (normalized.startsWith('..')) {
    throw Object.assign(new Error('PATH_ESCAPE'), { code: 'PATH_ESCAPE' });
  }
  const resolved = path.resolve(projectRoot, userPath);
  const rootWithSep = projectRoot.endsWith(path.sep) ? projectRoot : projectRoot + path.sep;
  if (resolved !== projectRoot && !resolved.startsWith(rootWithSep)) {
    throw Object.assign(new Error('PATH_ESCAPE'), { code: 'PATH_ESCAPE' });
  }
  try {
    const stat = fs.lstatSync(resolved);
    if (stat.isSymbolicLink()) {
      let real: string;
      try {
        real = fs.realpathSync(resolved);
      } catch {
        throw Object.assign(new Error('PATH_ESCAPE'), { code: 'PATH_ESCAPE' });
      }
      let realRoot: string;
      try { realRoot = fs.realpathSync(projectRoot); } catch { realRoot = projectRoot; }
      const realRootSep = realRoot.endsWith(path.sep) ? realRoot : realRoot + path.sep;
      if (real !== realRoot && !real.startsWith(realRootSep)) {
        throw Object.assign(new Error('PATH_ESCAPE'), { code: 'PATH_ESCAPE' });
      }
    }
  } catch (e: unknown) {
    if (e instanceof Error && 'code' in e) {
      if ((e as NodeJS.ErrnoException).code === 'ENOENT') return resolved;
      throw e;
    }
    throw e;
  }
  return resolved;
}

// ─── Tests ───────────────────────────────────────────────────────────────────

let tmpDir: string;

before(() => {
  // Resolve to real path so macOS /tmp → /private/tmp doesn't break symlink checks
  tmpDir = fs.realpathSync(fs.mkdtempSync(path.join(os.tmpdir(), 'pocket-test-')));
});

after(() => {
  fs.rmSync(tmpDir, { recursive: true, force: true });
});

describe('Path safety checks', () => {
  test('allows valid path inside root', () => {
    const result = safePath(tmpDir, 'src/main.ts');
    assert.ok(result.startsWith(tmpDir));
    assert.ok(result.includes('main.ts'));
  });

  test('rejects .. traversal', () => {
    assert.throws(
      () => safePath(tmpDir, '../etc/passwd'),
      (err: unknown) => {
        assert.ok(err instanceof Error);
        assert.equal((err as NodeJS.ErrnoException).code, 'PATH_ESCAPE');
        return true;
      }
    );
  });

  test('rejects embedded .. traversal', () => {
    assert.throws(
      () => safePath(tmpDir, 'src/../../etc/passwd'),
      (err: unknown) => {
        assert.ok(err instanceof Error);
        assert.equal((err as NodeJS.ErrnoException).code, 'PATH_ESCAPE');
        return true;
      }
    );
  });

  test('rejects absolute path outside root', () => {
    assert.throws(
      () => safePath(tmpDir, '/etc/passwd'),
      (err: unknown) => {
        assert.ok(err instanceof Error);
        return true;
      }
    );
  });

  test('rejects symlink targeting outside root', () => {
    const link = path.join(tmpDir, 'escape-link');
    fs.symlinkSync('/tmp', link);
    assert.throws(
      () => safePath(tmpDir, 'escape-link'),
      (err: unknown) => {
        assert.ok(err instanceof Error);
        assert.equal((err as NodeJS.ErrnoException).code, 'PATH_ESCAPE');
        return true;
      }
    );
    fs.unlinkSync(link);
  });

  test('allows symlink staying inside root', () => {
    const target = path.join(tmpDir, 'real-file.txt');
    fs.writeFileSync(target, 'hello');
    const link = path.join(tmpDir, 'safe-link');
    fs.symlinkSync(target, link);
    const result = safePath(tmpDir, 'safe-link');
    assert.ok(result.startsWith(tmpDir));
    fs.unlinkSync(link);
    fs.unlinkSync(target);
  });

  test('allows path pointing to non-existent file (for writes)', () => {
    const result = safePath(tmpDir, 'new-file.txt');
    assert.ok(result.startsWith(tmpDir));
  });
});

describe('Fingerprint conflict detection', () => {
  let testFile: string;

  beforeEach(() => {
    testFile = path.join(tmpDir, `fp-test-${Date.now()}.txt`);
  });

  test('fingerprint changes when file content changes', () => {
    fs.writeFileSync(testFile, 'version 1');
    const fp1 = fingerprintBuffer(fs.readFileSync(testFile));

    fs.writeFileSync(testFile, 'version 2');
    const fp2 = fingerprintBuffer(fs.readFileSync(testFile));

    assert.notEqual(fp1, fp2);
    assert.ok(fp1.startsWith('sha256:'));
    assert.ok(fp2.startsWith('sha256:'));
  });

  test('fingerprint is stable for identical content', () => {
    const content = 'stable content';
    const fp1 = fingerprintBuffer(Buffer.from(content));
    const fp2 = fingerprintBuffer(Buffer.from(content));
    assert.equal(fp1, fp2);
  });

  test('simulates 409 REMOTE_CHANGED: client fingerprint mismatches disk', () => {
    fs.writeFileSync(testFile, 'original');
    const clientFp = fingerprintBuffer(fs.readFileSync(testFile));

    // Simulate another writer changing the file
    fs.writeFileSync(testFile, 'changed by another writer');
    const diskFp = fingerprintBuffer(fs.readFileSync(testFile));

    // Client attempts save with stale fingerprint
    const conflicted = clientFp !== diskFp;
    assert.ok(conflicted, 'Should detect conflict');

    // File must NOT be overwritten in a conflict
    const onDisk = fs.readFileSync(testFile, 'utf8');
    assert.equal(onDisk, 'changed by another writer');
  });

  test('binary detection: null bytes flag as binary', () => {
    const bin = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x00, 0x0d]);
    assert.ok(isBinaryBuffer(bin));
  });

  test('text files are not detected as binary', () => {
    const text = Buffer.from('Hello, world!\nThis is plain text.\n');
    assert.ok(!isBinaryBuffer(text));
  });
});

describe('Idempotent prompt / commit simulation', () => {
  // We simulate idempotency without a live HTTP server by testing the key logic

  function bodyHash(body: Buffer): string {
    return crypto.createHash('sha256').update(body).digest('hex');
  }

  test('same key and same body produce same hash', () => {
    const body = Buffer.from(JSON.stringify({ text: 'hello' }));
    const h1 = bodyHash(body);
    const h2 = bodyHash(body);
    assert.equal(h1, h2);
  });

  test('different bodies produce different hashes', () => {
    const b1 = Buffer.from(JSON.stringify({ text: 'hello' }));
    const b2 = Buffer.from(JSON.stringify({ text: 'world' }));
    assert.notEqual(bodyHash(b1), bodyHash(b2));
  });

  test('idempotency store simulation: replay returns cached result', () => {
    const store = new Map<string, { hash: string; result: unknown }>();

    function processRequest(key: string, body: Buffer, handler: () => unknown): { result: unknown; replayed: boolean } {
      const hash = bodyHash(body);
      const existing = store.get(key);
      if (existing) {
        if (existing.hash !== hash) throw new Error('CONFLICT: key reused with different body');
        return { result: existing.result, replayed: true };
      }
      const result = handler();
      store.set(key, { hash, result });
      return { result, replayed: false };
    }

    let callCount = 0;
    const body = Buffer.from(JSON.stringify({ text: 'prompt text' }));
    const key = 'idempotency-key-abc123';

    const r1 = processRequest(key, body, () => { callCount++; return { ok: true, id: 'event-1' }; });
    const r2 = processRequest(key, body, () => { callCount++; return { ok: true, id: 'event-2' }; });

    assert.equal(callCount, 1, 'Handler should only be called once');
    assert.equal(r1.replayed, false);
    assert.equal(r2.replayed, true);
    assert.deepEqual(r1.result, r2.result);
  });

  test('idempotency conflict: same key, different body throws', () => {
    const store = new Map<string, { hash: string; result: unknown }>();

    function processRequest(key: string, body: Buffer, handler: () => unknown): unknown {
      const hash = bodyHash(body);
      const existing = store.get(key);
      if (existing) {
        if (existing.hash !== hash) throw Object.assign(new Error('CONFLICT'), { code: 'CONFLICT' });
        return existing.result;
      }
      const result = handler();
      store.set(key, { hash, result });
      return result;
    }

    const key = 'conflict-key-xyz';
    const body1 = Buffer.from(JSON.stringify({ text: 'prompt A' }));
    const body2 = Buffer.from(JSON.stringify({ text: 'prompt B' }));

    processRequest(key, body1, () => ({ ok: true }));

    assert.throws(
      () => processRequest(key, body2, () => ({ ok: true })),
      (err: unknown) => {
        assert.ok(err instanceof Error);
        assert.equal((err as NodeJS.ErrnoException).code, 'CONFLICT');
        return true;
      }
    );
  });
});

describe('Session state transitions', () => {
  // Test the valid state machine transitions
  type SessionState = 'queued' | 'starting' | 'running' | 'awaiting_input' | 'completed' | 'failed' | 'stopped';

  const VALID_TRANSITIONS: Record<SessionState, SessionState[]> = {
    queued: ['starting', 'failed'],
    starting: ['running', 'failed'],
    running: ['awaiting_input', 'completed', 'failed', 'stopped'],
    awaiting_input: ['running', 'stopped', 'failed'],
    completed: [],
    failed: [],
    stopped: [],
  };

  function canTransition(from: SessionState, to: SessionState): boolean {
    return VALID_TRANSITIONS[from].includes(to);
  }

  test('queued → starting is valid', () => {
    assert.ok(canTransition('queued', 'starting'));
  });

  test('starting → running is valid', () => {
    assert.ok(canTransition('starting', 'running'));
  });

  test('running → stopped is valid (on close)', () => {
    assert.ok(canTransition('running', 'stopped'));
  });

  test('running → completed is valid (process exits 0)', () => {
    assert.ok(canTransition('running', 'completed'));
  });

  test('running → failed is valid (crash)', () => {
    assert.ok(canTransition('running', 'failed'));
  });

  test('completed → running is invalid (terminal state)', () => {
    assert.ok(!canTransition('completed', 'running'));
  });

  test('stopped → running is invalid (terminal state)', () => {
    assert.ok(!canTransition('stopped', 'running'));
  });

  test('failed → starting is invalid (terminal state)', () => {
    assert.ok(!canTransition('failed', 'starting'));
  });

  test('running → awaiting_input is valid', () => {
    assert.ok(canTransition('running', 'awaiting_input'));
  });

  test('awaiting_input → running is valid (after permission)', () => {
    assert.ok(canTransition('awaiting_input', 'running'));
  });

  test('state labels match protocol definition', () => {
    const STATES: SessionState[] = ['queued', 'starting', 'running', 'awaiting_input', 'completed', 'failed', 'stopped'];
    for (const s of STATES) {
      assert.ok(Object.keys(VALID_TRANSITIONS).includes(s), `${s} should be in state machine`);
    }
  });
});

// ─── Provider stream parsing ────────────────────────────────────────────────

import { eventsFromProviderLine } from '../providers/streamEvents.js';
import { titleFromPrompt } from '../handlers/sessions.js';

describe('Provider stream parsing', () => {
  test('text deltas become live delta events', () => {
    const line = JSON.stringify({
      type: 'stream_event',
      event: { type: 'content_block_delta', index: 0, delta: { type: 'text_delta', text: 'Hel' } },
    });
    assert.deepEqual(eventsFromProviderLine(line), [{ type: 'delta', text: 'Hel' }]);
  });

  test('other stream events are ignored', () => {
    const line = JSON.stringify({ type: 'stream_event', event: { type: 'message_start' } });
    assert.deepEqual(eventsFromProviderLine(line), []);
  });

  test('a successful result ends the turn with its duration', () => {
    const line = JSON.stringify({ type: 'result', subtype: 'success', is_error: false, duration_ms: 75_000 });
    assert.deepEqual(eventsFromProviderLine(line), [{ type: 'done', text: 'Done in 1m 15s' }]);
  });

  test('a failed result reports the error and ends the turn', () => {
    const line = JSON.stringify({ type: 'result', subtype: 'error_max_turns', is_error: true, result: 'Too many turns' });
    assert.deepEqual(eventsFromProviderLine(line), [
      { type: 'error', text: 'Too many turns' },
      { type: 'done', text: '' },
    ]);
  });
});

describe('Chat titles', () => {
  test('uses the first line of the prompt', () => {
    assert.equal(titleFromPrompt('Fix the login bug\nIt fails on Android'), 'Fix the login bug');
  });

  test('skips the context file header', () => {
    assert.equal(titleFromPrompt('Use these files as context:\n- a.ts\n\nExplain a.ts'), 'Explain a.ts');
  });

  test('shortens long prompts at a word boundary', () => {
    const title = titleFromPrompt('Refactor the authentication middleware so it handles expired tokens gracefully');
    assert.ok(title.endsWith('…'));
    assert.ok(title.length <= 49);
  });
});

// ─── Slash commands ─────────────────────────────────────────────────────────

import { listCommands, expandCommand, fillArguments, parseFrontmatter } from '../providers/commands.js';

describe('Slash commands', () => {
  let home: string;
  let project: string;
  const saved = { HOME: process.env.HOME, CODEX_HOME: process.env.CODEX_HOME, CLAUDE_CONFIG_DIR: process.env.CLAUDE_CONFIG_DIR };

  before(() => {
    home = fs.mkdtempSync(path.join(os.tmpdir(), 'pocket-home-'));
    project = fs.mkdtempSync(path.join(os.tmpdir(), 'pocket-proj-'));
    process.env.HOME = home;
    process.env.CODEX_HOME = path.join(home, '.codex');
    process.env.CLAUDE_CONFIG_DIR = path.join(home, '.claude');

    fs.mkdirSync(path.join(project, '.cursor', 'commands', 'git'), { recursive: true });
    fs.writeFileSync(path.join(project, '.cursor', 'commands', 'fix-lint.md'), '# Fix lint errors\nRun the linter on $ARGUMENTS and fix every error.');
    fs.writeFileSync(path.join(project, '.cursor', 'commands', 'git', 'pr.md'), 'Open a pull request.');

    fs.mkdirSync(path.join(home, '.codex', 'prompts'), { recursive: true });
    fs.writeFileSync(
      path.join(home, '.codex', 'prompts', 'explain.md'),
      '---\ndescription: Explain a file\nargument-hint: <file>\n---\nExplain $1 in plain words.'
    );

    fs.mkdirSync(path.join(project, '.claude', 'commands'), { recursive: true });
    fs.writeFileSync(path.join(project, '.claude', 'commands', 'ship.md'), '---\ndescription: Ship it\n---\nShip.');
    const pluginDir = path.join(home, 'plugin-install');
    fs.mkdirSync(path.join(pluginDir, 'commands'), { recursive: true });
    fs.writeFileSync(path.join(pluginDir, 'commands', 'review.md'), '---\ndescription: Plugin review\n---\nReview.');
    fs.mkdirSync(path.join(home, '.claude', 'plugins'), { recursive: true });
    fs.writeFileSync(
      path.join(home, '.claude', 'plugins', 'installed_plugins.json'),
      JSON.stringify({ plugins: { 'codex@market': [{ scope: 'user', installPath: pluginDir }] } })
    );
  });

  after(() => {
    for (const [key, value] of Object.entries(saved)) {
      if (value === undefined) delete process.env[key];
      else process.env[key] = value;
    }
  });

  test('frontmatter is split from the body', () => {
    const { meta, body } = parseFrontmatter("---\ndescription: 'Quoted'\nargument-hint: <x>\n---\nBody");
    assert.deepEqual(meta, { description: 'Quoted', 'argument-hint': '<x>' });
    assert.equal(body, 'Body');
  });

  test('Cursor lists project commands, nested ones with a colon', () => {
    const names = listCommands('agent', [project], null).map((c) => `${c.name}=${c.description}`);
    assert.deepEqual(names.sort(), ['fix-lint=Fix lint errors', 'git:pr=Open a pull request.']);
  });

  test('Codex lists review and custom prompts', () => {
    const commands = listCommands('codex', [project], null);
    assert.deepEqual(commands.map((c) => c.name), ['review', 'prompts:explain']);
    assert.equal(commands[1].argumentHint, '<file>');
  });

  test('Claude uses its reported list and describes known commands', () => {
    const commands = listCommands('claude', [project], ['compact', 'ship', 'codex:review', 'mystery']);
    assert.deepEqual(
      commands.map((c) => [c.name, c.source, c.description]),
      [
        ['compact', 'built-in', 'Summarize the conversation to free up context'],
        ['ship', 'project', 'Ship it'],
        ['codex:review', 'plugin', 'Plugin review'],
        ['mystery', 'skill', ''],
      ]
    );
  });

  test('Claude without a reported list falls back to built-ins and files', () => {
    const names = listCommands('claude', [project], null).map((c) => c.name);
    assert.ok(names.includes('compact'));
    assert.ok(names.includes('ship'));
    assert.ok(names.includes('codex:review'));
  });

  test('custom commands expand with their arguments', () => {
    assert.deepEqual(expandCommand('agent', '/fix-lint src/app.ts', [project]), {
      prompt: '# Fix lint errors\nRun the linter on src/app.ts and fix every error.',
    });
    assert.deepEqual(expandCommand('codex', '/prompts:explain main.ts', [project]), { prompt: 'Explain main.ts in plain words.' });
    assert.equal(expandCommand('agent', '/unknown', [project]), null);
    assert.equal(expandCommand('claude', '/ship', [project]), null);
  });

  test('codex /review becomes a review run', () => {
    assert.deepEqual(expandCommand('codex', '/review', [project])?.review, ['--uncommitted']);
    assert.deepEqual(expandCommand('codex', '/review --base main focus on auth', [project])?.review, [
      '--base',
      'main',
      'focus on auth',
    ]);
  });

  test('arguments are appended when the body has no placeholder', () => {
    assert.equal(fillArguments('Do the thing.', 'quickly'), 'Do the thing.\n\nquickly');
    assert.equal(fillArguments('Do the thing.', ''), 'Do the thing.');
  });
});

describe('Status details', () => {
  test('init message carries the environment for /status', () => {
    const line = JSON.stringify({
      type: 'system',
      subtype: 'init',
      session_id: 's1',
      model: 'claude-opus-5-5',
      claude_code_version: '2.1.168',
      cwd: '/srv/app',
      permissionMode: 'acceptEdits',
      output_style: 'default',
      mcp_servers: [{ name: 'playwright', status: 'connected' }],
      plugins: [{ name: 'codex', path: '/p' }],
      tools: ['Read', 'Edit'],
      skills: ['a'],
      agents: [],
      slash_commands: ['compact'],
    });
    const [event] = eventsFromProviderLine(line);
    assert.deepEqual(event.info, {
      version: '2.1.168',
      cwd: '/srv/app',
      permissionMode: 'acceptEdits',
      outputStyle: 'default',
      mcpServers: [{ name: 'playwright', status: 'connected' }],
      plugins: ['codex'],
      tools: 2,
      skills: 1,
      agents: 0,
    });
  });

  test('result reports cost, tokens and context use', () => {
    const line = JSON.stringify({
      type: 'result',
      subtype: 'success',
      is_error: false,
      duration_ms: 1695,
      total_cost_usd: 0.0319,
      usage: {
        input_tokens: 10,
        cache_creation_input_tokens: 25337,
        cache_read_input_tokens: 0,
        output_tokens: 50,
        iterations: [{ input_tokens: 10, output_tokens: 50, cache_read_input_tokens: 0, cache_creation_input_tokens: 25337 }],
      },
      modelUsage: { 'claude-haiku': { contextWindow: 200000 } },
    });
    const [event] = eventsFromProviderLine(line);
    assert.deepEqual(event.usage, {
      processCostUsd: 0.0319,
      inputTokens: 25347,
      outputTokens: 50,
      contextTokens: 25397,
      contextWindow: 200000,
    });
  });

  test('codex turn usage is captured', () => {
    const [event] = eventsFromProviderLine(JSON.stringify({ type: 'turn.completed', usage: { input_tokens: 900, output_tokens: 40 } }));
    assert.deepEqual(event.usage, { inputTokens: 900, outputTokens: 40 });
  });
});

// ─── Real path guard, root guard, orphaned agents ───────────────────────────

import { safePath as realSafePath, assertNotRoot } from '../util/paths.js';
import { looksLikeOurAgent, stopOrphan } from '../providers/orphans.js';
import { spawn } from 'node:child_process';

describe('Project path guard', () => {
  let root: string;
  let outside: string;

  before(() => {
    root = fs.realpathSync(fs.mkdtempSync(path.join(os.tmpdir(), 'pocket-root-')));
    outside = fs.realpathSync(fs.mkdtempSync(path.join(os.tmpdir(), 'pocket-outside-')));
    fs.writeFileSync(path.join(outside, 'secret.txt'), 'x');
    fs.mkdirSync(path.join(root, 'src'));
    fs.symlinkSync(outside, path.join(root, 'link'));
    fs.symlinkSync(path.join(root, 'src'), path.join(root, 'inner-link'));
  });

  const code = (fn: () => unknown) => {
    try {
      fn();
      return 'ok';
    } catch (e) {
      return (e as { body?: { error?: { code?: string } } }).body?.error?.code ?? String(e);
    }
  };

  test('a symlinked parent folder cannot lead outside the project', () => {
    assert.equal(code(() => realSafePath(root, 'link/secret.txt')), 'PATH_ESCAPE');
    assert.equal(code(() => realSafePath(root, 'link/new-file.txt')), 'PATH_ESCAPE');
    assert.equal(code(() => realSafePath(root, 'link')), 'PATH_ESCAPE');
  });

  test('normal paths and links inside the project still work', () => {
    assert.equal(code(() => realSafePath(root, 'src/new/file.ts')), 'ok');
    assert.equal(code(() => realSafePath(root, 'inner-link/file.ts')), 'ok');
    assert.equal(code(() => realSafePath(root, '../escape')), 'PATH_ESCAPE');
  });

  test('the project folder itself cannot be deleted, moved or renamed', () => {
    assert.equal(code(() => assertNotRoot(root, realSafePath(root, '.'))), 'VALIDATION');
    assert.equal(code(() => assertNotRoot(root, realSafePath(root, 'src/..'))), 'VALIDATION');
    assert.equal(code(() => assertNotRoot(root, realSafePath(root, 'src'))), 'ok');
  });
});

describe('Orphaned agents', () => {
  test('only our own detached agents match', () => {
    assert.ok(looksLikeOurAgent('4242 claude -p --input-format stream-json --verbose', 4242));
    assert.ok(looksLikeOurAgent('4242 codex exec --json --color never', 4242));
    // Same flags, but not a group leader: not ours.
    assert.ok(!looksLikeOurAgent('100 claude -p --output-format stream-json', 4242));
    // A group leader without our flags, e.g. a reused pid.
    assert.ok(!looksLikeOurAgent('4242 /usr/bin/postgres -D /var/lib', 4242));
  });

  const sleeper = (marker: string) => {
    const child = spawn(process.execPath, ['-e', 'setInterval(() => {}, 1000)', '--', marker], {
      detached: true,
      stdio: 'ignore',
    });
    child.unref();
    return child.pid!;
  };
  const alive = (pid: number) => {
    try {
      process.kill(pid, 0);
      return true;
    } catch {
      return false;
    }
  };

  test('a leftover agent is stopped and an unrelated process is not', async () => {
    const ours = sleeper('--output-format=stream-json');
    const theirs = sleeper('--something-else');
    await new Promise((r) => setTimeout(r, 300));
    try {
      assert.equal(stopOrphan(ours), true);
      assert.equal(stopOrphan(theirs), false);
      await new Promise((r) => setTimeout(r, 500));
      assert.equal(alive(ours), false);
      assert.equal(alive(theirs), true);
    } finally {
      for (const pid of [ours, theirs]) {
        try { process.kill(pid, 'SIGKILL'); } catch { /* gone */ }
      }
    }
  });
});

describe('Orphan ownership', () => {
  test('a recorded start time that does not match is never stopped', async () => {
    const child = spawn(process.execPath, ['-e', 'setInterval(() => {}, 1000)', '--', '--output-format=stream-json'], {
      detached: true,
      stdio: 'ignore',
    });
    child.unref();
    const pid = child.pid!;
    await new Promise((r) => setTimeout(r, 300));
    try {
      const { processStart } = await import('../providers/orphans.js');
      const started = processStart(pid);
      assert.ok(started);
      // Same pid, but a different process from the one we recorded.
      assert.equal(stopOrphan(pid, 'Thu Jan  1 00:00:00 1970'), false);
      assert.equal(stopOrphan(pid, started), true);
    } finally {
      try { process.kill(pid, 'SIGKILL'); } catch { /* gone */ }
    }
  });
});

import { parseStatus } from '../handlers/git.js';

describe('Git status for the phone', () => {
  test('branch, ahead/behind and one entry per path', () => {
    const out = [
      '## main...origin/main [ahead 2, behind 1]',
      'MM src/app.ts', // staged and modified again: one row, not two
      'A  new.ts',
      ' D old.ts',
      'R  renamed.ts',
      'was-named.ts',
      '?? notes with space.md',
      'UU conflict.ts',
      '',
    ].join('\0');
    assert.deepEqual(parseStatus(out), {
      branch: 'main',
      ahead: 2,
      behind: 1,
      files: [
        { path: 'src/app.ts', status: 'modified', staged: true },
        { path: 'new.ts', status: 'added', staged: true },
        { path: 'old.ts', status: 'deleted', staged: false },
        { path: 'renamed.ts', status: 'renamed', staged: true },
        { path: 'notes with space.md', status: 'untracked', staged: false },
        { path: 'conflict.ts', status: 'conflict', staged: true },
      ],
    });
  });

  test('a repository without commits', () => {
    assert.deepEqual(parseStatus('## No commits yet on main\0?? a.txt\0'), {
      branch: 'main',
      ahead: 0,
      behind: 0,
      files: [{ path: 'a.txt', status: 'untracked', staged: false }],
    });
  });
});
