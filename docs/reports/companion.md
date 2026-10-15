# Companion agent report

Agent: Companion  
Date: 2026-10-15  
Node: v22.19.0  
Build: TypeScript 5 → CommonJS, tsc clean

---

## What was implemented

A TypeScript/Node.js HTTP service implementing the full Pocket Dev companion protocol (docs/PROTOCOL.md v1).

### File layout

```
companion/
├── package.json            # build / start / test scripts
├── tsconfig.json
└── src/
    ├── index.ts            # HTTP server + WebSocket upgrade handler
    ├── db.ts               # node:sqlite schema init, ID/seq helpers
    ├── auth.ts             # pairing.json creation, Bearer token verification
    ├── router.ts           # zero-dependency HTTP router (method + path params)
    ├── handlers/
    │   ├── health.ts       # GET /v1/health, GET /v1/capabilities
    │   ├── pair.ts         # POST /v1/pair
    │   ├── projects.ts     # CRUD /v1/projects
    │   ├── sessions.ts     # CRUD + actions /v1/sessions
    │   ├── events.ts       # GET /v1/events (replay fallback)
    │   ├── files.ts        # full file API: list, content, save, mkdir, rename, move, copy, delete
    │   ├── git.ts          # status, diff, branches, commit, push, pull
    │   ├── processes.ts    # managed dev processes
    │   └── deploy.ts       # recipes + runs
    ├── providers/
    │   ├── detect.ts       # binary detection via --version; PTY availability
    │   └── manager.ts      # process supervisor for sessions + managed processes
    ├── ws/
    │   └── broadcast.ts    # event broadcaster (WebSocket + DB) + PTY client registry
    ├── types/
    │   └── node-pty.d.ts   # optional type declaration for node-pty
    ├── util/
    │   ├── errors.ts       # HttpError + error-code factory
    │   ├── fingerprint.ts  # sha256 fingerprint + binary detection
    │   ├── idempotency.ts  # idempotency key check/store + body reader
    │   └── paths.ts        # path escape + symlink safety
    └── test/
        └── index.test.ts   # node:test suite (27 tests)
```

### Binding / port

Binds `127.0.0.1` only. Port from `POCKET_DEV_PORT` environment variable, defaulting to `8787`.

### Auth

On first start, `~/.pocket-dev/pairing.json` is created with a random three-word setup code and a 32-byte hex token. `POCKET_DEV_TOKEN` overrides the token. Every request except `GET /v1/health` and `POST /v1/pair` requires `Authorization: Bearer <token>`. Credentials are not in the database and are never logged.

### Database

`~/.pocket-dev/pocket.db` via `node:sqlite` (Node 22 built-in). WAL journal mode, foreign keys ON.

Tables: `projects`, `sessions`, `session_events`, `approvals`, `idempotency_keys`, `managed_processes`, `deploy_recipes`, `deploy_runs`.

### Endpoints implemented

All endpoints listed in PROTOCOL.md are present:

| Area | Count |
|------|-------|
| Health + capabilities + pair | 3 |
| Projects (CRUD) | 4 |
| Sessions + sub-actions | 9 |
| Events (HTTP replay) | 1 |
| Files (list, content, save, mkdir, rename, move, copy, delete) | 8 |
| Git (status, diff, branches, commit, push, pull) | 6 |
| Processes | 3 |
| Deploy (recipes + runs) | 4 |
| **Total** | **38** |

### WebSocket

- `GET /v1/events/stream?after=N` — broadcasts all new events to connected clients; replays missed events on connect.
- `GET /v1/pty/:sessionId` — bidirectional PTY channel. Client sends `{ type:"input", data }` and `{ type:"resize", cols, rows }`. Server sends `{ type:"output", data }`.

### File APIs

- `GET /v1/files` — directory listing with `name`, `type`, `size`, `symlink`, `symlinkTarget`.
- `GET /v1/files/content` — returns `{ path, text, fingerprint, binary, truncated }`. `fingerprint` is `sha256:<hex>`. Files over 1 MB or containing null bytes return `binary:true` with no text.
- `PUT /v1/files/content` — version-checked atomic save via temp file + `rename`. If disk fingerprint differs from `expectedFingerprint`, responds `409 REMOTE_CHANGED` with `details.fingerprint` and `details.text` (if readable). Does not write.
- Path safety: every path is resolved against the project root. `..` traversal, absolute paths that escape the root, and symlinks whose real path leaves the root are rejected with `PATH_ESCAPE`.
- Symlink resolution uses `fs.realpathSync` on both the target and the project root (handles macOS `/tmp` → `/private/tmp`).

### Git

- Never runs `git add -A`. Commit stages only the caller-supplied `paths`.
- Never changes `git config`.
- `git stderr` is surfaced in all responses.
- Diff supports `base: worktree | index | <commit>`.
- Push and pull are separate explicit endpoints.

### Provider detection

```
$ node dist/index.js → capabilities:
  claude  : installed=true  version="2.1.168 (Claude Code)"
  agent   : installed=true  version="3.23.23" (cursor CLI)
  codex   : installed=true  version="codex-cli 0.136.0"
  terminal: installed=true  version="bash"
  pty     : false   (node-pty not built)
  git     : true
```

Detection runs `which <binary>` then `<binary> --version`. Versions are returned in capabilities. If a binary is missing, `installed:false` and session start returns `UNSUPPORTED`.

### Process management

**PTY**: tries `require('node-pty')` at session start. If available, spawns a PTY process and streams output. If unavailable (see Limitations), falls back to `child_process.spawn` with `stdio:'pipe'` and `detached:true`.

**Detached spawn fallback**: the child is created with `detached:true` and `unref()`-ed. A client disconnect does not send `SIGHUP` to the child — the process continues on the VPS independently. The process is tracked by PID in the DB and in a runtime Map. It stops only when `/stop`, `/close`, or `/interrupt` is called, or when it exits.

**Managed processes** (dev servers) also use `detached:true` with `shell:true`.

### Idempotency

Mutations that accept `Idempotency-Key`: `POST /v1/projects`, `POST /v1/sessions`, `POST /v1/sessions/:id/prompt`, `POST /v1/git/commit`, `POST /v1/deploy/runs`. A reused key with the same body hash replays the stored response. A reused key with a different body returns `409 CONFLICT`.

### Providers

- `terminal`: spawns `bash --login` in the project workspace.
- `claude`: writes prompts to stdin. Does not auto-approve permissions.
- `agent` (Cursor CLI): same — stdin prompt, no auto-approve.
- `codex`: same.
- No ACP, cookie, or private API usage. The terminal transcript is the event stream; structured approval cards are empty (as permitted by the protocol when reliable prompt detection is not available).

---

## Commands run

```sh
cd companion

# Install
npm install

# Build (first attempt — SQLite type errors)
npm run build

# Fix: sed replace `as TypeName` → `as unknown as TypeName` across handlers
# Fix: add src/types/node-pty.d.ts
# Fix: tsconfig typeRoots

# Build (clean)
npm run build

# Test (first run — 1 fail: macOS /tmp symlink)
npm test

# Fix: realpathSync on projectRoot + tmpDir in test
npm test   # → 27 pass, 0 fail

# Smoke tests (ad-hoc curl, see below)
POCKET_DEV_PORT=18787 node --experimental-sqlite dist/index.js &
curl http://127.0.0.1:18787/v1/health
# → {"ok":true,"version":"1"}
curl http://127.0.0.1:18787/v1/projects
# → 401 UNAUTHORIZED (no token)
curl -H "Authorization: Bearer <token>" http://127.0.0.1:18787/v1/projects
# → []
curl -H "Authorization: Bearer <token>" http://127.0.0.1:18787/v1/capabilities
# → {providers:{claude,agent,codex,terminal},pty:false,git:true}
POST /v1/pair with setupCode → token returned
POST /v1/projects → project created, stored in DB
GET /v1/git/status → git error surfaced in stderr field (dir not a repo)
GET /v1/files?projectId=&path=. → 4 entries listed
```

---

## Test results

```
# tests 27
# suites 4
# pass 27
# fail 0
# cancelled 0
# skipped 0
# todo 0
# duration_ms ~42ms
```

Suites:
1. **Path safety checks** (7) — valid path, `..` traversal (direct + embedded), absolute escape, symlink-outside-root rejection, symlink-inside-root allowed, non-existent path allowed.
2. **Fingerprint conflict detection** (5) — fingerprint changes on edit, stable for same content, 409 REMOTE_CHANGED simulation (no overwrite), binary detection (null bytes), text detection.
3. **Idempotent prompt / commit simulation** (4) — same key+body hash equals, different bodies differ, replay returns cached result (handler called once), conflict on key reuse with different body.
4. **Session state transitions** (11) — valid transitions: queued→starting, starting→running, running→stopped/completed/failed/awaiting_input, awaiting_input→running; invalid terminal states; state label coverage.

---

## Limitations

### PTY (node-pty not built)

`node-pty` is a native add-on that requires a C++ build environment. It was not installed on this machine, so `capabilities.pty` is `false`. The fallback is `child_process.spawn` with `stdio:'pipe'` and `detached:true`.

**Impact**: in the fallback mode, the PTY WebSocket still works (stdout/stderr piped through), but there is no true pseudo-terminal. Cursor-positioning escape sequences, interactive readline, and programs that require a TTY (e.g. interactive password prompts) will not behave correctly. `claude` and `codex` CLIs work in pipe mode for prompt/response, but the in-terminal UI rendering will be raw escape codes rather than a formatted terminal.

**To enable PTY**: `npm install node-pty` on the VPS (requires `python`, `make`, and a C++ compiler). The companion detects its presence at session-start time without a restart.

### Provider resume

`POST /v1/sessions/:id/resume` restarts the process if it is not running. True provider-native session resume (e.g. `claude --resume <session-id>`) is not yet wired up because the resume flag syntax is provider-specific and would need the saved `providerSessionId` to be passed as a CLI argument. The placeholder is in place and can be extended.

### Approval cards

The companion does not parse terminal output to detect when a provider is waiting for a permission prompt. The `approvals` list is populated only through the `POST /v1/sessions/:id/permission` endpoint if the client explicitly creates approval records. A future enhancement would parse known patterns (e.g. the `claude` CLI's `Allow this action? (y/n)` prompt) and emit `approval_request` events. The terminal remains available for manual response in the interim.

### No physical phone or Google login tested

All validation was performed locally using `curl` and the `node:test` suite. No physical Android device, SSH tunnel, or Google Cloud IAP connection was tested or claimed.

### Worktree isolation

Sessions with `workspace:worktree` create a subdirectory at `<projectRoot>/.worktrees/<sessionId>`. Full `git worktree add` integration (creating a proper Git worktree branch) is not wired up — the directory is plain. Implementing that would require the session to know the current branch and run `git worktree add`.

### Binary file upload

Binary file upload is not implemented. The file API handles text reads/writes. Large or binary files return `binary:true` with no text and require out-of-band transfer (SFTP). This matches the stated scope.

### Deploy health checks

`healthCheckUrl` is stored in the recipe but the post-deploy health check (HTTP poll) is not executed. The field is reserved for a future enhancement.
