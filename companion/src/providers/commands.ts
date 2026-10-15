import * as fs from 'node:fs';
import * as os from 'node:os';
import * as path from 'node:path';

export interface CommandInfo {
  name: string;
  description: string;
  argumentHint: string;
  source: 'built-in' | 'project' | 'user' | 'plugin' | 'skill';
}

interface CommandFile extends CommandInfo {
  file: string;
}

// The providers run headless (claude -p, agent -p, codex exec), where only
// some slash commands exist. These are the Claude built-ins that work there;
// the authoritative list arrives in Claude's init message once a session runs.
const CLAUDE_BUILTINS: Record<string, string> = {
  clear: 'Start a fresh conversation',
  compact: 'Summarize the conversation to free up context',
  context: 'Show what is using the context window',
  init: 'Create a CLAUDE.md guide for this project',
  review: 'Review a pull request',
  'security-review': 'Review pending changes for security issues',
  usage: 'Show plan usage limits',
  insights: 'Summarize how you have been using Claude Code',
  'reload-skills': 'Reload skills from disk',
};

const CODEX_BUILTINS: Record<string, CommandInfo> = {
  review: {
    name: 'review',
    description: 'Code review of uncommitted changes (or --base <branch>, --commit <sha>)',
    argumentHint: '[--base <branch> | --commit <sha>] [instructions]',
    source: 'built-in',
  },
};

export function parseFrontmatter(text: string): { meta: Record<string, string>; body: string } {
  const meta: Record<string, string> = {};
  if (!text.startsWith('---')) return { meta, body: text };
  const end = text.indexOf('\n---', 3);
  if (end < 0) return { meta, body: text };
  for (const line of text.slice(3, end).split('\n')) {
    const match = /^([A-Za-z][\w-]*):\s*(.*)$/.exec(line.trim());
    if (match) meta[match[1]] = match[2].replace(/^['"]|['"]$/g, '').trim();
  }
  return { meta, body: text.slice(text.indexOf('\n', end + 1) + 1) };
}

function firstLine(body: string): string {
  const line = body
    .split('\n')
    .map((l) => l.replace(/^#+\s*/, '').trim())
    .find((l) => l.length > 0) ?? '';
  return line.length > 100 ? line.slice(0, 99) + '…' : line;
}

/** `<dir>/a.md` → `a`, `<dir>/sub/b.md` → `sub:b`, the way the CLIs name nested commands. */
function scanCommandDir(dir: string, source: CommandInfo['source'], prefix = ''): CommandFile[] {
  const out: CommandFile[] = [];
  const walk = (current: string, parts: string[]) => {
    let entries: fs.Dirent[];
    try {
      entries = fs.readdirSync(current, { withFileTypes: true });
    } catch {
      return;
    }
    for (const entry of entries) {
      const full = path.join(current, entry.name);
      if (entry.isDirectory() && parts.length < 2) walk(full, [...parts, entry.name]);
      else if (entry.isFile() && entry.name.endsWith('.md')) {
        const text = safeRead(full);
        if (text === null) continue;
        const { meta, body } = parseFrontmatter(text);
        out.push({
          name: prefix + [...parts, entry.name.slice(0, -3)].join(':'),
          description: meta.description || firstLine(body),
          argumentHint: meta['argument-hint'] ?? '',
          source,
          file: full,
        });
      }
    }
  };
  walk(dir, []);
  return out;
}

function scanSkillDir(dir: string, source: CommandInfo['source'], prefix = ''): CommandFile[] {
  const out: CommandFile[] = [];
  let entries: fs.Dirent[];
  try {
    entries = fs.readdirSync(dir, { withFileTypes: true });
  } catch {
    return out;
  }
  for (const entry of entries) {
    if (!entry.isDirectory()) continue;
    const file = path.join(dir, entry.name, 'SKILL.md');
    const text = safeRead(file);
    if (text === null) continue;
    const { meta, body } = parseFrontmatter(text);
    out.push({
      name: prefix + (meta.name || entry.name),
      description: meta.description || firstLine(body),
      argumentHint: meta['argument-hint'] ?? '',
      source,
      file,
    });
  }
  return out;
}

function safeRead(file: string): string | null {
  try {
    const stat = fs.statSync(file);
    if (stat.size > 256 * 1024) return null;
    return fs.readFileSync(file, 'utf8');
  } catch {
    return null;
  }
}

function claudeHome(): string {
  return process.env.CLAUDE_CONFIG_DIR || path.join(os.homedir(), '.claude');
}

function codexHome(): string {
  return process.env.CODEX_HOME || path.join(os.homedir(), '.codex');
}

function claudePluginCommands(projectRoot: string): CommandFile[] {
  const manifest = safeRead(path.join(claudeHome(), 'plugins', 'installed_plugins.json'));
  if (!manifest) return [];
  let parsed: { plugins?: Record<string, Array<{ scope?: string; projectPath?: string; installPath?: string }>> };
  try {
    parsed = JSON.parse(manifest);
  } catch {
    return [];
  }
  const out: CommandFile[] = [];
  for (const [key, installs] of Object.entries(parsed.plugins ?? {})) {
    const plugin = key.split('@')[0];
    const install = installs.find((i) => i.scope !== 'project' || i.projectPath === projectRoot);
    if (!install?.installPath) continue;
    out.push(...scanCommandDir(path.join(install.installPath, 'commands'), 'plugin', `${plugin}:`));
    out.push(...scanSkillDir(path.join(install.installPath, 'skills'), 'plugin', `${plugin}:`));
  }
  return out;
}

function commandFiles(provider: string, roots: string[]): CommandFile[] {
  const unique = [...new Set(roots.filter(Boolean))];
  switch (provider) {
    case 'claude':
      return [
        ...unique.flatMap((root) => scanCommandDir(path.join(root, '.claude', 'commands'), 'project')),
        ...unique.flatMap((root) => scanSkillDir(path.join(root, '.claude', 'skills'), 'skill')),
        ...scanCommandDir(path.join(claudeHome(), 'commands'), 'user'),
        ...scanSkillDir(path.join(claudeHome(), 'skills'), 'skill'),
        ...claudePluginCommands(unique[0] ?? ''),
      ];
    case 'agent':
      return [
        ...unique.flatMap((root) => scanCommandDir(path.join(root, '.cursor', 'commands'), 'project')),
        ...scanCommandDir(path.join(os.homedir(), '.cursor', 'commands'), 'user'),
      ];
    case 'codex':
      // Codex invokes custom prompts as /prompts:<name>.
      return scanCommandDir(path.join(codexHome(), 'prompts'), 'user', 'prompts:');
    default:
      return [];
  }
}

/**
 * Commands a chat can use. For Claude, `reported` is the slash_commands list
 * from its init message, which already accounts for enabled plugins and
 * skills; files are only used to describe them.
 */
export function listCommands(provider: string, roots: string[], reported: string[] | null): CommandInfo[] {
  const files = commandFiles(provider, roots);
  const strip = ({ file: _file, ...info }: CommandFile): CommandInfo => info;

  if (provider === 'claude') {
    const described = new Map(files.map((f) => [f.name, strip(f)]));
    const names = reported ?? [...Object.keys(CLAUDE_BUILTINS), ...files.map((f) => f.name)];
    const seen = new Set<string>();
    const out: CommandInfo[] = [];
    for (const name of names) {
      if (seen.has(name)) continue;
      seen.add(name);
      const known = described.get(name);
      out.push(
        known ?? {
          name,
          description: CLAUDE_BUILTINS[name] ?? '',
          argumentHint: '',
          source: name in CLAUDE_BUILTINS ? 'built-in' : name.includes(':') ? 'plugin' : 'skill',
        }
      );
    }
    return out;
  }

  const builtins = provider === 'codex' ? Object.values(CODEX_BUILTINS) : [];
  return [...builtins, ...files.map(strip)];
}

export interface Expansion {
  /** Text to send to the provider. */
  prompt: string;
  /** Codex `/review`: run `codex exec review` with these extra arguments. */
  review?: string[];
}

/**
 * Cursor and Codex do not run slash commands headless, so the companion
 * expands custom command files itself. Returns null when the text is not a
 * known command, in which case it is sent unchanged.
 */
export function expandCommand(provider: string, text: string, roots: string[]): Expansion | null {
  const match = /^\/([\w.:-]+)(?:\s+([\s\S]*))?$/.exec(text.trim());
  if (!match || provider === 'claude') return null;
  const [, name, rawArgs = ''] = match;
  const args = rawArgs.trim();

  if (provider === 'codex' && name === 'review') {
    const extra: string[] = [];
    const words = args.split(/\s+/).filter(Boolean);
    let i = 0;
    while (i < words.length && /^--(base|commit|title)$/.test(words[i]) && words[i + 1]) {
      extra.push(words[i], words[i + 1]);
      i += 2;
    }
    if (!extra.some((w) => w === '--base' || w === '--commit')) extra.unshift('--uncommitted');
    const instructions = words.slice(i).join(' ');
    if (instructions) extra.push(instructions);
    return { prompt: text, review: extra };
  }

  const file = commandFiles(provider, roots).find((f) => f.name === name);
  if (!file) return null;
  const { body } = parseFrontmatter(safeRead(file.file) ?? '');
  return { prompt: fillArguments(body.trim(), args) };
}

export function fillArguments(body: string, args: string): string {
  const positional = args.split(/\s+/).filter(Boolean);
  let used = false;
  const filled = body
    .replace(/\$ARGUMENTS/g, () => {
      used = true;
      return args;
    })
    .replace(/\$([1-9])/g, (_, n: string) => {
      used = true;
      return positional[Number(n) - 1] ?? '';
    });
  if (!used && args) return `${filled}\n\n${args}`;
  return filled;
}
