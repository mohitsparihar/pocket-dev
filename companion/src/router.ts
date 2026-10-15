import * as http from 'node:http';
import * as url from 'node:url';
import { HttpError, apiError } from './util/errors.js';

export type Params = Record<string, string>;
export type Query = Record<string, string>;
export type Handler = (
  req: http.IncomingMessage,
  res: http.ServerResponse,
  params: Params,
  query: Query
) => Promise<void> | void;

interface Route {
  method: string;
  regex: RegExp;
  paramNames: string[];
  handler: Handler;
}

const routes: Route[] = [];

function pathToRegex(pattern: string): { regex: RegExp; paramNames: string[] } {
  const paramNames: string[] = [];
  const regexStr = pattern
    .replace(/:[a-zA-Z_]+/g, (match) => {
      paramNames.push(match.slice(1));
      return '([^/]+)';
    })
    .replace(/\//g, '\\/');
  return { regex: new RegExp(`^${regexStr}$`), paramNames };
}

export function register(method: string, pattern: string, handler: Handler): void {
  const { regex, paramNames } = pathToRegex(pattern);
  routes.push({ method: method.toUpperCase(), regex, paramNames, handler });
}

export function send(
  res: http.ServerResponse,
  statusCode: number,
  body: unknown
): void {
  const json = JSON.stringify(body);
  res.writeHead(statusCode, {
    'Content-Type': 'application/json',
    'Content-Length': Buffer.byteLength(json),
  });
  res.end(json);
}

export async function dispatch(
  req: http.IncomingMessage,
  res: http.ServerResponse
): Promise<void> {
  const parsed = url.parse(req.url ?? '/', true);
  const pathname = parsed.pathname ?? '/';
  const query: Query = {};
  for (const [k, v] of Object.entries(parsed.query)) {
    if (typeof v === 'string') query[k] = v;
    else if (Array.isArray(v) && v.length > 0 && typeof v[0] === 'string') query[k] = v[0];
  }

  const method = (req.method ?? 'GET').toUpperCase();

  for (const route of routes) {
    if (route.method !== method) continue;
    const match = route.regex.exec(pathname);
    if (!match) continue;

    const params: Params = {};
    route.paramNames.forEach((name, i) => {
      params[name] = decodeURIComponent(match[i + 1]);
    });

    try {
      await route.handler(req, res, params, query);
    } catch (err) {
      if (err instanceof HttpError) {
        send(res, err.statusCode, err.body);
      } else {
        const msg = err instanceof Error ? err.message : String(err);
        send(res, 500, apiError('VALIDATION', `Internal error: ${msg}`));
      }
    }
    return;
  }

  send(res, 404, apiError('NOT_FOUND', `${method} ${pathname} not found`));
}
