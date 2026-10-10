/* Location pings while on shift (S255). The DriverProvider starts the pinger only while the driver is signed in, on
   shift, and the app is in the foreground; it never asks for background location. Device updates come from a
   foreground watch (lib/location), each one goes through the throttle in domain/locationPing, and a ping is sent only
   when online: a position is only useful while it is live, so pings are never queued in the outbox (fulfilment ignores
   positions older than 10 minutes). The newest fix is kept so queued sync events can be stamped with it.
   Plain TS with injected deps so it is unit-tested without a device. */
import { decidePing, type GpsFix, PING_POLICY, type PingPolicy } from '@/domain/locationPing';

export type PingerStatus = 'idle' | 'watching' | 'no-permission' | 'forbidden';

export interface PingerDeps {
  /** Starts a foreground watch; resolves the unsubscribe, or null when location permission is not granted. */
  watch(onFix: (fix: GpsFix) => void): Promise<(() => void) | null>;
  send(fix: GpsFix): Promise<unknown>;
  now(): number;
  policy?: PingPolicy;
}

function statusOf(e: unknown): number | null {
  return e && typeof e === 'object' && 'status' in e && typeof (e as { status: unknown }).status === 'number' ? (e as { status: number }).status : null;
}

export class LocationPinger {
  private stopWatch: (() => void) | null = null;
  private starting: Promise<void> | null = null;
  private lastSent: GpsFix | null = null;
  private lastAttemptAt = Number.NEGATIVE_INFINITY;
  private latestFix: GpsFix | null = null;
  private inFlight = false;
  private online = true;
  private wanted = false;
  status: PingerStatus = 'idle';

  constructor(private readonly deps: PingerDeps) {}

  private get policy(): PingPolicy {
    return this.deps.policy ?? PING_POLICY;
  }

  /** Starts watching (idempotent). A 403 from the server (no driver access) stops pings until the next start. */
  start(): Promise<void> {
    this.wanted = true;
    if (this.stopWatch || this.starting) return this.starting ?? Promise.resolve();
    this.starting = this.deps
      .watch((f) => this.onFix(f))
      .then((unsubscribe) => {
        if (!unsubscribe) {
          this.status = 'no-permission';
          return;
        }
        if (!this.wanted) return unsubscribe(); // stopped while the watch was starting
        this.stopWatch = unsubscribe;
        this.status = 'watching';
      })
      .catch(() => {
        this.status = 'no-permission';
      })
      .finally(() => {
        this.starting = null;
      });
    return this.starting;
  }

  /** Off shift, signed out or in the background. The next shift starts with a fresh ping. */
  stop(): void {
    this.wanted = false;
    this.stopWatch?.();
    this.stopWatch = null;
    this.lastSent = null;
    this.lastAttemptAt = Number.NEGATIVE_INFINITY;
    if (this.status !== 'forbidden') this.status = 'idle';
  }

  setOnline(online: boolean): void {
    const was = this.online;
    this.online = online;
    if (online && !was && this.latestFix) this.onFix(this.latestFix); // back online: send the newest fix if still fresh
  }

  /** The newest device fix, if it is recent enough to stamp an event with. */
  latest(maxAgeMs = this.policy.intervalMs): GpsFix | null {
    const f = this.latestFix;
    return f && this.deps.now() - f.at <= maxAgeMs ? f : null;
  }

  private onFix(fix: GpsFix): void {
    if (!this.latestFix || fix.at >= this.latestFix.at) this.latestFix = fix;
    if (!this.online || this.inFlight || this.status === 'forbidden' || !this.stopWatch) return;
    const now = this.deps.now();
    if (now - this.lastAttemptAt < this.policy.minGapMs) return; // a failed ping is retried, but not more than every 15 s
    if (decidePing(this.lastSent, fix, now, this.policy) !== 'send') return;
    this.inFlight = true;
    this.lastAttemptAt = now;
    void this.deps
      .send(fix)
      .then(() => {
        this.lastSent = { ...fix, at: now };
      })
      .catch((e: unknown) => {
        const s = statusOf(e);
        if (s === 403) {
          this.status = 'forbidden';
          this.stopWatch?.();
          this.stopWatch = null;
        }
      })
      .finally(() => {
        this.inFlight = false;
      });
  }
}
