import { execFileSync } from 'node:child_process';

/**
 * Whether a `ps -o pgid=,command=` line is an agent process this companion
 * started: it leads its own process group (agents are spawned detached) and
 * carries the flags the companion passes. Guards against a reused pid.
 */
export function looksLikeOurAgent(psLine: string, pid: number): boolean {
  const match = /^(\d+)\s+(.*)$/.exec(psLine.trim());
  if (!match || Number(match[1]) !== pid) return false;
  return /stream-json|\bexec\b.*--json|bash --login/.test(match[2]);
}

/** When the process started, as `ps` reports it; identifies it across pid reuse. */
export function processStart(pid: number): string | null {
  try {
    return execFileSync('ps', ['-o', 'lstart=', '-p', String(pid)], { encoding: 'utf8' }).trim() || null;
  } catch {
    return null;
  }
}

function isOurs(pid: number, expectedStart: string | null): boolean {
  let line = '';
  try {
    line = execFileSync('ps', ['-o', 'pgid=,command=', '-p', String(pid)], { encoding: 'utf8' });
  } catch {
    return false;
  }
  if (!looksLikeOurAgent(line, pid)) return false;
  // With a recorded start time the process must be the very one we spawned.
  return expectedStart === null || processStart(pid) === expectedStart;
}

/**
 * Stops an agent left over from a previous companion. Its output pipe died
 * with that companion, so it can't be reattached, and leaving it running
 * would put two agents in one workspace after the next prompt.
 */
export function stopOrphan(pid: number, expectedStart: string | null = null): boolean {
  if (!isOurs(pid, expectedStart)) return false;
  const started = processStart(pid);
  try {
    process.kill(-pid, 'SIGTERM');
  } catch {
    return false;
  }
  setTimeout(() => {
    // Check again: the pid could have been reused in the meantime.
    if (started === null || processStart(pid) !== started) return;
    try {
      process.kill(-pid, 'SIGKILL');
    } catch { /* already gone */ }
  }, 3_000).unref();
  return true;
}
