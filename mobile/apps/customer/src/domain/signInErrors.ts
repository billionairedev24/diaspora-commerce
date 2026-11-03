/* What a failed sign-in step means for the form (pure; unit-tested). The BFF answers RFC 9457 problems:
   401 INVALID_CREDENTIALS (wrong email or password), 422 field errors (email, password, code) and INVALID_CODE,
   429 RATE_LIMITED with a Retry-After header (per-email throttle or identity's lockout; http.ts keeps the header as
   `retryAfterSeconds` on the problem), 410 CHALLENGE_EXPIRED (start again), 403 (email not confirmed, disabled).
   The server does not say how many attempts are left, so none is shown. */
import { ApiError } from '@afrotransact/api-client';

export type SignInField = 'email' | 'password' | 'code';

export type SignInFailure =
  /** Wrong email or password: both fields are marked, the message sits under the password. */
  | { kind: 'credentials'; fields: SignInField[] }
  /** Wrong or expired code. */
  | { kind: 'invalid-code'; fields: SignInField[] }
  /** Too many attempts: `retryAfterSeconds` when the server sent it. */
  | { kind: 'rate-limited'; fields: SignInField[]; message: string; retryAfterSeconds?: number }
  /** The attempt expired: back to the password step. */
  | { kind: 'expired'; fields: SignInField[]; message: string }
  | { kind: 'network'; fields: SignInField[] }
  /** The server's own wording for a field (validation) or the whole form (403, 5xx). */
  | { kind: 'server'; fields: SignInField[]; message: string }
  | { kind: 'unknown'; fields: SignInField[] };

const RE_EMAIL = /^[^\s@]+@[^\s@]+\.[a-z]{2,}$/i;

export function isValidEmail(value: string): boolean {
  return RE_EMAIL.test(value.trim());
}

/** Seconds from the Retry-After header (delta-seconds form); undefined when absent or not a positive number. */
export function parseRetryAfter(header: string | null | undefined): number | undefined {
  if (!header) return undefined;
  const n = Number(header.trim());
  return Number.isFinite(n) && n > 0 ? Math.ceil(n) : undefined;
}

export function retryAfterOf(e: ApiError): number | undefined {
  const v = e.problem.retryAfterSeconds;
  return typeof v === 'number' && v > 0 ? Math.ceil(v) : undefined;
}

const FIELDS: SignInField[] = ['email', 'password', 'code'];

export function describeSignInError(e: unknown): SignInFailure {
  if (e instanceof ApiError) {
    if (e.status === 410 || e.code === 'CHALLENGE_EXPIRED') return { kind: 'expired', fields: [], message: e.message };
    if (e.status === 429 || e.code === 'RATE_LIMITED') {
      const retryAfterSeconds = retryAfterOf(e);
      return { kind: 'rate-limited', fields: [], message: e.message, ...(retryAfterSeconds ? { retryAfterSeconds } : {}) };
    }
    if (e.code === 'INVALID_CREDENTIALS') return { kind: 'credentials', fields: ['email', 'password'] };
    if (e.code === 'INVALID_CODE') return { kind: 'invalid-code', fields: ['code'] };
    const fe = e.fieldErrors;
    const field = FIELDS.find((f) => fe[f]);
    if (field) return { kind: 'server', fields: [field], message: fe[field] ?? e.message };
    return { kind: 'server', fields: [], message: e.message };
  }
  if (e instanceof TypeError) return { kind: 'network', fields: [] };
  return { kind: 'unknown', fields: [] };
}

/** "15 min" / "45 s" / "2 h" as a unit and amount, for the catalogue to word. */
export function retryWait(seconds: number): { unit: 'seconds' | 'minutes' | 'hours'; n: number } {
  if (seconds < 60) return { unit: 'seconds', n: Math.max(1, Math.ceil(seconds)) };
  if (seconds < 3600) return { unit: 'minutes', n: Math.ceil(seconds / 60) };
  return { unit: 'hours', n: Math.ceil(seconds / 3600) };
}
