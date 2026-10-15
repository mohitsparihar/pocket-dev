# Android UI contract

Package root: `dev.pocket.app`. Material 3. Dark is the default. Also support light and system. Charcoal surfaces, indigo actions, 16sp chat text, monospace for code and terminal, 48dp touch targets. Status uses an icon and a word, not color alone.

The host (`ui/nav/PocketApp.kt`) already draws the top bar and the bottom tabs. Screen composables must not add another bottom bar or a second scaffold. They fill the content area and handle their own scrolling.

Use `PocketRepository` for all data. You may add files under your package. Keep these signatures:

```kotlin
@Composable
fun ChatsScreen(repository: PocketRepository, onOpenChat: (String) -> Unit)

@Composable
fun ChatDetailScreen(
    repository: PocketRepository,
    sessionId: String,
    onBack: () -> Unit,
    onOpenTerminal: (String) -> Unit,
    onOpenChanges: (String) -> Unit,
    onOpenFile: (String, String) -> Unit,
)

@Composable
fun ProjectsScreen(repository: PocketRepository, onOpenProject: (String) -> Unit)

@Composable
fun ProjectDetailScreen(
    repository: PocketRepository,
    projectId: String,
    onBack: () -> Unit,
    onOpenChat: (String) -> Unit,
    onOpenFiles: () -> Unit,
    onOpenEditor: () -> Unit,
    onOpenChanges: () -> Unit,
    onOpenTerminal: () -> Unit,
    onOpenPreview: () -> Unit,
    onOpenGit: () -> Unit,
    onOpenDeploy: () -> Unit,
)

@Composable
fun PreviewScreen(repository: PocketRepository)

@Composable
fun ConnectionsScreen(repository: PocketRepository)

@Composable
fun FileManagerScreen(
    repository: PocketRepository,
    projectId: String,
    onOpenFile: (String) -> Unit,
)

@Composable
fun EditorScreen(
    repository: PocketRepository,
    projectId: String,
    path: String,
    onBack: () -> Unit,
)

@Composable
fun ChangesScreen(
    repository: PocketRepository,
    projectId: String,
    onOpenFile: (String, Int) -> Unit,
)

@Composable
fun TerminalScreen(repository: PocketRepository, projectId: String)

@Composable
fun GitScreen(repository: PocketRepository, projectId: String)

@Composable
fun DeployScreen(repository: PocketRepository, projectId: String)
```

`path` may be empty. The editor then shows open buffers.

Reusable pieces you may call but must not edit: `ui/components/StatusChip.kt`, `ui/components/ProviderBadge.kt`, `ui/theme/*`.

Phone layout, left aligned, generous padding (16dp). Empty states tell the user the next action. Errors name the failure and the recovery action from `ConnectResult` / `DiagnosticLine`.

Chats: rows with title, provider badge, project and branch, latest line, state. Filters All, Running, Needs input, Completed. New chat is a sheet: project, provider, title, model if the provider supports it, workspace choice. Detail: streaming-style message list, expandable output, changed-file cards, approval cards with the full action and Allow / Deny, fixed composer. Actions: stop, resume, rename, archive, open terminal. Switching provider means creating a new session.

Projects: cards with VPS, folder, branch, active chats, preview status. Detail has the seven actions and a setup form for folder, dev command, ports, test command, and deploy recipe name. Do not assume Next.js.

Preview: each mapping shows VM target, phone port, URL, Open, Copy, Stop. Separate process state from tunnel state. Open uses an `ACTION_VIEW` intent. Several mappings can run together.

Connections: saved profiles, Connect, Disconnect, Test, Edit. Show recoverable errors in words: Sign in again, Key rejected, Changed host key, Local port occupied, VM unreachable.

File manager: breadcrumbs, VPS and path label, list, parent, search, create folder, multi-select, metadata, symlink label. Actions: Open, Edit, Upload, Download, Rename, Move, Copy, Delete. Confirm delete. Uploads use `ActivityResultContracts.OpenMultipleDocuments`. Downloads use `CreateDocument` or `OpenDocumentTree` only if you actually persist the URI permission. Show the transfer queue with direction, bytes, progress, and state, plus retry and cancel.

Editor: syntax-colored-enough plain highlighting for common web languages is acceptable if a full highlighter is too heavy, but line numbers, search, indent, undo, and a coding toolbar are required. Unsaved marker. Save and Save all. Conflict dialog: Reload remote, Review differences, Save a separate copy.

Changes: file list and unified diff. Base selector for worktree, index, or a commit hash. Tap a hunk line to open the editor. Do not claim agent-only authorship.

Git: select files, commit, push, pull, branches. No auto-stage-all. Show failed push text.

Deploy: show the command and target, require an explicit Deploy tap, show logs, and block a second run while one is active.

Terminal: output, input, Ctrl+C, paste, clear. Session lives on the repository, not in the phone process.
