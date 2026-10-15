# Remote connectivity — implementation report

## Implemented behaviour

### 1. SSH host-key pinning (`ssh/`)

`HostKeyStore` keeps an in-memory `Map<profileId, fingerprint>`. Three outcomes on first or subsequent connects:

| Situation | `HostKeyStatus` | `ConnectResult.message` |
|---|---|---|
| Key not yet in store | `Unknown` | `"Unknown host key: SHA256:…"` |
| Key matches stored | `Accepted` | `"Connected to <host>"` |
| Key differs from stored | `Changed` | `"Changed host key"` |
| Profile manually rejected | `Rejected` | `"Key rejected"` |

`PocketHostKeyRepository` implements JSch's `HostKeyRepository`. In `check()` it computes the SHA-256 fingerprint (`java.util.Base64`, no Android SDK) and delegates to `HostKeyStore`. JSch is configured with `StrictHostKeyChecking = "yes"`, so an `OK`-returning check proceeds; `NOT_INCLUDED` or `CHANGED` cause `JSchException`, which `SshSessionManager.connect()` catches and maps to `ConnectResult`. The `capturedStatus` and `capturedFingerprint` fields on the repository let `SshSessionManager` pick the right message without re-parsing the exception string.

`FingerprintUtil.computeFingerprint()` uses `MessageDigest("SHA-256")` and `java.util.Base64.getEncoder().withoutPadding()`, producing `SHA256:<base64>` — the same format OpenSSH shows on `ssh-keygen -l -E sha256`.

`HostKeyStore.accept(profileId, fingerprint)` is called by `RemotePocketRepository.acceptHostKey()` and saves the key for future sessions. `HostKeyStore.storedFingerprint()` is exposed for `testConnection` diagnostics.

ProxyCommand strings are never read or executed. The `ConnectionProfile` model has no field for them. Google Cloud profiles are rejected at `connect()` time (see §6 below).

### 2. TCP port forwarding (`ssh/PortSelector`, `ssh/SshSessionManager`)

`PortSelector.findFree(startPort, maxAttempts)` tries to bind a `ServerSocket` on `127.0.0.1:startPort`. If the bind throws, it increments and retries up to `maxAttempts` (default 20). Returns `Pair<Int, Boolean>` — the chosen port and a flag indicating a shift occurred.

`SshSessionManager.addForward(remoteHost, remotePort, preferredPhonePort)`:
1. Calls `PortSelector.findFree(preferredPhonePort)` to obtain a free phone-side port.
2. Calls `session.setPortForwardingL("127.0.0.1", port, remoteHost, remotePort)` — JSch opens a `ServerSocket` on the phone and routes incoming TCP connections through an SSH local-forward channel to `remoteHost:remotePort` on the VM.

`RemotePocketRepository.addMapping()` records the shift and appends `(port N)` to the label so the user sees it. `startMapping()` calls `addForward`; `stopMapping()` calls `session.delPortForwardingL()`. Multiple mappings are supported and tracked in `_mappings`.

This is real TCP forwarding via JSch channels — not an HTTP proxy. WebSocket, HMR, and streaming all pass transparently because JSch forwards raw TCP.

### 3. Foreground service (`service/PocketConnectionService`)

- Channel ID `"pocket-connection"`, IMPORTANCE_LOW, created idempotently.
- Notification ID 41, ongoing, shows `"<state> — <host>"`.
- A **Disconnect** action sends `ACTION_DISCONNECT` to the service, which calls `stopSelf()`.
- `START_STICKY` ensures Android restarts the service after a kill.

**Reconnect loop** (`startConnectionLoop`): coroutine on `Dispatchers.IO`. On connection success, polls `sshManager.isConnected` every 5 s. When the session drops, backs off: 1 s → 2 s → 4 s → … capped at 30 s. Non-retryable messages (`"Unknown host key"`, `"Changed host key"`, `"Key rejected"`, `"Sign in again"`) stop the loop and show the message in the notification without further retries.

`onDestroy()` cancels the coroutine scope and calls `sshManager.disconnect()`, which closes phone-side JSch sockets. **The notification and docs are clear that this does not stop VPS processes.** Agent sessions and dev servers run independently on the VPS.

`PocketConnectionService.sshManager` and `.repository` are companion-object singletons. The repository reference is set by the consumer (future `RepositoryFactory`) before `startService()` is called.

### 4. Companion HTTP client (`data/remote/CompanionClient`)

OkHttp 4.12, synchronous calls (called from `Dispatchers.IO` coroutines in the repository).

- Every authenticated request adds `Authorization: Bearer <token>`.
- POST mutations that accept an idempotency key receive `Idempotency-Key: <value>` header.
- `postEmpty()` handles no-body mutations (`interrupt`, `resume`, `close`, `stopProcess`) — avoids the Kotlin `Nothing` reified-type issue with null bodies.
- `execute()` parses error bodies: `{"error":{"code":"…","message":"…"}}` → `CompanionException(code, message)`. Unmapped HTTP codes fall through `httpStatusToCode()`.
- File contents (`fileContent`, `saveFile`) are handled inside the client methods; their response bodies are **not logged** anywhere in this implementation. Tokens flow only through the `Authorization` header, never through log calls.

All protocol endpoints from `PROTOCOL.md` are covered: health, pair, projects, sessions, prompt, interrupt, permission, resume, close, patch, events, files (list/content/save/mkdir/rename/move/copy/delete), git (status/diff/branches/commit/push/pull), processes (list/start/stop), deploy (recipes/start/run).

`IDEMPOTENT_REPLAY` from the server is handled transparently — the server returns the original result; the client returns it to the caller without a second side effect. Client-side duplicate detection is in `IdempotencyTracker` (used in the session/git/deploy flows) as a first-layer guard.

### 5. `RemotePocketRepository`

Implements `PocketRepository`. Key behaviours:

- **Companion unreachable**: `connect()` calls `client.health()` after SSH succeeds. Failure returns `ConnectResult(false, "Companion is unreachable at …", recovery)` instead of fake success.
- **Pairing**: if `ConnectionProfile.setupCode` is set and no token is stored, `pair()` is called immediately after health check. Token is cached in memory (keyed by `connectionId`).
- **Polling**: `startPolling()` launches a coroutine refreshing projects, sessions for the active project, and events (using the `after=seq` cursor) every 3 s. `_sessions`, `_events`, `_projects` are `MutableStateFlow` so UI flows update reactively.
- **Port shifts are visible**: `addMapping()` appends `(port N)` to the label when a shift occurs so the user sees the actual port in the UI.
- **Delegate pattern**: methods the companion API doesn't map to UI-only demo state (transfers, terminal I/O) delegate to the injected `PocketRepository` (intended to be a `DemoRepository` instance for now). `connect`, `listDir`, `openBuffer`, `saveBuffer`, `gitStatus`, `gitDiff`, `commit`, `push`, `pull`, `startMapping` all use the real network.
- **`SshCredentials`**: `setCredentials(connectionId, creds)` accepts `KeyAuth(privateKeyBytes, passphrase?)` or `PasswordAuth(password)`. Not part of the `PocketRepository` interface — called by the credential management layer before `connect()`.

### 6. Google Cloud

`connect()` returns immediately for `ConnectionKind.GoogleCloud`:

```
ConnectResult(false, "Sign in again",
  "No ProxyCommand is configured and there is no proven Android IAP client
   in this build. Google Cloud connections are not yet supported without a
   valid, non-shell ProxyCommand.")
```

`testConnection()` returns a `DiagnosticLine("Google transport", false, "Sign in again. No ProxyCommand is configured…")`. IAP is not implemented. This matches the plan's feasibility gate requirement.

## How `RepositoryFactory` should wire `RemotePocketRepository`

```kotlin
// In RepositoryFactory.create(context):
val hostKeyStore = HostKeyStore()
val sshManager = SshSessionManager(hostKeyStore)
// Or reuse PocketConnectionService.sshManager if the service is already started
val repo = RemotePocketRepository(
    sshManager = sshManager,
    delegate = DemoRepository(),
)
PocketConnectionService.repository = repo
// instance = repo
return repo
```

The service and repository share the same `sshManager` instance so that when the service reconnects, the repository sees the live session. `PocketConnectionService.sshManager` is already a singleton companion; `RepositoryFactory` can pass it directly.

Connection profiles must be seeded via `repo.upsertConnection(profile)` before calling `connect()`. Credentials (SSH key bytes or password) are set via `repo.setCredentials(connectionId, creds)` from the credential UI flow.

## Tests run

`./gradlew testDebugUnitTest --tests "dev.pocket.app.PortSelectorTest" --tests "dev.pocket.app.IdempotencyTest"`

**BUILD SUCCESSFUL** — 12 tests, 0 failures, 0 errors.

Test classes are pure JVM (no Android framework imports). `PortSelectorTest` covers: free port returned unchanged, occupied port shifts, shifted port is actually bindable, two consecutive occupied ports both skipped, exhaustion throws `PortExhaustedException`. `IdempotencyTest` covers: first use unseen, repeat seen, different keys independent, triple-attempt applies once, reset clears, 100 distinct keys all fresh, prompt retry scenario.

No device test and no live VM test were run. This report does not claim either.

## Limits and known gaps

| Area | Limit |
|---|---|
| Key persistence | `HostKeyStore` is in-memory. A re-launched app loses accepted keys and re-prompts for fingerprint review. Needs DataStore or `security-crypto` persistence. |
| SSH credentials | No UI to import a private key. `setCredentials()` must be called programmatically. JSch will fall back to OS default-identity paths (`~/.ssh/id_rsa`) on non-Android JVMs; on Android these paths don't exist without explicit import. |
| Companion token | Stored in memory; lost on process death. Needs encrypted DataStore persistence. |
| Terminal | PTY WebSocket (`/v1/pty/:sessionId`) is not implemented. `terminalInput` and `terminalResize` delegate to `DemoRepository`. Needs an OkHttp WebSocket client. |
| Transfer SFTP | Upload/download delegate to `DemoRepository`. Real transfers require SFTP over the JSch session (SFTP subsystem channels) or a dedicated companion transfer API. |
| Event WebSocket | Events are polled via `GET /v1/events?after=` every 3 s. The WebSocket stream (`/v1/events/stream`) is not connected. Real-time responsiveness and battery use will improve with WebSocket. |
| Polling scope | Only the active project's sessions are refreshed. Multi-project session lists require iterating all projects. |
| Google Cloud | IAP is not implemented. The service returns a clear diagnostic. Unblocks direct SSH work. |
| Android service restart | On `START_STICKY` restart after kill, the service has no `connectionId` to reconnect to (the intent extras are gone). Needs a persisted "last active connection" in SharedPreferences or DataStore. |
| Reconnect detection | `sshManager.isConnected` polls JSch `session.isConnected`. A half-open TCP connection might return `true` until a keepalive timeout. JSch's `ServerAliveInterval` / `ServerAliveCountMax` should be configured for faster drop detection. |
