import { type ApiClientOptions, createTransport } from './http';
import { MeSchema } from './schemas/auth';
import { type LoadedSessionPolicy, type SessionEndReason, SessionPolicySchema } from './schemas/session';

/** Epoch ms of a response's `Date` header (whole seconds), or null when it has none. */
export function serverTimeOf(res: Response): number | null {
  const raw = res.headers.get('Date');
  if (!raw) return null;
  const at = Date.parse(raw);
  return Number.isNaN(at) ? null : at;
}

/** Why the person was signed out, as /v1/me tells it for ten minutes after the sign-out policy ended the session. */
export interface SignedOutState {
  authenticated: boolean;
  reason: SessionEndReason | null;
}

/**
 * The web session timer's calls (docs/engineering/session-timeouts.md). Reading the policy and /v1/me here never counts as
 * activity (they send X-Background); only {@link SessionApi.reportActivity} does, and it is called from real input only.
 */
export function createSessionApi(options: ApiClientOptions) {
  const send = createTransport(options);
  return {
    /** GET /v1/session/policy with the server's clock (Date header) so the timer can correct for a wrong device clock. */
    getPolicy: async (signal?: AbortSignal): Promise<LoadedSessionPolicy> => {
      const res = await send('/v1/session/policy', { signal, background: true });
      return { policy: SessionPolicySchema.parse(await res.json()), serverTime: serverTimeOf(res) };
    },
    /** POST /v1/session/activity: the person used the app (pointer, key, touch, scroll, "Stay signed in"). */
    reportActivity: async (signal?: AbortSignal): Promise<void> => {
      await send('/v1/session/activity', { method: 'POST', signal });
    },
    /** GET /v1/me, only for `signedOutReason` (and whether the session is still there). */
    getSignedOutState: async (signal?: AbortSignal): Promise<SignedOutState> => {
      const res = await send('/v1/me', { signal, background: true });
      const me = MeSchema.parse(await res.json());
      return { authenticated: me.authenticated, reason: me.signedOutReason ?? null };
    }
  };
}

export type SessionApi = ReturnType<typeof createSessionApi>;
