/* Location pings while on shift (S255): fulfilment-service keeps each driver's last known position (PostGIS) to offer
   seller pickups to the nearest driver; positions older than 10 minutes are ignored there. Pings are throttled here:
   one when the last one is a minute old, or as soon as the driver moved 200 m, but never more than one per 15 s.
   Pure functions (unit-tested); the timer-free policy is driven by the device's location updates. */

export interface GpsFix {
  lat: number;
  lon: number;
  accuracyM: number | null;
  /** Device time of the fix (ms since epoch). */
  at: number;
}

export interface PingPolicy {
  intervalMs: number;
  distanceM: number;
  minGapMs: number;
  /** Fixes older than this are not worth sending (the server ignores stale positions). */
  maxAgeMs: number;
  /** Fixes less accurate than this are skipped (a 1 km circle helps no nearest-driver ranking). */
  maxAccuracyM: number;
}

export const PING_POLICY: PingPolicy = { intervalMs: 60_000, distanceM: 200, minGapMs: 15_000, maxAgeMs: 120_000, maxAccuracyM: 500 };

const R = 6_371_000;

/** Great-circle distance in metres. */
export function distanceM(a: { lat: number; lon: number }, b: { lat: number; lon: number }): number {
  const t = Math.PI / 180;
  const x = Math.sin(((b.lat - a.lat) * t) / 2) ** 2 + Math.cos(a.lat * t) * Math.cos(b.lat * t) * Math.sin(((b.lon - a.lon) * t) / 2) ** 2;
  return 2 * R * Math.asin(Math.min(1, Math.sqrt(x)));
}

export type PingDecision = 'send' | 'wait' | 'skip-stale' | 'skip-inaccurate';

/** Whether a new fix should be sent, given the last one sent (null = none yet this shift). */
export function decidePing(last: GpsFix | null, fix: GpsFix, now: number, p: PingPolicy = PING_POLICY): PingDecision {
  if (now - fix.at > p.maxAgeMs) return 'skip-stale';
  if (fix.accuracyM !== null && fix.accuracyM > p.maxAccuracyM) return 'skip-inaccurate';
  if (!last) return 'send';
  const gap = now - last.at;
  if (gap < p.minGapMs) return 'wait';
  if (gap >= p.intervalMs) return 'send';
  return distanceM(last, fix) >= p.distanceM ? 'send' : 'wait';
}
