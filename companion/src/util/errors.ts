export type ErrorCode =
  | 'UNAUTHORIZED'
  | 'NOT_FOUND'
  | 'REMOTE_CHANGED'
  | 'CONFLICT'
  | 'PATH_ESCAPE'
  | 'PERMISSION'
  | 'VALIDATION'
  | 'BUSY'
  | 'UNSUPPORTED'
  | 'IDEMPOTENT_REPLAY';

export interface ApiError {
  error: {
    code: ErrorCode;
    message: string;
    recoverable: boolean;
    details: Record<string, unknown>;
  };
}

export class HttpError extends Error {
  constructor(
    public statusCode: number,
    public body: ApiError
  ) {
    super(body.error.message);
  }
}

export function apiError(
  code: ErrorCode,
  message: string,
  recoverable = false,
  details: Record<string, unknown> = {}
): ApiError {
  return { error: { code, message, recoverable, details } };
}

export function throwHttp(
  statusCode: number,
  code: ErrorCode,
  message: string,
  recoverable = false,
  details: Record<string, unknown> = {}
): never {
  throw new HttpError(statusCode, apiError(code, message, recoverable, details));
}
