# Pocket Dev

Personal Android workspace for VPS coding. The product brief is `Pocket_Dev_App_Plan.md`.

- `android/` is the Jetpack Compose app.
- `companion/` is the loopback Node service that runs on the VM.
- `docs/PROTOCOL.md` is the companion contract.

The phone opens in a local demo workspace so the UI can be used before a VM is configured. Direct SSH is the real transport. Google Cloud login is not connected until a redacted SSH config is provided.

## Commands

Run these from the project root. They need Node 22.5+, and the phone commands need the phone plugged in by USB with USB debugging on.

| Command | What it does |
|---|---|
| `npm run apk` | Builds the debug APK and copies it to `dist/pocket-dev-debug.apk` |
| `npm run phone` | Builds, installs on the connected phone, and opens the app (keeps your connections and keys) |
| `npm run phone:install` | Installs the last build without rebuilding |
| `npm run phone:launch` | Opens the app on the phone |
| `npm run phone:logs` | Streams the app's logs (Ctrl+C to stop) |
| `npm run phone:crashes` | Shows the app's recent crashes from the phone's crash log |
| `npm run phone:uninstall` | Removes the app and its data from the phone |
| `npm run devices` | Lists connected phones |
| `npm test` | Runs the companion and Android unit tests |
| `npm run companion:build` / `companion:start` | Builds or starts the companion (on the VM) |
| `npm run clean` | Deletes build output |

With more than one phone or emulator connected, pick one: `ANDROID_SERIAL=<id> npm run phone` (ids come from `npm run devices`). The scripts find `adb` through `ANDROID_HOME`, `android/local.properties`, or the default SDK location.
