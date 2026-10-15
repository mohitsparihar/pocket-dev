Build the Pocket Dev phone UI for Chats, Projects, Preview, and Connections.

You may only create or edit files under:
- android/app/src/main/java/dev/pocket/app/ui/chats/
- android/app/src/main/java/dev/pocket/app/ui/projects/
- android/app/src/main/java/dev/pocket/app/ui/preview/
- android/app/src/main/java/dev/pocket/app/ui/connections/
- docs/reports/shell.md

Read first:
- Pocket_Dev_App_Plan.md (App design, Chats, Projects, Preview, Connections)
- docs/ANDROID_UI.md
- docs/AGENT_OWNERSHIP.md
- android/app/src/main/java/dev/pocket/app/data/PocketRepository.kt
- android/app/src/main/java/dev/pocket/app/model/Models.kt
- android/app/src/main/java/dev/pocket/app/ui/components/StatusChip.kt
- android/app/src/main/java/dev/pocket/app/ui/components/ProviderBadge.kt

Replace the stub screens. Keep the composable signatures in docs/ANDROID_UI.md exactly, including parameter names and order.

The host already draws the top bar and bottom tabs. Do not add another Scaffold bottom bar.

Build a finished Material 3 phone UI, dark-first charcoal and indigo, light text, 48dp targets. Use StatusChip and ProviderBadge. Status is an icon plus a word.

Chats: filters All, Running, Needs input, Completed via repository.setSessionFilter. Rows show title, provider, project/branch, latest line, state. New-chat sheet collects project, provider, title, model, and workspace, then createSession and onOpenChat. Chat detail shows events, expandable output, changed files, approval cards with the full action and Allow/Deny, and a fixed composer. Actions: stop, resume, rename, archive, open terminal. Call repository methods. Collect flows with collectAsState.

Projects: cards with folder, branch, chats, preview. Detail has the seven navigation callbacks and a setup form that upsertProject for folder, dev command, ports, and test command.

Preview: list mappings, show VM target, phone port, URL, process state, and tunnel state separately. Buttons: start, stop, copy URL, open with Intent.ACTION_VIEW. Add mapping when the user enters a label and ports. If start returns a failed tunnel, say the local port is occupied and show the offered port.

Connections: list profiles, connect, disconnect, test, edit sheet, delete. Show ConnectResult.message and recovery. Test results are a list of DiagnosticLine. Google Cloud profiles must say that sign-in is not available until a redacted ProxyCommand is supplied. Do not pretend Google login works.

Empty and error states name the next action. No lorem ipsum. Do not edit navigation, theme, models, or the repository. Do not git commit.

Write docs/reports/shell.md with the screens you finished and anything you could not compile.
