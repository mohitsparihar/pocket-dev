# Pocket Dev companion protocol v1

Bind `127.0.0.1` only. Default port `8787` (`POCKET_DEV_PORT`). Never expose this port on a public interface.

Android reaches it through an SSH local forward to the VM loopback, or directly at `http://10.0.2.2:8787` from the emulator during development.

## Auth

On first start, create `~/.pocket-dev/pairing.json`:

```json
{ "setupCode": "word-word-word", "token": "<random 32 bytes hex>" }
```

`POCKET_DEV_TOKEN` overrides the token. Every request except `GET /v1/health` and `POST /v1/pair` requires `Authorization: Bearer <token>`.

`POST /v1/pair` body `{ "setupCode": "..." }` returns `{ "token": "..." }`.

## Errors

```json
{ "error": { "code": "REMOTE_CHANGED", "message": "human readable", "recoverable": true, "details": {} } }
```

Codes: `UNAUTHORIZED`, `NOT_FOUND`, `REMOTE_CHANGED`, `CONFLICT`, `PATH_ESCAPE`, `PERMISSION`, `VALIDATION`, `BUSY`, `UNSUPPORTED`, `IDEMPOTENT_REPLAY`.

## Idempotency

`POST` mutations accept `Idempotency-Key`. Replaying the same key and same body returns the original result and does not repeat the side effect. A reused key with a different body is `CONFLICT`.

## Endpoints

| Method | Path | Purpose |
| --- | --- | --- |
| GET | `/v1/health` | `{ ok, version: "1" }` unauthenticated |
| GET | `/v1/capabilities` | providers, pty, git |
| POST | `/v1/pair` | exchange setup code |
| GET | `/v1/projects` | list |
| POST | `/v1/projects` | create |
| GET | `/v1/projects/:id` | one |
| PATCH | `/v1/projects/:id` | update |
| GET | `/v1/sessions?projectId=` | list |
| POST | `/v1/sessions` | start |
| GET | `/v1/sessions/:id` | one, including events and approvals |
| POST | `/v1/sessions/:id/prompt` | `{ text }` |
| POST | `/v1/sessions/:id/interrupt` | stop current turn |
| POST | `/v1/sessions/:id/permission` | `{ approvalId, allow }` |
| POST | `/v1/sessions/:id/resume` | resume provider session if supported |
| POST | `/v1/sessions/:id/close` | close process, keep history |
| PATCH | `/v1/sessions/:id` | `{ title?, archived? }` |
| GET | `/v1/events?after=` | events with `seq` greater than cursor |
| GET | `/v1/files?projectId=&path=` | directory listing |
| GET | `/v1/files/content?projectId=&path=` | text plus fingerprint |
| PUT | `/v1/files/content` | version-checked save |
| POST | `/v1/files/mkdir` | `{ projectId, path }` |
| POST | `/v1/files/rename` | `{ projectId, path, newName }` |
| POST | `/v1/files/move` | `{ projectId, path, destination }` |
| POST | `/v1/files/copy` | `{ projectId, path, destination }` |
| POST | `/v1/files/delete` | `{ projectId, paths }` |
| GET | `/v1/git/status?projectId=` | porcelain status |
| GET | `/v1/git/diff?projectId=&path=&base=` | unified diff, `base` is `worktree`, `index`, or a commit |
| GET | `/v1/git/branches?projectId=` | branches |
| POST | `/v1/git/commit` | `{ projectId, paths, message }` staged paths only |
| POST | `/v1/git/push` | `{ projectId }` |
| POST | `/v1/git/pull` | `{ projectId }` |
| GET | `/v1/processes?projectId=` | managed dev processes |
| POST | `/v1/processes` | `{ projectId, label, command }` |
| POST | `/v1/processes/:id/stop` | stop |
| GET | `/v1/deploy/recipes?projectId=` | recipes |
| POST | `/v1/deploy/recipes` | create |
| POST | `/v1/deploy/runs` | `{ projectId, recipeId }` explicit run |
| GET | `/v1/deploy/runs/:id` | run with logs |

WebSocket `GET /v1/events/stream?after=` sends `{ "seq", "sessionId", "type", "payload", "at" }` as text frames. HTTP `GET /v1/events` is the replay fallback.

WebSocket `/v1/pty/:sessionId` carries terminal bytes. Client text frames: `{ "type": "input", "data": "<utf8>" }` and `{ "type": "resize", "cols": 80, "rows": 24 }`. Server text frames: `{ "type": "output", "data": "<utf8>" }`.

## Models

Session `state`: `queued | starting | running | awaiting_input | completed | failed | stopped`.

`connectivity` is separate: `online | reconnecting | offline`.

Provider: `claude | cursor | codex | terminal`.

Workspace: `worktree | shared`. Default `worktree`. Shared is allowed only when the client sends `workspace: "shared"`.

File content response: `{ path, text, fingerprint, binary, truncated }`. `fingerprint` is `sha256:` plus hex of the bytes on disk. Binary or files over 1_000_000 bytes set `binary: true` and omit editable text.

Save body: `{ projectId, path, text, expectedFingerprint }`. If the disk fingerprint differs, respond `409` `REMOTE_CHANGED` and include `details.fingerprint` and `details.text`. Do not write. Clients may then upload with a new name (`Save a separate copy`) or reload.

Paths must resolve inside the project root. Reject `..` escapes and symlinks whose target leaves the root.

Commit stages only `paths`. Never `git add -A`. Push and deploy require their own calls. Save does not commit or deploy.

## Providers

Detect `claude`, `agent` (Cursor CLI), and `codex` with `--version`. Record the version. If the binary is missing, `installed: false` and session start returns `UNSUPPORTED`.

Start providers in a persistent PTY when `node-pty` builds. If it does not build, use a detached process supervisor and document that limitation in the report. Do not invent ACP, cookie, or private APIs.

Write the user's prompt to the PTY stdin. Keep stdout/stderr as session events of type `output`. A terminal transcript is not structured tool state. If you cannot reliably detect a permission prompt, leave approval cards empty and keep the terminal available.

`terminal` provider is `bash` in the project workspace.

Dev processes and agent processes must survive a client disconnect. They stop only on interrupt, stop, close, or crash.

## Git identity

Do not change git config. Commit uses the environment's existing identity. If commit fails, return the git stderr as `VALIDATION` or `PERMISSION`.
