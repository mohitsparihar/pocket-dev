# Shell UI — implementation report

## Screens finished

### Chats (`ui/chats/ChatsScreen.kt`)

- **ChatsScreen**: filter chips (All / Running / Needs input / Completed) calling `repository.setSessionFilter`; lazy list of `ChatRow`s with title, `ProviderBadge`, project+branch subtitle, latest line, and `StatusChip`; FAB; empty state naming the next action.
- **NewChatSheet** (bottom sheet): project picker (chips, horizontal-scroll), provider picker, title field, optional model field (hidden for Terminal provider), workspace choice (Worktree / Shared) with explanatory copy, Start Chat button disabled until project and title are filled; error banner when no projects exist.
- **ChatDetailScreen**: custom toolbar row (Surface + Row, no second Scaffold) with back button, session title, Stop/Resume icon, and a More menu (Rename, Archive, Open terminal, View changes). `LazyColumn` with auto-scroll to the latest event. Events expand when longer than 300 chars; code/output events use monospace. Pending approval cards show the full `action` + `detail` with Allow / Deny buttons. Changed-file rows are tappable and navigate via `onOpenFile`. Fixed composer at the bottom with `imePadding()`; idempotency key is regenerated after each send. Rename dialog is an `AlertDialog`.

### Projects (`ui/projects/ProjectsScreen.kt`)

- **ProjectsScreen**: lazy card list showing VPS connection name, folder path, branch, active-chats count, preview status chip; FAB; empty state.
- **NewProjectSheet**: connection picker, name, optional folder path; calls `upsertProject` with a fresh UUID.
- **ProjectDetailScreen**: toolbar row; 8 action buttons in a 2-column `FilledTonalButton` grid (New Chat, Files, Editor, Changes, Terminal, Preview, Git, Deploy); setup form (folder, dev command, comma-separated ports, test command); Save button calls `upsertProject`; save confirmation text replaces the button label. New Chat opens `NewChatForProjectSheet` which creates the session and calls `onOpenChat(session.id)`.

### Preview (`ui/preview/PreviewScreen.kt`)

- `mappings()` flow displayed as `MappingCard`s. Each card shows: label, VM target (`remoteHost:remotePort`), phone port, URL. `processState` and `tunnelState` are shown as separate `StatusChip`s so they can differ independently. Start / Stop button switches based on combined running state; Copy URL uses `LocalClipboardManager`; Open uses `Intent.ACTION_VIEW` via `LocalContext`.
- Failed-tunnel handling: after `startMapping` returns `TunnelState.Failed`, an error banner appears on that card: "Port X is occupied — offered port: Y" where Y is the `phonePort` from the returned `PortMapping`.
- Add Mapping form at the bottom: label, VM port, phone port, validation, calls `addMapping` with the active project ID. If no active project, a warning banner replaces the form.
- Empty state with next-action text.

### Connections (`ui/connections/ConnectionsScreen.kt`)

- `connections()` flow as `ProfileCard`s; active profile highlighted with `primaryContainer` tint.
- Per-profile: Connect / Disconnect buttons (only one shown at a time), Test (calls `testConnection`, results shown inline as a `DiagnosticLine` list with ✓ / ✗ icons), Edit (opens `EditProfileSheet`), Delete (AlertDialog confirmation).
- `ConnectResult`: if `!ok`, an error banner shows `message` and `recovery` string so the user knows the exact next step.
- `EditProfileSheet`: kind selector (Direct SSH / Google Cloud); Direct SSH fields: host, port, username; Google Cloud fields: project, zone, username, ProxyCommand text area with a **notice that Google sign-in is not available until a valid ProxyCommand is supplied** and that "Google login alone does not create an IAP tunnel". Companion port field for both kinds.
- Empty state with next-action text.

## Known compile risks / items not verified

- **`Icons.Outlined.AccountTree`** — used in ProjectsScreen for branch and Git. Present in `material-icons-extended`; if the version in the BOM predates it, substitute `Icons.Outlined.Link`.
- **`Icons.Outlined.Terminal`** — extended icon, available since Material Icons 1.4. May need explicit extended dependency version check.
- **`Icons.Outlined.CompareArrows`** — extended, used in Changes action and Chat detail menu.
- **`FilterChip` / `ModalBottomSheet`** — annotated `@OptIn(ExperimentalMaterial3Api::class)`; if the BOM resolves to Material3 1.3+ these are stable and the annotation is harmless.
- **Auto-scroll in ChatDetailScreen** — scrolls to the last event whenever `events.size` changes. If the user has scrolled up to read history this will yank them back down. A production build should track a "user is at bottom" flag; noted for follow-up.
- **`acceptHostKey`** — the `ConnectResult` model does not carry the new fingerprint, so the UI cannot surface the "Accept new host key" button with the correct value. The recovery string from `ConnectResult.recovery` is shown in plain text; the user must act outside the app for now.
- **`startMapping` offered port** — relies on the convention that `PortMapping.phonePort` in the failure return value carries the suggested alternative. This is an assumption about the `DemoRepository` / real implementation; if it always echoes the original port the banner will mislead the user.
- **New project sheet needs at least one connection** — if the user has no connections the sheet blocks project creation. The UI shows a clear error banner and directs the user to the Connections tab.
- **`Intent.ACTION_VIEW` security** — URL is taken from `PortMapping.url` which is constructed server-side; no user-supplied raw URL is passed to the intent directly.
- No git commit has been created.
