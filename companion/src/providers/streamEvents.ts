export interface ProviderEvent {
  // 'delta' is a live text fragment; it is broadcast but never stored.
  // 'done' marks the end of a turn so the session can go back to awaiting input.
  type: 'assistant' | 'thinking' | 'tool' | 'error' | 'status' | 'delta' | 'done';
  text: string;
  model?: string;
  providerSessionId?: string;
  /** Slash commands Claude reports as available in this session. */
  commands?: string[];
  /** Environment details from Claude's init message, for /status. */
  info?: ProviderInfo;
  /** Usage reported at the end of a turn. */
  usage?: TurnUsage;
}

export interface ProviderInfo {
  version: string;
  cwd: string;
  permissionMode: string;
  outputStyle: string;
  mcpServers: Array<{ name: string; status: string }>;
  plugins: string[];
  tools: number;
  skills: number;
  agents: number;
}

export interface TurnUsage {
  /** Claude reports a running total for the process, not per turn. */
  processCostUsd?: number;
  inputTokens: number;
  outputTokens: number;
  /** Tokens in the context window after this turn. */
  contextTokens?: number;
  contextWindow?: number;
}

export function cursorModelId(model: string, effort: string): string {
  if (!model) return '';
  const token = effort === 'max' ? 'xhigh' : effort;
  if (!token) return model;
  if (!/^(low|medium|high|xhigh)$/.test(token)) return model;
  return model.replace(/-(low|medium|high|xhigh)(?=-fast$|$)/, `-${token}`);
}

export function eventsFromProviderLine(line: string): ProviderEvent[] {
  const trimmed = line.trim();
  if (!trimmed.startsWith('{')) return [];
  let msg: Record<string, unknown>;
  try {
    msg = JSON.parse(trimmed) as Record<string, unknown>;
  } catch {
    return [];
  }

  if (msg.type === 'stream_event' && msg.event && typeof msg.event === 'object') {
    const event = msg.event as Record<string, unknown>;
    const delta = event.delta as Record<string, unknown> | undefined;
    if (event.type === 'content_block_delta' && delta?.type === 'text_delta' && typeof delta.text === 'string') {
      return delta.text ? [{ type: 'delta', text: delta.text }] : [];
    }
    return [];
  }

  if (msg.type === 'thread.started' && typeof msg.thread_id === 'string') {
    return [{ type: 'status', text: '', providerSessionId: msg.thread_id }];
  }

  if (msg.type === 'item.completed' && msg.item && typeof msg.item === 'object') {
    return codexItem(msg.item as Record<string, unknown>);
  }

  if (msg.type === 'error') {
    const text = stringField(msg, 'message') || stringField(msg, 'error');
    return text ? [{ type: 'error', text }] : [];
  }

  if (msg.type === 'system') {
    if (msg.subtype && msg.subtype !== 'init') return [];
    const model = stringField(msg, 'model');
    const providerSessionId = stringField(msg, 'session_id');
    const commands = Array.isArray(msg.slash_commands)
      ? msg.slash_commands.filter((c): c is string => typeof c === 'string')
      : undefined;
    if (!model && !providerSessionId && !commands) return [];
    return [{
      type: 'status',
      text: model ? `Model ${shortModel(model)}` : '',
      model,
      providerSessionId,
      commands,
      info: initInfo(msg),
    }];
  }

  if (msg.type === 'assistant' || msg.type === 'message') {
    const message = msg.message;
    if (message && typeof message === 'object') {
      return blocksFromContent((message as Record<string, unknown>).content);
    }
    return blocksFromContent(msg.content);
  }

  if (msg.type === 'tool_call' || msg.type === 'tool_use') {
    const name = stringField(msg, 'name') || stringField(msg, 'tool') || 'tool';
    return [{ type: 'tool', text: toolLabel(name, msg.input ?? msg.arguments) }];
  }

  if (msg.type === 'result') {
    const failed = msg.is_error === true || (typeof msg.subtype === 'string' && msg.subtype.startsWith('error'));
    if (failed) {
      const text = stringField(msg, 'result') || stringField(msg, 'error') || 'The agent stopped with an error';
      return [{ type: 'error', text }, { type: 'done', text: '' }];
    }
    const usage = claudeUsage(msg);
    return [{ type: 'done', text: turnSummary(msg), ...(usage ? { usage } : {}) }];
  }

  if (msg.type === 'turn.completed') {
    const usage = msg.usage as Record<string, unknown> | undefined;
    return [{
      type: 'done',
      text: '',
      ...(usage ? { usage: { inputTokens: num(usage.input_tokens), outputTokens: num(usage.output_tokens) } } : {}),
    }];
  }

  return [];
}

function codexItem(item: Record<string, unknown>): ProviderEvent[] {
  const kind = stringField(item, 'type');
  if (kind === 'agent_message') {
    const text = stringField(item, 'text');
    return text ? [{ type: 'assistant', text }] : [];
  }
  if (kind === 'reasoning' || kind === 'thinking') {
    const text = stringField(item, 'text') || stringField(item, 'summary');
    return text ? [{ type: 'thinking', text }] : [];
  }
  if (kind === 'command_execution' || kind === 'function_call') {
    const text = stringField(item, 'command') || stringField(item, 'name') || 'command';
    return [{ type: 'tool', text }];
  }
  return [];
}

function blocksFromContent(content: unknown): ProviderEvent[] {
  if (typeof content === 'string') {
    const text = content.trim();
    return text ? [{ type: 'assistant', text }] : [];
  }
  if (!Array.isArray(content)) return [];
  const out: ProviderEvent[] = [];
  for (const block of content) {
    if (!block || typeof block !== 'object') continue;
    const item = block as Record<string, unknown>;
    const kind = stringField(item, 'type');
    if (kind === 'thinking' || kind === 'reasoning' || kind === 'redacted_thinking') {
      const text = stringField(item, 'thinking') || stringField(item, 'text');
      if (text) out.push({ type: 'thinking', text });
    } else if (kind === 'text') {
      const text = stringField(item, 'text');
      if (text) out.push({ type: 'assistant', text });
    } else if (kind === 'tool_use' || kind === 'tool_call') {
      const name = stringField(item, 'name') || 'tool';
      out.push({ type: 'tool', text: toolLabel(name, item.input) });
    }
  }
  return out;
}

function toolLabel(name: string, input: unknown): string {
  const detail = toolDetail(input);
  return detail ? `${name}  ${detail}` : name;
}

function toolDetail(input: unknown): string {
  if (!input || typeof input !== 'object') return '';
  const record = input as Record<string, unknown>;
  const raw = ['file_path', 'path', 'command', 'pattern', 'query', 'url']
    .map((key) => record[key])
    .find((value) => typeof value === 'string' && value.trim());
  if (typeof raw !== 'string') return '';
  const text = raw.trim().replace(/\s+/g, ' ');
  if (text.length > 80) return text.slice(0, 77) + '…';
  return text;
}

function num(value: unknown): number {
  return typeof value === 'number' && Number.isFinite(value) ? value : 0;
}

function initInfo(msg: Record<string, unknown>): ProviderInfo {
  const list = (value: unknown) => (Array.isArray(value) ? value : []);
  return {
    version: stringField(msg, 'claude_code_version'),
    cwd: stringField(msg, 'cwd'),
    permissionMode: stringField(msg, 'permissionMode'),
    outputStyle: stringField(msg, 'output_style'),
    mcpServers: list(msg.mcp_servers)
      .filter((s): s is Record<string, unknown> => !!s && typeof s === 'object')
      .map((s) => ({ name: String(s.name ?? ''), status: String(s.status ?? '') })),
    plugins: list(msg.plugins).map((p) =>
      p && typeof p === 'object' ? String((p as Record<string, unknown>).name ?? '') : String(p)
    ),
    tools: list(msg.tools).length,
    skills: list(msg.skills).length,
    agents: list(msg.agents).length,
  };
}

function claudeUsage(msg: Record<string, unknown>): TurnUsage | undefined {
  const usage = msg.usage as Record<string, unknown> | undefined;
  if (!usage) return undefined;
  const cached = num(usage.cache_creation_input_tokens) + num(usage.cache_read_input_tokens);
  // The last API call of the turn shows how full the context window is now.
  const iterations = Array.isArray(usage.iterations) ? (usage.iterations as Array<Record<string, unknown>>) : [];
  const last = iterations[iterations.length - 1];
  const contextTokens = last
    ? num(last.input_tokens) + num(last.cache_read_input_tokens) + num(last.cache_creation_input_tokens) + num(last.output_tokens)
    : undefined;
  const models = msg.modelUsage && typeof msg.modelUsage === 'object' ? Object.values(msg.modelUsage as Record<string, Record<string, unknown>>) : [];
  const contextWindow = models.map((m) => num(m.contextWindow)).find((n) => n > 0);
  return {
    processCostUsd: typeof msg.total_cost_usd === 'number' ? msg.total_cost_usd : undefined,
    inputTokens: num(usage.input_tokens) + cached,
    outputTokens: num(usage.output_tokens),
    contextTokens,
    contextWindow,
  };
}

function turnSummary(msg: Record<string, unknown>): string {
  const ms = typeof msg.duration_ms === 'number' ? msg.duration_ms : 0;
  if (!ms) return 'Done';
  const seconds = Math.round(ms / 1000);
  const time = seconds >= 60 ? `${Math.floor(seconds / 60)}m ${seconds % 60}s` : `${seconds}s`;
  return `Done in ${time}`;
}

function shortModel(model: string): string {
  const value = model.toLowerCase();
  if (value.includes('haiku')) return 'Haiku';
  if (value.includes('sonnet')) return 'Sonnet';
  if (value.includes('opus')) return 'Opus';
  if (value.includes('fable')) return 'Fable';
  return model.length > 32 ? model.slice(0, 32) : model;
}

function stringField(record: Record<string, unknown>, key: string): string {
  const value = record[key];
  return typeof value === 'string' ? value.trim() : '';
}
