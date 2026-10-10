import { describe, expect, it } from 'vitest';

import { contactStateFrom, formatWait, noShowReady, validateDeliveryProblem, validatePickupProblem, WAIT_MS, waitLabel, waitRemainingMs } from '../src/domain/problems';

const t0 = Date.parse('2026-10-10T19:00:00Z');

describe('customer unavailable: call, text and a 5-minute timer', () => {
  it('counts down five minutes', () => {
    expect(waitRemainingMs(null, t0)).toBeNull();
    expect(waitRemainingMs(t0, t0 + 60_000)).toBe(4 * 60_000);
    expect(waitRemainingMs(t0, t0 + WAIT_MS + 1)).toBe(0);
    expect(formatWait(4 * 60_000 + 1)).toBe('4:01');
    expect(waitLabel(null, t0)).toBe('Start 5-minute wait');
    expect(waitLabel(t0, t0 + 30_000)).toBe('Waiting… 4:30');
    expect(waitLabel(t0, t0 + WAIT_MS)).toBe('✓ Waited 5 minutes');
  });

  it('needs call, text and the full wait before a fallback', () => {
    const contacts = [{ kind: 'CALL' as const, at: '2026-10-10T19:00:00Z' }, { kind: 'TEXT' as const, at: '2026-10-10T19:00:10Z' }, { kind: 'WAIT_STARTED' as const, at: '2026-10-10T19:00:20Z' }];
    const c = contactStateFrom(contacts);
    expect(c).toEqual({ called: true, texted: true, waitStartedAt: Date.parse('2026-10-10T19:00:20Z') });
    const input = { type: 'CUSTOMER_UNAVAILABLE' as const, resolution: 'REATTEMPT' as const, note: '', hasPhoto: false, contact: c, reattempts: 0, now: t0 + 60_000 };
    expect(validateDeliveryProblem(input)).toBe('Call, text and complete the 5-minute wait first.');
    expect(noShowReady(c, Date.parse('2026-10-10T19:05:20Z'))).toBe(true);
    expect(validateDeliveryProblem({ ...input, now: Date.parse('2026-10-10T19:05:20Z') })).toBeNull();
    expect(validateDeliveryProblem({ ...input, contact: { ...c, texted: false }, now: t0 + WAIT_MS * 2 })).toBe('Call, text and complete the 5-minute wait first.');
  });

  it('limits reattempts to two', () => {
    const c = { called: true, texted: true, waitStartedAt: t0 };
    expect(validateDeliveryProblem({ type: 'CUSTOMER_UNAVAILABLE', resolution: 'REATTEMPT', note: '', hasPhoto: false, contact: c, reattempts: 2, now: t0 + WAIT_MS })).toMatch(/reattempt limit/);
  });
});

describe('other problems need evidence', () => {
  const none = { called: false, texted: false, waitStartedAt: null };
  it('requires notes and photos', () => {
    expect(validateDeliveryProblem({ type: 'ACCESS_ISSUE', resolution: 'REATTEMPT', note: 'x', hasPhoto: true, contact: none, reattempts: 0, now: t0 })).toBe('Add a short note for the customer and dispatch.');
    expect(validateDeliveryProblem({ type: 'ACCESS_ISSUE', resolution: 'REATTEMPT', note: 'Gate code failed', hasPhoto: false, contact: none, reattempts: 0, now: t0 })).toBe('A photo is required for this problem.');
    expect(validateDeliveryProblem({ type: 'REFUSED', resolution: 'RETURN_TO_FC', note: 'Wrong order', hasPhoto: false, contact: none, reattempts: 0, now: t0 })).toBeNull();
    expect(validateDeliveryProblem({ type: 'REFUSED', resolution: 'REATTEMPT', note: 'Wrong order', hasPhoto: false, contact: none, reattempts: 0, now: t0 })).toBe('Choose what happens next.');
  });
  it('validates pickup problems', () => {
    expect(validatePickupProblem('NOT_READY', 'WAIT', 'Still packing', false)).toBeNull();
    expect(validatePickupProblem('WRONG_COUNT', 'PARTIAL', 'Only one bag', false)).toBe('A photo is required for this problem.');
    expect(validatePickupProblem('CLOSED', 'WAIT', 'Shut', false)).toBe('Choose what happens next.');
  });
});
