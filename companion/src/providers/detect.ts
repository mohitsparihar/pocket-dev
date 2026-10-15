import { execSync } from 'node:child_process';

export interface ProviderInfo {
  installed: boolean;
  version?: string;
  binary?: string;
}

export interface Capabilities {
  providers: {
    claude: ProviderInfo;
    agent: ProviderInfo;  // Cursor CLI
    codex: ProviderInfo;
    terminal: ProviderInfo;
  };
  pty: boolean;
  git: boolean;
}

function detectBinary(name: string): ProviderInfo {
  try {
    const which = execSync(`which ${name} 2>/dev/null`, { encoding: 'utf8' }).trim();
    if (!which) return { installed: false };
    const version = execSync(`${name} --version 2>&1`, { encoding: 'utf8', timeout: 5000 }).trim();
    return { installed: true, version, binary: which };
  } catch {
    return { installed: false };
  }
}

function detectCursor(): ProviderInfo {
  const agent = detectBinary('agent');
  if (agent.installed) return agent;
  return detectBinary('cursor');
}

function detectPty(): boolean {
  try {
    require('node-pty');
    return true;
  } catch {
    return false;
  }
}

function detectGit(): boolean {
  try {
    execSync('git --version 2>/dev/null', { encoding: 'utf8', timeout: 3000 });
    return true;
  } catch {
    return false;
  }
}

let _capabilities: Capabilities | null = null;

export function getCapabilities(): Capabilities {
  if (_capabilities) return _capabilities;

  _capabilities = {
    providers: {
      claude: detectBinary('claude'),
      agent: detectCursor(),
      codex: detectBinary('codex'),
      terminal: { installed: true, version: 'bash' },
    },
    pty: detectPty(),
    git: detectGit(),
  };

  return _capabilities;
}

export function refreshCapabilities(): Capabilities {
  _capabilities = null;
  return getCapabilities();
}
