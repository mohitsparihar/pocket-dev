Implement Pocket Dev's direct SSH connection, TCP port forwards, foreground service, and companion HTTP client.

You may only create or edit:
- android/app/src/main/java/dev/pocket/app/ssh/ (new files)
- android/app/src/main/java/dev/pocket/app/service/PocketConnectionService.kt (replace the stub)
- android/app/src/main/java/dev/pocket/app/data/remote/ (new files)
- android/app/src/test/java/dev/pocket/app/ (unit tests)
- docs/reports/remote.md

Do not edit UI, DemoRepository.kt, RepositoryFactory.kt, Gradle, or the manifest. The service class name must stay `dev.pocket.app.service.PocketConnectionService`.

Read docs/PROTOCOL.md, Pocket_Dev_App_Plan.md (architecture, port forwarding, Google Cloud gate), and PocketRepository.kt.

Build:
1. Host-key pinning with JSch (`com.github.mwiede:jsch` is already a dependency). Remember accepted fingerprints. A changed key returns a failed ConnectResult whose message is "Changed host key" and recovery tells the user to review it. Key rejection says "Key rejected". Unknown host says the fingerprint and does not connect until acceptHostKey is used. Do not execute ProxyCommand strings.
2. Real local TCP forwarding: phone ServerSocket on 127.0.0.1, SSH local forward to the VM host and port. If the phone port is taken, pick the next free port and say so. Support several forwards. This is TCP, not an HTTP rewrite proxy.
3. PocketConnectionService is a foreground service with a notification that says the connection state and includes a Disconnect action. Reconnect with a short bounded backoff. Stopping the service closes sockets and does not claim the VPS processes stopped.
4. CompanionClient in data/remote talks to the protocol: health, pair, projects, sessions, prompt, interrupt, permission, files, git, processes, deploy, event replay. Send Idempotency-Key. Map HTTP errors to the protocol error codes. Do not log tokens or file contents.
5. RemotePocketRepository implements PocketRepository by combining the SSH session, forwards, and companion client. Where the companion is unreachable, return a failed ConnectResult instead of fake success. You may leave Demo-only methods delegating to an injected PocketRepository for UI-only flows you cannot complete, but connect, mappings, and file reads must use the network when a Direct SSH profile is connected.
6. Google Cloud: do not implement IAP. testConnection for a Google Cloud profile returns a diagnostic "Sign in again" explaining that no redacted ProxyCommand was provided and there is no proven Android IAP client in this build.
7. JVM unit tests for port selection (occupied port offers the next one) and idempotency (same key does not double-apply). Keep tests free of Android framework classes so `testDebugUnitTest` can run them. Put the pure logic in ssh/ or data/remote so the tests can instantiate it.

Do not git commit. Write docs/reports/remote.md with implemented behavior, how RepositoryFactory should later call RemotePocketRepository, tests you ran, and limits. Do not claim a device test or a live VM test unless you actually ran one.
