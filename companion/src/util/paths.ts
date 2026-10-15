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

  // Check for symlinks that escape (resolve both sides to handle /tmp → /private/tmp on macOS)
  try {
    const stat = fs.lstatSync(resolved);
    if (stat.isSymbolicLink()) {
      let real: string;
      try {
        real = fs.realpathSync(resolved);
      } catch {
        throwHttp(400, 'PATH_ESCAPE', 'Cannot resolve symlink target');
      }
      let realRoot: string;
      try {
        realRoot = fs.realpathSync(projectRoot);
      } catch {
        realRoot = projectRoot;
      }
      const realRootSep = realRoot.endsWith(path.sep) ? realRoot : realRoot + path.sep;
      if (real !== realRoot && !real.startsWith(realRootSep)) {
        throwHttp(400, 'PATH_ESCAPE', 'Symlink target escapes project root');
      }
    }
  } catch (e: unknown) {
    if (e instanceof Error && 'code' in e && (e as NodeJS.ErrnoException).code === 'ENOENT') {
      // File doesn't exist yet — allowed for write operations
      return resolved;
    }
    throw e;
  }

  return resolved;
}

export function assertInsideRoot(projectRoot: string, resolved: string): void {
  const rootWithSep = projectRoot.endsWith(path.sep) ? projectRoot : projectRoot + path.sep;
  if (resolved !== projectRoot && !resolved.startsWith(rootWithSep)) {
    throwHttp(400, 'PATH_ESCAPE', 'Path escapes project root');
  }
}
