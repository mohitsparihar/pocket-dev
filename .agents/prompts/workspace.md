Build the Pocket Dev workspace screens: file manager, editor, diffs, terminal, git, and deploy.

You may only create or edit files under:
- android/app/src/main/java/dev/pocket/app/ui/files/
- android/app/src/main/java/dev/pocket/app/ui/editor/
- android/app/src/main/java/dev/pocket/app/ui/changes/
- android/app/src/main/java/dev/pocket/app/ui/terminal/
- android/app/src/main/java/dev/pocket/app/ui/git/
- android/app/src/main/java/dev/pocket/app/ui/deploy/
- docs/reports/workspace.md

Read Pocket_Dev_App_Plan.md sections on file manager, editor, changes, git, and deploy, plus docs/ANDROID_UI.md and PocketRepository.kt. Keep the composable signatures exactly.

Replace the stubs with working phone UI that calls the repository. The host already has the app bar. Do not add a bottom navigation bar.

File manager: start at the project folder from repository.project. Breadcrumbs, path label, list, parent, search box filtering the current listing, create folder, multi-select, metadata, symlink label. Open calls onOpenFile. Edit can call onOpenFile too. Upload uses ActivityResultContracts.OpenMultipleDocuments and enqueueUpload. Download uses enqueueDownload and then a share/open intent only when you have a real local file; otherwise show the transfer as completed in the demo queue and say the phone destination is the label the user typed. Collision dialog: Skip, Keep both, Replace. Confirm delete. Show transfers() with direction, bytes, progress, state, retry, and cancel.

Editor: openBuffer, updateBuffer on each change, dirty flag, Save, Save all for buffers(). Line numbers, monospace, undo/redo via a simple edit stack or the field's undo if you keep it correct, search row, indent button, and a toolbar that inserts tab and brackets. If SaveResult.conflict, offer Reload remote, Review differences (show both texts), and Save a separate copy. Do not silently overwrite. Empty path shows the open buffer list.

Changes: gitStatus file list and gitDiff unified text. Base selector Worktree, Index, or a commit string. Tap a line with a file path to onOpenFile(path, line). Say that uncommitted files can include agent and manual edits.

Terminal: show terminal() output in a monospace scrolling view, a text field, send, Ctrl+C via terminalInput with "\u0003", and paste. Keep the session text from the repository.

Git: checkboxes for files, commit message, Commit only selected paths, Push, Pull, branch list. Show GitOperationResult.message. Disable commit when nothing is selected. Do not add a stage-all button.

Deploy: list recipes, show command, directory, and target, Deploy button, logs from the returned run, and do not start a second run while one is running. Saving a recipe uses saveRecipe.

Material 3, charcoal/indigo, 48dp targets, left aligned. Use StatusChip from ui.components if useful. Do not edit files outside your directories. Do not git commit.

Write docs/reports/workspace.md describing behavior and gaps.
