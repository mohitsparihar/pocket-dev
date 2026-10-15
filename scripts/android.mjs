#!/usr/bin/env node
// Builds, installs and inspects the Android app. Run through npm scripts:
//   npm run apk | phone | phone:launch | phone:logs | phone:crashes | devices
// Pick a phone with ANDROID_SERIAL=<id> when more than one is connected.

import { spawnSync } from 'node:child_process';
import { copyFileSync, existsSync, mkdirSync, readFileSync } from 'node:fs';
import { homedir, platform } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const androidDir = join(root, 'android');
const apkPath = join(androidDir, 'app/build/outputs/apk/debug/app-debug.apk');
const distApk = join(root, 'dist/pocket-dev-debug.apk');
const appId = 'dev.pocket.app';
const windows = platform() === 'win32';

function fail(message) {
  console.error(`\n✖ ${message}\n`);
  process.exit(1);
}

function run(cmd, args, options = {}) {
  const result = spawnSync(cmd, args, { stdio: 'inherit', ...options });
  if (result.error) fail(`Could not run ${cmd}: ${result.error.message}`);
  if (result.status !== 0 && !options.allowFailure) process.exit(result.status ?? 1);
  return result;
}

function capture(cmd, args) {
  const result = spawnSync(cmd, args, { encoding: 'utf8' });
  if (result.error) fail(`Could not run ${cmd}: ${result.error.message}`);
  return { ok: result.status === 0, out: (result.stdout ?? '').trim(), err: (result.stderr ?? '').trim() };
}

/** The Android SDK: ANDROID_HOME, ANDROID_SDK_ROOT, android/local.properties, then the default location. */
function sdkDir() {
  const fromEnv = process.env.ANDROID_HOME || process.env.ANDROID_SDK_ROOT;
  if (fromEnv) return fromEnv;
  const props = join(androidDir, 'local.properties');
  if (existsSync(props)) {
    const line = readFileSync(props, 'utf8').split('\n').find((l) => l.startsWith('sdk.dir='));
    if (line) return line.slice('sdk.dir='.length).trim().replace(/\\:/g, ':').replace(/\\\\/g, '\\');
  }
  if (platform() === 'darwin') return join(homedir(), 'Library/Android/sdk');
  if (windows) return join(process.env.LOCALAPPDATA ?? '', 'Android/Sdk');
  return join(homedir(), 'Android/Sdk');
}

function adb() {
  const candidate = join(sdkDir(), 'platform-tools', windows ? 'adb.exe' : 'adb');
  if (existsSync(candidate)) return candidate;
  if (capture(windows ? 'where' : 'which', ['adb']).ok) return 'adb';
  fail('adb not found. Install Android platform-tools, or set ANDROID_HOME to your Android SDK.');
}

/** The phone to use: ANDROID_SERIAL, or the only connected device. */
function device() {
  const { out } = capture(adb(), ['devices', '-l']);
  const lines = out.split('\n').slice(1).filter((l) => l.trim());
  const ready = lines.filter((l) => /\sdevice(\s|$)/.test(l)).map((l) => l.split(/\s+/)[0]);
  const blocked = lines.filter((l) => /\s(unauthorized|offline)(\s|$)/.test(l));

  const wanted = process.env.ANDROID_SERIAL;
  if (wanted) {
    if (!ready.includes(wanted)) fail(`Phone ${wanted} is not connected. Connected: ${ready.join(', ') || 'none'}.`);
    return wanted;
  }
  if (ready.length === 1) return ready[0];
  if (ready.length > 1) {
    fail(`Several devices are connected (${ready.join(', ')}). Choose one: ANDROID_SERIAL=<id> npm run phone`);
  }
  if (blocked.length) {
    fail('The phone is connected but not authorised. Unlock it and accept the "Allow USB debugging" prompt.');
  }
  fail('No phone found. Connect it by USB with USB debugging on (Settings → Developer options), then try again.');
}

function buildApk() {
  console.log('▸ Building the debug APK…');
  run(join(androidDir, windows ? 'gradlew.bat' : 'gradlew'), ['assembleDebug'], { cwd: androidDir, shell: windows });
  if (!existsSync(apkPath)) fail(`The build finished but ${apkPath} is missing.`);
  mkdirSync(dirname(distApk), { recursive: true });
  copyFileSync(apkPath, distApk);
  console.log(`✔ APK ready: ${distApk}`);
}

function launch(serial) {
  run(adb(), ['-s', serial, 'shell', 'am', 'start', '-n', `${appId}/.MainActivity`], { stdio: 'ignore' });
  console.log('✔ Pocket Dev opened on the phone');
}

function install({ build }) {
  if (build) buildApk();
  else if (!existsSync(apkPath)) fail('No APK yet. Run: npm run apk');
  const serial = device();
  console.log(`▸ Installing on ${serial} (your saved connections and keys are kept)…`);
  const result = capture(adb(), ['-s', serial, 'install', '-r', apkPath]);
  if (!result.ok || !/Success/.test(result.out)) {
    const why = `${result.out}\n${result.err}`;
    if (/INSTALL_FAILED_UPDATE_INCOMPATIBLE/.test(why)) {
      fail('The installed app was signed with a different key. Uninstall it first with: npm run phone:uninstall (this deletes its data).');
    }
    fail(`Install failed:\n${why.trim()}`);
  }
  console.log('✔ Installed');
  launch(serial);
}

function pid(serial) {
  return capture(adb(), ['-s', serial, 'shell', 'pidof', appId]).out.split(/\s+/)[0] ?? '';
}

const commands = {
  build: () => buildApk(),
  install: () => install({ build: true }),
  'install-only': () => install({ build: false }),
  launch: () => launch(device()),
  devices: () => run(adb(), ['devices', '-l']),
  logs: () => {
    const serial = device();
    let id = pid(serial);
    if (!id) {
      launch(serial);
      spawnSync(process.execPath, ['-e', 'setTimeout(() => {}, 2000)']);
      id = pid(serial);
    }
    if (!id) fail('The app is not running.');
    console.log(`▸ Showing logs for Pocket Dev (pid ${id}). Ctrl+C to stop.`);
    run(adb(), ['-s', serial, 'logcat', `--pid=${id}`], { allowFailure: true });
  },
  crashes: () => {
    const serial = device();
    const { out } = capture(adb(), ['-s', serial, 'logcat', '-d', '-b', 'crash']);
    // Each crash starts at a "FATAL EXCEPTION" line.
    const blocks = [];
    for (const line of out.split('\n')) {
      if (line.includes('FATAL EXCEPTION') || blocks.length === 0) blocks.push(line);
      else blocks[blocks.length - 1] += `\n${line}`;
    }
    blocks.splice(0, blocks.length, ...blocks.filter((b) => b.includes(`Process: ${appId}`)));
    if (!blocks.length) {
      console.log('✔ No Pocket Dev crashes in the phone\'s crash log.');
      return;
    }
    console.log(`Last ${Math.min(blocks.length, 3)} of ${blocks.length} Pocket Dev crashes:\n`);
    for (const block of blocks.slice(-3)) console.log(block.split('\n').slice(0, 12).join('\n') + '\n');
  },
  uninstall: () => run(adb(), ['-s', device(), 'uninstall', appId]),
};

const name = process.argv[2];
if (!commands[name]) fail(`Unknown command "${name}". Use one of: ${Object.keys(commands).join(', ')}`);
commands[name]();
