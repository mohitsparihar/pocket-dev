# Parallel ownership

Do not edit files outside your ownership. Do not git commit. Do not change function signatures listed in `docs/ANDROID_UI.md` or the HTTP contract in `docs/PROTOCOL.md`.

| Agent | May write | Must not touch |
| --- | --- | --- |
| Companion | `companion/**`, `docs/reports/companion.md` | `android/**`, the plan, protocol |
| Shell UI | `android/app/src/main/java/dev/pocket/app/ui/chats/**`, `ui/projects/**`, `ui/preview/**`, `ui/connections/**`, `docs/reports/shell.md` | Theme, navigation host, models, repository, other UI packages |
| Workspace UI | `android/app/src/main/java/dev/pocket/app/ui/files/**`, `ui/editor/**`, `ui/changes/**`, `ui/terminal/**`, `ui/git/**`, `ui/deploy/**`, `docs/reports/workspace.md` | Same shared files as shell |
| Remote | `android/app/src/main/java/dev/pocket/app/ssh/**`, `service/PocketConnectionService.kt`, `data/remote/**`, `android/app/src/test/**`, `docs/reports/remote.md` | UI, `DemoRepository.kt`, `RepositoryFactory.kt`, Gradle |

Shared code already exists. Call it. Replace only the stub files inside your packages (and `PocketConnectionService.kt` for the remote agent). You may add new files inside your packages.

The app must compile against the existing `PocketRepository` interface. Screens talk to that interface only. They do not open sockets themselves.
