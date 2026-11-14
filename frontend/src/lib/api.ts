// Thin fetch wrapper. Every failure becomes an ApiError carrying the API's own
// errorCode and message, so screens can show the server's explanation verbatim.

export interface FieldViolation {
  field: string;
  message: string;
}

export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly violations: FieldViolation[];

  constructor(status: number, code: string, message: string, violations: FieldViolation[] = []) {
    super(message);
    this.name = "ApiError";
    this.status = status;
    this.code = code;
    this.violations = violations;
  }
}

export type QueryParams = Record<string, string | number | boolean | null | undefined>;

/** Builds "?a=1&b=2", leaving out empty values so optional filters stay optional. */
export function qs(params: QueryParams = {}): string {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined && value !== null && value !== "") search.set(key, String(value));
  }
  const text = search.toString();
  return text ? `?${text}` : "";
}

const BASE = "/api/v1";

async function request<T>(method: string, path: string, body?: unknown, headers: Record<string, string> = {}): Promise<T> {
  let response: Response;
  try {
    response = await fetch(BASE + path, {
      method,
      headers: {
        Accept: "application/json",
        ...(body !== undefined ? { "Content-Type": "application/json" } : {}),
        ...headers,
      },
      body: body !== undefined ? JSON.stringify(body) : undefined,
    });
  } catch {
    throw new ApiError(0, "NETWORK_ERROR", "Cannot reach the PayFlow Lab API. Is the backend running?");
  }

  const text = await response.text();
  let payload: unknown = null;
  if (text) {
    try {
      payload = JSON.parse(text);
    } catch {
      payload = null;
    }
  }

  if (!response.ok) {
    const error = (payload ?? {}) as { errorCode?: string; message?: string; validationErrors?: FieldViolation[] };
    throw new ApiError(
      response.status,
      error.errorCode ?? "HTTP_" + response.status,
      error.message ?? (response.status >= 500 ? "The API is unavailable. Is the backend running?" : "Request failed"),
      error.validationErrors ?? [],
    );
  }
  return payload as T;
}

export const api = {
  get: <T>(path: string, params?: QueryParams) => request<T>("GET", path + qs(params)),
  post: <T>(path: string, body?: unknown, idempotencyKey?: string) =>
    request<T>("POST", path, body, idempotencyKey ? { "Idempotency-Key": idempotencyKey } : {}),
  put: <T>(path: string, body: unknown) => request<T>("PUT", path, body),
};

/** A fresh key per user intent. Reusing it for a retry of the same action is what makes the retry safe. */
export function newIdempotencyKey(): string {
  return "ui-" + crypto.randomUUID();
}

export function errorMessage(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.violations.length > 0) {
      return error.violations.map((v) => `${v.field}: ${v.message}`).join("; ");
    }
    return error.message;
  }
  return error instanceof Error ? error.message : "Something went wrong";
}
