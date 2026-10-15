import * as fs from 'node:fs';
import * as path from 'node:path';
import { throwHttp } from './errors.js';

export function safePath(projectRoot: string, userPath: string): string {
  const normalized = path.normalize(userPath);

  // Block explicit .. traversal before resolving
  if (normalized.startsWith('..') || normalized.includes('/../') || normalized.includes('\\..' + path.sep)) {
    throwHttp(400, 'PATH_ESCAPE', 'Path escapes project root');
  }

  const resolved = path.resolve(projectRoot, userPath);
  const rootWithSep = projectRoot.endsWith(path.sep) ? projectRoot : projectRoot + path.sep;

  if (resolved !== projectRoot && !resolved.startsWith(rootWithSep)) {
    throwHttp(400, 'PATH_ESCAPE', 'Path escapes project root');
  }

  // Resolve the deepest part of the path that exists, so a symlinked parent
  // folder (project/link/file with link -> /etc) can't lead outside the root.
  // Resolving both sides also handles /tmp -> /private/tmp on macOS.
  const realRoot = realpathOr(projectRoot);
  let existing = resolved;
  const rest: string[] = [];
  for (;;) {
    try {
      fs.lstatSync(existing);
      break;
    } catch {
      const parent = path.dirname(existing);
      if (parent === existing) break;
      rest.unshift(path.basename(existing));
      existing = parent;
    }
  }
  let real: string;
  try {
    real = fs.realpathSync(existing);
  } catch {
    throwHttp(400, 'PATH_ESCAPE', 'Cannot resolve symlink target');
  }
  if (!isInside(realRoot, path.join(real, ...rest))) {
    throwHttp(400, 'PATH_ESCAPE', 'Path escapes project root');
  }

  return resolved;
}

function realpathOr(p: string): string {
  try {
    return fs.realpathSync(p);
  } catch {
    return p;
  }
}

function isInside(root: string, candidate: string): boolean {
  const rootWithSep = root.endsWith(path.sep) ? root : root + path.sep;
  return candidate === root || candidate.startsWith(rootWithSep);
}

/** Deleting, moving or renaming the project folder itself is never what the phone meant. */
export function assertNotRoot(projectRoot: string, resolved: string): void {
  if (path.resolve(resolved) === path.resolve(projectRoot) || realpathOr(resolved) === realpathOr(projectRoot)) {
    throwHttp(400, 'VALIDATION', 'The project folder itself cannot be changed this way');
  }
}

export function assertInsideRoot(projectRoot: string, resolved: string): void {
  const rootWithSep = projectRoot.endsWith(path.sep) ? projectRoot : projectRoot + path.sep;
  if (resolved !== projectRoot && !resolved.startsWith(rootWithSep)) {
    throwHttp(400, 'PATH_ESCAPE', 'Path escapes project root');
  }
}
