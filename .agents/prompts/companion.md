Build the Pocket Dev VPS companion. Work only inside `companion/` and write your report to `docs/reports/companion.md`.

Read first:
- Pocket_Dev_App_Plan.md
- docs/PROTOCOL.md
- docs/AGENT_OWNERSHIP.md

Implement a TypeScript Node.js service that matches docs/PROTOCOL.md exactly.

Requirements:
- Bind 127.0.0.1 only. Port from POCKET_DEV_PORT or 8787.
- Use node:sqlite, node:http, node:crypto, node:fs, node:child_process, node:test. You may add the `ws` package and TypeScript tooling. Do not add a web framework unless http becomes unreadable.
- Persist metadata in ~/.pocket-dev/pocket.db. Create pairing.json on first start. Provider credentials stay out of the database and out of logs.
- Projects, sessions, ordered events, approvals, idempotency keys, deploy runs.
- File APIs with fingerprint saves, 409 REMOTE_CHANGED, path escape checks, symlink refusal when the target leaves the project root, binary/size limit.
- Git status/diff/branches and commit of selected paths only. Never git add -A. Never change git config. Push and pull are separate. Surface stderr.
- Dev processes survive client disconnect. Stop only on the stop endpoint or crash.
- Provider sessions: detect `claude`, `agent`, and `codex`. If missing, capabilities say installed:false and start returns UNSUPPORTED. `terminal` starts bash. Use a PTY when node-pty installs; otherwise a process supervisor, documented in the report. Do not invent ACP or private APIs. Write prompts to stdin. Do not auto-approve permissions.
- WebSocket event stream with seq replay and a PTY websocket.
- Tests with node:test for path checks, fingerprint conflict, idempotent prompt/commit, and session state transitions. Run them and record the result.
- package.json scripts: `build`, `start`, `test`.
- Do not git commit. Do not edit android/ or the protocol doc.
- Report what you implemented, the commands you ran, and limitations. Do not claim a physical phone or Google login was tested.
