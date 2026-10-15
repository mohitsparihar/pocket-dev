# Workspace screens — behavior and gaps
Generated 2026-10-15

## File manager

**Behavior**
- Opens at the project folder from `repository.project(projectId).folder`.
- Breadcrumbs are rendered as tappable `TextButton`s; tapping any crumb navigates directly to that path.
- VPS name and current path are shown as a monospace label at the top.
- A search `OutlinedTextField` filters the current listing in real time (case-insensitive).
- Files are sorted: directories first, then alphabetically.
- A checkbox per row enables multi-select; the Delete button appears when anything is selected and requires confirmation.
- Per-row actions (Open, Edit, Download) are in a `DropdownMenu` on the ⋮ button.
- Open and Edit both call `onOpenFile(path)`, which navigates to the editor.
- Upload uses `ActivityResultContracts.OpenMultipleDocuments` launched with `*/*`. Files are processed one at a time. If a destination collision is detected, a dialog offers Skip / Keep both / Replace before enqueuing with `enqueueUpload`.
- Download shows a label-entry dialog. In demo mode `enqueueDownload` records a completed transfer in the queue. On a connected VPS the companion would stream bytes; the phone destination is the label the user typed.
- The transfer queue shows direction icon, source → destination, `fmtBytes(done) / fmtBytes(total)`, a progress bar while transferring, a status chip, and Retry / Cancel buttons.
- Folder creation calls `repository.mkdir`.

**Gaps**
- File rename, move, and copy are declared in `PocketRepository` but have no UI entry points yet.
- The actual file size from the phone's content resolver is not read; `enqueueUpload` is called with `0L`. A real upload would need `contentResolver.openFileDescriptor(uri,"r")?.statSize` on the IO dispatcher.
- Download does not persist a URI permission or call `CreateDocument`; it records a demo transfer. A production implementation needs `ActivityResultContracts.CreateDocument` followed by streaming.
- Folder downloads (recursion/zip) are not implemented and not implied.
- The collision queue processes files sequentially but the outer coroutine may still run forward if the collision dialog is dismissed without a choice; a full state machine would be more robust.

---

## Editor

**Behavior**
- When `path` is empty, a buffer list is shown. Tapping a buffer switches the editor to that path without navigation.
- On a non-empty path, `openBuffer(projectId, path)` is called once. The returned `EditorBuffer.binary` flag shows "Binary file — use Download" instead of the editor.
- The edit surface is `BasicTextField` inside a `Row` also containing a line-number `Column`, both in a `verticalScroll` parent. Font size 12 sp and line height 20 sp keep numbers aligned.
- Text changes trigger `updateBuffer` after a 600 ms debounce via `LaunchedEffect(tfv.text)`.
- Undo/redo: each debounce push adds the previous text to `undoStack` (capped at 100) and clears `redoStack`. Undo/redo buttons update `TextFieldValue` and call `updateBuffer` immediately.
- A dirty indicator (`•` after the filename) comes from `buffer.dirty` in the `buffers()` flow.
- Save calls `saveBuffer`. Save All iterates `buffers(projectId)` and saves each dirty buffer.
- A save conflict (`SaveResult.conflict = true`) opens a dialog with three options:
  - **Reload remote** — calls `reloadBuffer` and replaces `TextFieldValue`.
  - **Review differences** — expands two scrollable monospace blocks (local / remote) inside the dialog.
  - **Save a separate copy** — calls `saveCopy`.
- A coding toolbar below the action row inserts `Tab`, `{}`, `()`, `[]`, `""`, `''` at the cursor, plus an Indent key (two spaces).
- A collapsible find bar counts matches and advances the selection with Prev / Next.
- A `FilterChip` tab row appears when two or more buffers are open.

**Gaps**
- There is no syntax highlighting. Tokens are not colored; the plain monospace text is readable but not colored.
- Line numbers are re-rendered for every recomposition; a large file (> 2 000 lines) would be slow. Virtualisation is deferred.
- The `verticalScroll` parent and `BasicTextField` can conflict when the software keyboard appears; `imePadding` is not applied to the editor column.
- Find-bar match jumps update the `TextFieldValue` selection, but the parent `verticalScroll` does not automatically scroll to show the selection. The user may need to scroll manually.
- Tab insertion inserts the literal string `"Tab"` (the toolbar label), not a tab character. The `insertAtCursor` call for the Tab chip should pass `"\t"` instead. **Fix needed.**
- There is no save-on-back prompt; navigating away with unsaved changes does not warn the user.

**Fix for Tab chip**  
In `FileEditor`, the toolbar section passes the label string to `insertAtCursor`. The Tab chip must pass `"\t"` not `"Tab"`:
```kotlin
listOf("\t" to "Tab", "{}" to "{}", "()" to "()", "[]" to "[]", "\"\"" to "\"\"", "''" to "''")
    .forEach { (insert, label) -> ... clickable { insertAtCursor(insert) } ... Text(label) }
```

---

## Changes

**Behavior**
- `gitStatus(projectId)` loads on entry. The first file in the list is auto-selected.
- Changed files appear in a left column (35 % width) with a one-letter status badge (M / A / D) colored from the theme's Ok / Danger / primary values.
- An info banner explains that uncommitted files may include both agent and manual edits, not just agent changes.
- A base selector (`FilterChip` row) switches between Worktree, Index, and Commit. When Commit is selected, a text field appears for a hash or ref.
- `gitDiff(projectId, path, diffBase, commitStr)` is called when the selected file or base changes.
- The diff is parsed line-by-line: `+` lines get green tint, `-` lines get red, `@@` hunk headers get indigo tint, file headers (`diff --git`, `---`, `+++`) get `surfaceVariant`.
- Lines where `filePath != null` (parsed from `+++ b/path` and `diff --git ... b/path`) are tappable and call `onOpenFile(path, lineNumber)`. The line number is extracted from the nearest preceding `@@ +N @@` hunk header.
- `softWrap = false` keeps each diff line on one row so the monospace columns line up.

**Gaps**
- No horizontal scroll on the diff column; long lines are truncated.
- Tapping a context line (not a file-path line) does nothing. The spec says hunk lines should navigate to the relevant line; currently only file-header lines navigate.
- Side-by-side diff (mentioned as optional on wider screens) is not implemented.
- No "review full file" action.

---

## Terminal

**Behavior**
- Observes `repository.terminal(projectId)` as a `Flow<TerminalSnapshot>`.
- Output is rendered in a `SelectionContainer > Text` with `FontFamily.Monospace` on a near-black background (`#0D1117`), in a `verticalScroll` box.
- `LaunchedEffect(snapshot.output)` calls `scrollState.animateScrollTo(maxValue)` on each new output so the view stays at the bottom.
- A status label shows "Session active" or "Session closed" from `snapshot.alive`.
- A note explains that the session lives on the VPS and reconnecting restores output.
- An `OutlinedTextField` + Send button sends the typed text via `terminalInput(projectId, text)`.
- The **Ctrl+C** button sends the ETX byte (``) via `terminalInput`.
- The **Paste** button appends clipboard text to the input field.
- **Copy all** copies `snapshot.output` to the clipboard.

**Gaps**
- `imePadding()` is applied at the column level but the host `Scaffold` may absorb it; keyboard-obscuring the input row on some devices is possible.
- The terminal does not call `terminalResize` when the composable size changes. A production implementation would measure the available columns and rows and call `terminalResize`.
- There is no explicit clear button that calls a repository method; clearing would require the repository to expose a reset.
- Bounded scrollback is not enforced in the UI; very long sessions keep the full text in memory.

---

## Git

**Behavior**
- `gitStatus` and `branches` are loaded once on entry.
- Each changed file has a `Checkbox`; selected paths accumulate in a `mutableStateListOf`.
- The Commit button is disabled when no files are selected or the commit message is blank. It calls `repository.commit(projectId, selected, message, UUID)`. On success it clears the selection and message and reloads status.
- Push and Pull each generate a fresh `UUID` idempotency key per tap. Results appear in a status banner with a `StatusChip` and the `GitOperationResult.message`.
- Branch list is collapsible; the current branch is marked with a checkmark icon.
- There is no stage-all button.

**Gaps**
- Status is not refreshed after Push or Pull, only after Commit.
- There is no UI for worktree switching or conflict resolution.
- The branch list is read-only; checkout is not implemented.

---

## Deploy

**Behavior**
- `repository.recipes(projectId)` is observed as a flow. Recipes appear as tappable `Card`s showing name, command, working directory, and target.
- Tapping a recipe selects it. A **Deploy** button appears below, disabled while a run is in `ProcessState.Running`.
- `startDeploy(projectId, recipeId, UUID)` is called on tap; the returned `DeploymentRun.id` is stored in `currentRunId`.
- `produceState(key = currentRunId)` collects `deployRun(id)` so the log and state update reactively.
- The log is shown in a monospace scrollable `Surface` with a `SelectionContainer`. A `StatusChip` reflects Running / Finished / Failed.
- A `LinearProgressIndicator` appears while the run is active.
- Pressing Deploy while `isRunning` is impossible because the button is disabled.

**Gaps**
- Only the most recent run's log is shown. Past runs are not listed.
- There is no `saveRecipe` UI; users cannot edit recipe fields from this screen. A form would need fields for name, command, directory, target, and health URL.
- Health URL is stored in the model but not shown or checked in the UI.
- No retry or re-run button for a completed or failed run (other than pressing Deploy again for a new run).

---

## Common notes

- All screens fill the content area of the host `Scaffold` and do not add a second scaffold or bottom navigation bar.
- Material 3 `MaterialTheme.colorScheme` and `MaterialTheme.typography` are used throughout. `ui/theme/Color.kt` constants (`Ok`, `Danger`, `Indigo`) are imported for semantic colors.
- `StatusChip` from `ui/components` is used in file manager transfer rows, git result banners, and deploy log headers.
- Touch targets: all interactive rows and icon buttons are at least 48 dp. Action rows use `height(48.dp)` on buttons.
- Left-aligned layout with 16 dp horizontal padding is consistent across all screens.
- Empty states describe the next action rather than saying "nothing here".
- Errors from repository operations are surfaced as text messages, not silent failures.
