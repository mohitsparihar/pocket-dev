# Pocket Dev — Android app plan and design
Prepared for Mohit Singh Parihar • 14 October 2026
Working name. Personal Android app to develop with Claude Code.

## Goal
One app to connect to a VPS, manage multiple Cursor/Claude Code/Codex chats, edit and review files, run dev servers, forward ports to phone localhost, preview in Chrome, push to GitHub, and run existing deployment commands.

Daily use must not require manually operating Termux, gcloud, or tmux. One-time onboarding and occasional reauthentication are acceptable. Phone disconnects must not terminate VPS work.

## Scope
Required: Google Cloud connection matching the existing authenticated SSH configuration; direct SSH for other servers; independent persistent chats; terminal; file manager with uploads/downloads, code editor and diffs; several local port mappings; external browser preview; Git and configurable deployment.

Defer phone-local execution, full desktop IDE parity, guaranteed offline push notifications, and public multiuser distribution. This is an independent workspace app, not an official Cursor app.

## App design
Native Material 3. Dark default, light/system options. Charcoal surfaces, indigo actions, readable chat text, monospace code, generous touch targets. Status uses words and icons as well as color. Design for a phone rather than shrinking a desktop IDE.

Four bottom tabs: Chats, Projects, Preview, Connections. Files, Changes, Terminal, and Deploy are project actions. Keep the active project and connection status in the top bar.

### Chats
List rows: chat title, provider badge, project/branch, latest message, and state. Filters: All, Running, Needs input, Completed. New Chat opens a sheet for project, provider, title, model when supported, and workspace choice.

Chat detail: streaming messages; expandable tool calls; changed-file cards; approval cards showing the full action; fixed composer. Actions: stop, resume, rename, archive, open terminal. Switching provider creates a new session; do not imply native histories are transferable.

Each chat owns a provider session and process. Default concurrent editing to separate Git worktrees. Shared-folder sessions are an explicit choice. Track connectivity separately from task state.

### Projects
Project cards show VPS, folder, branch, active chats, and preview status.
Detail actions: New chat, File manager, Editor, Changes, Terminal, Start preview, Git, Deploy.
Setup: folder, dev command, expected ports, optional test/build commands and deployment recipes. Do not assume every project uses Next.js.

### Preview
Separate dev-process state from tunnel state. Each mapping shows VM target, phone port, actual URL, and Open/Copy/Stop. Allow several mappings and saved project presets. External Chrome is a core feature; in-app WebView can follow.

### Connections
Saved profiles: name, Direct SSH or Google Cloud, host/project/zone, username, last connection.
Actions: Connect, Disconnect, Test, Edit, Diagnostics.
Recoverable errors: Sign in again, Key rejected, Changed host key, Local port occupied, VM unreachable. Credentials are set up once where possible.

### Typical journey
Open app → Work VM → Retail dashboard → resume Fix filters → review changes → Start preview → Chrome localhost → Git commit/push → explicit configured Deploy action.

## File manager, transfers, manual editing and code review

These are required in the first usable release, not deferred enhancements. Project actions become File manager, Editor, Changes, Terminal, Preview, Git, and Deploy. The file manager opens at the project folder with breadcrumbs and a visible VPS/path label. Support list view, parent navigation, filter/search, folder creation, multi-selection, metadata, and authorized browsing. File actions: Open, Edit, Upload, Download, Rename, Move, Copy, Delete. Confirm destructive deletion and overwrite choices; respect remote permissions and label symlinks.

Uploads use Android system document pickers for one or several files. Stream content URIs rather than assuming filesystem paths or loading whole files into memory. Select the remote destination; resolve collisions using Skip, Keep both, or Replace. Preserve phone source files. Downloads use a system-selected Android destination, then offer Open/Share. Persist URI permissions when supported and recover clearly when permission is revoked. Folder transfer requires separately implemented recursion/archive export; do not imply a folder picker alone provides it.

Provide a transfer queue showing direction, source/destination, bytes, progress, and queued/transferring/disconnected/completed/failed/cancelled states. Offer retry and cancel. SFTP over the authenticated SSH connection is the preferred candidate, subject to library capability; a companion transfer adapter is an alternative. Upload via temporary remote names and finalize only after successful persistence/verification. Interrupted files must never appear as completed. Resume depends on unchanged source identity, remote offsets, target state, and Android URI seek support; reliable restart is the initial fallback. Test large files, name collisions, insufficient space, remote permission errors, network loss, app restart, and cancelled transfers.

Editor: native phone-friendly code editor with syntax highlighting for common web languages, line numbers, search/replace, indentation, undo/redo, selection, and a coding toolbar for Tab/brackets/Ctrl shortcuts. Open multiple files in tabs or a compact file switcher. Show unsaved state and explicit Save/Save all. Use editable text buffers rather than making the user edit terminal output. Full language-server completion and desktop extensions are later enhancements.

Load file content with an encoding/version fingerprint and check for remote changes before saving. An agent may edit the same file: never silently overwrite either version. Offer Reload remote, Review differences, or Save a separate copy; add merge support only when implemented. Preserve unsaved buffers across recoverable app restarts, protected appropriately. Atomic saves are preferred. Large/binary files use preview/download or an explicit size limit, not a broken text editor.

Changes: list modified/added/deleted files and show unified Git diffs by default, with optional side-by-side on wider screens. Allow choosing a base such as working tree versus index or a specified commit. Explain that uncommitted files may include both agent and manual edits; do not invent agent-only provenance from Git status. Tap a diff to open the editor near the relevant line. Review full files as well as changes.

Manual workflow: File manager → open source file → edit → save with remote-version check → run configured test/build → view forwarded site in Chrome → inspect diff → select files for commit/push. An unsaved edit must not be presented as applied to the VPS. Saving updates the active chat workspace/worktree, which is always visible.

Initial review scope: readable diffs, edit-and-save, file-level staging, commit/push. Hunk staging, selective revert, and merge-conflict editing can follow after tested implementation. Do not label these advanced operations as available until shipped. No automatic commit or deployment on Save.

Add TransferJob and EditorBuffer metadata to the data model, including connection/workspace ownership, source fingerprints, destination URI/path, transfer recovery capability, and editor saved-version state. Keep credentials and file contents out of diagnostic logs. VPS storage permissions remain authoritative; companion file APIs must enforce authorized roots and handle symlinks deliberately.

Add acceptance tests: upload a phone image and large archive; download and open/share an output; verify bytes and collision behavior. Edit a file while an agent changes it and verify conflict detection. Recover unsaved buffers after app restart. Confirm saved manual edits appear in the correct worktree diff and browser preview. Verify failed saves and transfers never report success.

Claude Code kickoff addition: implement the required File manager, Android-picker uploads/downloads, native code editor, and Git diff review in the first usable release. Prove real remote read/write and transfer behavior rather than delivering static mock screens. Use streaming transfer APIs, source-version checks, explicit saves, and terminal fallback where appropriate. Do not block direct SSH implementation while waiting for the Google configuration.

## Architecture
Recommended stack: Kotlin + Jetpack Compose Android client; TypeScript/Node.js companion service on the Linux VPS. This is a design choice, subject to feasibility.

Android owns UI, connection profiles, local TCP listeners, SSH transport, background connection lifecycle, and cached metadata.
VPS companion owns agent processes, PTYs, workspaces, durable session events, dev servers, Git, and deployment logs.

Android connects via Direct SSH or a validated Google Cloud transport followed by SSH. SSH channels carry both companion traffic and forwarded dev-service TCP streams. Browser traffic goes to a phone loopback listener, across SSH, then to the VM service.

Bind the companion to VM loopback and authenticate paired devices. Reach it through SSH; do not expose an unauthenticated administrative API. Keep provider credentials in official CLI storage on the VPS.

Run the companion as a user-level service where available. Preserve interactive sessions with a persistent PTY supervisor or tmux fallback. Retain provider-native session IDs and implement provider-specific resume. Saved messages alone do not preserve a running process. VM restart and companion restart have separate recovery semantics.

## Google Cloud feasibility gate
Known: gcloud auth login, then an SSH configuration containing HostName, User, IdentityFile, ProxyCommand.
Unknown: actual ProxyCommand, IAP usage, OS Login, and organizational OAuth restrictions.

Inspect the redacted config when provided. Prove authentication, actual proxy connectivity, SSH, token refresh, reconnect, and local browser forwarding on a real VM before promising seamless Google access. Google login alone is neither an IAP tunnel implementation nor SSH authorization.

If IAP is required, investigate current supported client/protocol options and IAM requirements. Do not assume there is a production-ready native Android IAP SDK. If a maintainable standalone implementation is not established, document the blocker and propose an explicitly chosen interim bridge. Termux being installed does not automatically let another app run its programs or control its lifecycle.

Import supported SSH fields into structured profiles. Recognize known gcloud ProxyCommand patterns; do not execute arbitrary imported shell commands. Report unsupported Include/Match/proxy directives. Desktop IdentityFile paths require a separate Android key import. Direct SSH supports custom ports and key authentication first; password and jump-host capabilities must be verified with the selected library.

Do not loosen VM access policies simply to ship the app.

## Port forwarding
Implement true TCP forwarding, not an HTML-rewriting HTTP proxy. Bind phone listeners to 127.0.0.1 by default. Store remote host, remote port, phone port, project, and label.

| Service | VM target | Phone listener | Browser URL |
| --- | --- | --- | --- |
| Frontend | 127.0.0.1:3002 | 127.0.0.1:3002 | http://127.0.0.1:3002 |
| API | 127.0.0.1:8000 | 127.0.0.1:8000 | http://127.0.0.1:8000 |
| Second project | 127.0.0.1:3000 | 127.0.0.1:3003 | http://127.0.0.1:3003 |

On collision, offer another local port visibly. Preserve mappings across reconnect. Support HTTP, WebSocket/HMR, streaming, and concurrent requests. Use health checks and logs to distinguish tunnel failures from stopped servers.

Client-side API URLs must be reachable from the phone too. Forwarding the frontend does not make private VM hostnames accessible. Cover API mappings, CORS, allowed hosts, absolute redirects, cookies, and framework HMR settings. Live reload must be tested rather than assumed.

Use a compliant Android foreground service for active connections, with a persistent notification and Disconnect. Reconnect with bounded backoff after network changes. Android may still stop the app/service; preserve VPS work and restore mappings on reopening. WorkManager is not a permanent socket host. Do not claim uninterrupted tunnel availability.

## Multiple chats and agent adapters
Session states: queued, starting, running, awaiting_input, completed, failed, stopped. Connectivity is separate.

Per-session records: ID, provider session ID, project, workspace path, branch/worktree, process identity, event history, approval queue. Use ordered events and replay cursors. Idempotent request IDs prevent repeated prompts and mutations after reconnect.

Adapter contract: capabilities, auth status, start, prompt, events, permission response, interrupt, resume, close. Detect installed versions. Unsupported operations must be disabled or use terminal fallback.

Begin with official CLIs in persistent separate terminals. Add structured chat adapters only using current supported interfaces. Cursor ACP is a candidate; verify Claude and Codex interfaces at implementation time. Do not invent APIs, scrape desktop internals, or reuse browser cookies. A terminal transcript is not structured agent state.

Official tool logins may use existing subscriptions within plan limits; arbitrary custom SDK access is not guaranteed and API keys bill separately. Recheck current provider authentication and distribution rules before custom integrations. Preserve permission prompts; do not globally auto-approve for convenience.

Notifications can report status while connected. Guaranteed notifications while the app is disconnected need an additional push architecture and are deferred.

## Files, terminal, Git, deployment
Files: lazy folder loading, basic text editor, and save-conflict detection. Diffs: changed-file list, readable additions/deletions, and worktree context.
Terminal: resize, Ctrl keys, paste, bounded scrollback, reconnect. Sessions live on the VPS.
Git: status, diff, select files, commit, push, pull, branches/worktrees. Do not auto-stage all files. Surface conflicts and failed pushes.
Deploy: user-configured working directory, command, environment reference, target, logs, optional health check. Show target/command and require an explicit Deploy tap. Prevent duplicate deployment requests. Never assume deployment equals git push or follows agent completion automatically.

## Data and protocol
Core entities: ConnectionProfile, Project, Workspace, AgentSession, SessionEvent, ApprovalRequest, PortMapping, ManagedProcess, GitOperation, DeploymentRecipe, DeploymentRun, PairedDevice.

Android: Room metadata/cache; encrypted credential storage backed by Android Keystore as appropriate; secrets excluded from logs/backups. Pin SSH host keys and review changed keys.
VPS: SQLite metadata/events with retention limits. Provider credential contents stay outside app databases.

Suggested versioned companion API:
- Health/capabilities, projects, sessions.
- Prompt, interrupt, permission-response operations.
- WebSocket events with sequence cursors/replay.
- PTY binary stream and resize messages.
- File listings/diffs and version-checked saves.
- Process start/stop/status; Git/deployment operations with idempotency keys.

Validate paths and operation ownership. Treat configured commands as intentional execution authority. Pair/revoke devices and keep logs useful without exposing secrets.

## Build phases
| Phase | Deliverable | Exit evidence |
| --- | --- | --- |
| 0 | Transport/provider feasibility | Real Android SSH forwarding + HMR; persistent CLI; Google route investigated when config arrives |
| 1 | Mock Compose UI | Phone-friendly navigation, multi-chat states, actionable failures |
| 2 | Companion and direct SSH | Projects, file manager/transfers, editor/diffs, terminal, several tunnels, reconnect with event replay |
| 3 | Claude-first vertical slice | Prompt/approval/diff/preview; independent persistent chats and worktrees |
| 4 | Google Cloud integration | Real config supported; refresh/reconnect; no daily manual terminal steps |
| 5 | Cursor/Codex, Git, deploy | Verified adapters/logins; selected commits; deployment against test targets |
| 6 | Recovery and delivery | Physical-phone checks; debug APK; setup guide; known limitations |

Google feasibility starts early even if production integration comes later. Resolve transport and provider uncertainties before polishing the full UI. Avoid fixed schedule estimates until feasibility is established.

## Acceptance tests
1. Configured VM connects without manually operating Termux.
2. Two chats maintain independent prompts, approvals, histories, and worktrees.
3. Phone disconnect does not terminate agents/dev processes; reconnect replays missed events without duplicate actions.
4. External Chrome reaches VM port 3002 through phone localhost; code edits trigger HMR.
5. Frontend + API + WebSocket traffic work through simultaneous mappings.
6. Occupied ports, changed host keys, invalid credentials, expired Google tokens, unreachable VMs, and unsupported CLI versions show recoverable errors.
7. Wi-Fi/mobile switching, lock/unlock, force-stop/reopen distinguish persistent VPS sessions from closed phone listeners.
8. Commit/push to a test branch and deployment to a test target do not duplicate on retries.
9. Companion/VM restart follows documented resume semantics and does not pretend interrupted processes never stopped.

Meaningful unit tests: config parsing, state transitions, replay/idempotency, mapping allocation. Integration tests: real forwarding, process survival, adapters. Physical-device networking tests are required; UI screenshots cannot replace them.

## Deferred features
Phone-local projects need a separate execution backend and compatibility spike for Android storage, Node/native packages, agents, and background execution. Later: richer editor, voice/screenshots, PR management, jump hosts, push relay, tablet layout.

## Claude Code kickoff prompt
Paste this document into Claude Code and use this prompt:

Build Pocket Dev, a personal native Android app for VPS-based coding, including the required file manager, uploads/downloads, manual code editor, and Git diff review described above. Treat this document as the product brief. Inspect the repository and AGENTS.md first. Create a short backlog and capability matrix. Use Kotlin/Compose and a TypeScript/Node companion unless documented feasibility findings justify a change.

Start with Phase 0: verified direct SSH with host-key checking, one true TCP tunnel tested in external Chrome, persistent VPS CLI, and Google transport investigation when the user's redacted ProxyCommand arrives. Missing Google configuration must not block independent direct SSH/UI work.

Then build one vertical slice: choose project → start Claude session → prompt/permission response → reconnect → start preview → open phone-local URL. Implement multi-session isolation before expanding providers. Read current official SSH-library, Android service, Google, and provider documentation. Never invent agent APIs, embed gcloud without proving feasibility, or assume arbitrary SDK subscription access.

Use supported interfaces, explicit capabilities, and persistent-terminal fallback. Keep secrets on the correct host, services on loopback, permission requests interactive, and mutations idempotent. Preserve existing VM policies and deployment workflows.

Test session isolation, network recovery, port forwarding, HMR, and Git/deployment using test targets. Report implemented behavior, actual validation, and limitations at each milestone. Produce an installable debug APK and real-device setup guide. Never claim physical-device or Google-auth tests ran when they did not.

## References and inputs needed later
Recheck these official sources during implementation:
- https://cursor.com/docs/cli/overview
- https://cursor.com/docs/cli/reference/authentication
- https://cursor.com/docs/cli/acp
- https://code.claude.com/docs/en/remote-control
- https://developers.openai.com/codex/cli
- https://developers.openai.com/codex/auth
- https://docs.cloud.google.com/iap/docs/using-tcp-forwarding
- https://coder.com/docs/code-server/guide

Later inputs: redacted SSH block; VM OS/architecture; OS Login status if relevant; ability to install the companion; project/dev commands/ports; deployment recipe; installed agent versions. Never paste private keys or tokens into chat.

