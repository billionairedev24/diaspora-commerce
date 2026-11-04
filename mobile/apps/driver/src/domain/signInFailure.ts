/* What went wrong in a sign-in step, from the BFF's problem (contracts/openapi/gateway-bff.yaml, native sign-in):
   wrong password 401 INVALID_CREDENTIALS, wrong or expired code 422 INVALID_CODE, rate limit 429 RATE_LIMITED (with a
   Retry-After header when the server says how long), expired challenge 410 CHALLENGE_EXPIRED, 403 for an account that
   cannot sign in (its detail is already plain), 5xx/503 when identity is down. The screens translate the kind; the
   server's counts are never guessed (the API reports no attempts-left number). */
import { ApiProblemError, NetworkError } from '@/api/http';

export type FailureField = 'email' | 'password' | 'code';

export type SignInFailure =
  | { kind: 'credentials' }
  | { kind: 'invalid-code' }
  | { kind: 'field-required'; field: FailureField }
  | { kind: 'rate-limited'; retryAfterSeconds: number | null }
  | { kind: 'expired' }
  | { kind: 'refused'; detail: string }
  | { kind: 'offline' }
  | { kind: 'unavailable' }
  | { kind: 'unknown' };

/** Step the failure came from: a 401 on the password step is a wrong password, a 422 on the code step a wrong code. */
export type SignInStage = 'password' | 'code';

function requiredField(e: ApiProblemError): FailureField | null {
  const errors = e.body.errors;
  if (!errors || typeof errors !== 'object') return null;
  const keys = Object.keys(errors);
  return (['email', 'password', 'code'] as const).find((f) => keys.includes(f)) ?? null;
}

export function classifySignInFailure(e: unknown, stage: SignInStage): SignInFailure {
  if (e instanceof NetworkError || e instanceof TypeError) return { kind: 'offline' };
  if (e instanceof SyntaxError) return { kind: 'unavailable' };
  if (!(e instanceof ApiProblemError)) return { kind: 'unknown' };
  if (e.status === 410 || e.code === 'CHALLENGE_EXPIRED') return { kind: 'expired' };
  if (e.status === 429 || e.code === 'RATE_LIMITED') return { kind: 'rate-limited', retryAfterSeconds: e.retryAfterSeconds ?? null };
  if (e.code === 'INVALID_CREDENTIALS' || (e.status === 401 && stage === 'password')) return { kind: 'credentials' };
  if (e.code === 'INVALID_CODE' || (stage === 'code' && (e.status === 401 || e.status === 422))) return { kind: 'invalid-code' };
  if (e.status === 422) {
    const field = requiredField(e);
    return field ? { kind: 'field-required', field } : { kind: 'unknown' };
  }
  if (e.status === 403) return { kind: 'refused', detail: e.message };
  if (e.status >= 500) return { kind: 'unavailable' };
  return { kind: 'unknown' };
}

/** Which input the failure belongs to (for the red border). */
export function failureField(f: SignInFailure): FailureField | null {
  if (f.kind === 'credentials') return 'password';
  if (f.kind === 'invalid-code') return 'code';
  if (f.kind === 'field-required') return f.field;
  return null;
}
