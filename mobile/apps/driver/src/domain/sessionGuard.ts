/* The stateful half of the sign-out policy (see sessionPolicy.ts for the rules): keeps the clock and the cached policy,
   reads and writes them through an injected store, and tells the app once when the session has ended.
   Identical in the customer app and the driver app; no React Native here, so it is unit-tested with fakes.

   What it never does: touch tokens, the outbox, the lock or any network request except the injected `fetchPolicy`
   (which the app sends with `X-Background: 1`, so reading the policy is not activity). Ending the session is the app's
   own sign-out path, called from `onExpire`. Secrets are never logged; the stored values are three timestamps. */
import {
  type CachedPolicy,
  checkSession,
  effectivePolicy,
  observe,
  parsePolicy,
  parseStoredClock,
  parseStoredPolicy,
  reasonOf,
  recordActivity,
  serializeClock,
  serializePolicy,
  type SessionAudience,
  type SessionCheck,
  type SessionClock,
  type SessionEndReason,
  type SessionPolicy,
  shouldFetchPolicy,
  shouldPersistClock,
  startClock
} from './sessionPolicy';

export interface SessionStore {
  get(key: string): Promise<string | null>;
  set(key: string, value: string): Promise<void>;
  remove(key: string): Promise<void>;
}

export interface SessionGuardDeps {
  audience: SessionAudience;
  store: SessionStore;
  /** The JSON body of GET /v1/session/policy (sent with X-Background: 1; for the driver also X-App: driver). Rejects when offline. */
  fetchPolicy(): Promise<unknown>;
  /** Called once per session when a limit has passed. The app signs out through its existing path. */
  onExpire(reason: SessionEndReason): void;
  now?(): number;
}

export function storageKeys(audience: SessionAudience): { clock: string; policy: string } {
  const app = audience === 'driver' ? 'driver' : 'customer';
  return { clock: `at.${app}.session.clock`, policy: `at.${app}.session.policy` };
}

export class SessionGuard {
  private readonly keys: { clock: string; policy: string };
  private cached: CachedPolicy | null = null;
  private clock: SessionClock | null = null;
  private persisted: SessionClock | null = null;
  private ended = false;
  private fetching: Promise<SessionPolicy> | null = null;
  /** One forced policy fetch per stretch of a clock that reads behind, not one per check. */
  private behindHandled = false;

  constructor(private readonly deps: SessionGuardDeps) {
    this.keys = storageKeys(deps.audience);
  }

  private now(): number {
    return (this.deps.now ?? Date.now)();
  }

  /** The policy in force (cached, else the built-in default). */
  policy(): SessionPolicy {
    return effectivePolicy(this.cached, this.deps.audience);
  }

  /** Cold start: restores the cached policy and, when `signedIn`, the clock. A sign-in that predates this feature (or lost
      its clock) gets one fresh window from now, like the BFF does for legacy sessions. Then checks at once. */
  async load(signedIn: boolean): Promise<void> {
    this.cached = parseStoredPolicy(await this.safeGet(this.keys.policy), this.deps.audience);
    this.ended = false;
    if (!signedIn) {
      this.clock = null;
      this.persisted = null;
      return;
    }
    const stored = parseStoredClock(await this.safeGet(this.keys.clock));
    this.persisted = stored;
    if (stored) this.clock = stored;
    else await this.begin();
    this.check();
  }

  /** A new sign-in: the absolute limit counts from here. */
  async begin(): Promise<void> {
    this.ended = false;
    this.clock = startClock(this.now());
    await this.persist();
  }

  /** Signed out (any way): forget the clock. The cached policy is not a secret and stays. */
  async end(): Promise<void> {
    this.clock = null;
    this.persisted = null;
    this.ended = true;
    try {
      await this.deps.store.remove(this.keys.clock);
    } catch {
      /* best effort; a stale clock is replaced by begin() at the next sign-in */
    }
  }

  /** Decides now. Fires onExpire once when a limit has passed. Null when there is no session. */
  check(): SessionCheck | null {
    if (!this.clock || this.ended) return null;
    const now = this.now();
    this.clock = observe(this.clock, now);
    const result = checkSession(this.policy(), this.clock, now);
    const reason = reasonOf(result.status);
    if (reason) {
      this.ended = true;
      this.deps.onExpire(reason);
    } else if (!result.clockBehind) {
      this.behindHandled = false;
    } else if (!this.behindHandled) {
      this.behindHandled = true;
      void this.refreshPolicy(true).catch(() => undefined); // ask the server instead of trusting the wall clock
    }
    return result;
  }

  /** Real foreground interaction. Cheap: throttled in memory, persisted lazily. */
  touch(): void {
    if (!this.clock || this.ended) return;
    const now = this.now();
    const next = recordActivity(this.policy(), this.clock, now);
    if (!next) return;
    this.clock = next;
    if (shouldPersistClock(this.persisted, next, now)) void this.persist();
  }

  /** The user pressed "Stay signed in" (the warning): counts as activity even inside the throttle window. */
  stay(): void {
    if (!this.clock || this.ended) return;
    const now = this.now();
    const next = recordActivity(this.policy(), this.clock, now, 0);
    if (!next) return;
    this.clock = next;
    void this.persist();
  }

  /** Writes the clock now (the app is going to the background). */
  async flush(): Promise<void> {
    if (this.clock && !this.ended) await this.persist();
  }

  private async persist(): Promise<void> {
    const c = this.clock;
    if (!c) return;
    this.persisted = c;
    try {
      await this.deps.store.set(this.keys.clock, serializeClock(c));
    } catch {
      /* best effort: the in-memory clock still applies; a lost write can only make the session end earlier */
    }
  }

  private async safeGet(key: string): Promise<string | null> {
    try {
      return await this.deps.store.get(key);
    } catch {
      return null;
    }
  }

  /** Fetches the policy when due (never fetched, an hour old, forced). A failure keeps the cached or default policy.
      Never counts as activity. Re-checks afterwards, so a shorter limit applies at once. */
  async refreshPolicy(force = false): Promise<SessionPolicy> {
    if (this.fetching) return this.fetching;
    if (!shouldFetchPolicy(this.cached?.fetchedAt ?? null, this.now(), force)) return this.policy();
    this.fetching = (async () => {
      try {
        const fresh = parsePolicy(await this.deps.fetchPolicy(), this.deps.audience);
        if (fresh) {
          this.cached = { policy: fresh, fetchedAt: this.now() };
          try {
            await this.deps.store.set(this.keys.policy, serializePolicy(this.cached));
          } catch {
            /* kept in memory */
          }
        }
      } catch {
        /* offline or the server is down: keep what we have */
      } finally {
        this.fetching = null;
      }
      this.check();
      return this.policy();
    })();
    return this.fetching;
  }
}
