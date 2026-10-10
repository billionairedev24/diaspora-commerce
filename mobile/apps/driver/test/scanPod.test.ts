import { describe, expect, it } from 'vitest';

import { distanceMeters, handoverOptions, podCheck, type PodInput, stampText } from '../src/domain/pod';
import { checkScan, isRepeat } from '../src/domain/scan';
import { type PhotoCapture } from '../src/domain/types';

describe('label scanning', () => {
  const expected = ['ATS7K2Q9H4M1', 'ATS3NP8D2LQ6'];
  it('accepts expected labels once', () => {
    expect(checkScan(' ats7k2q9h4m1 ', expected, [])).toEqual({ ok: true, label: 'ATS7K2Q9H4M1' });
    expect(checkScan('ATS7K2Q9H4M1', expected, ['ATS7K2Q9H4M1'])).toEqual({ ok: false, message: 'ATS7K2Q9H4M1 was already scanned.' });
  });
  it('rejects unknown and malformed labels', () => {
    expect(checkScan('ATS000000000', expected, [])).toEqual({ ok: false, message: 'ATS000000000 isn’t expected here. Set it aside and tell dispatch.' });
    expect(checkScan('12345', expected, [])).toEqual({ ok: false, message: 'Not an AfroTransact shipment label (ATS + 9 characters).' });
  });
  it('ignores repeated camera reads', () => {
    expect(isRepeat({ label: 'A', at: 0 }, 'A', 1000)).toBe(true);
    expect(isRepeat({ label: 'A', at: 0 }, 'A', 3000)).toBe(false);
  });
});

describe('proof of delivery', () => {
  const photo: PhotoCapture = { source: 'LIVE_CAMERA', capturedAt: '2026-10-10T19:31:02Z', lat: 29.7376, lon: -95.5134, accuracyM: 6, stampRouteId: 'RT-HOU-PM', sha256: 'x', localUri: 'file:///pod.jpg' };
  const base: PodInput = { method: 'CUSTOMER', code: '4821', recipient: '', relationship: '', signatureLocalUri: null, safePlace: '', photo, perishable: false, safePlaceAllowed: true, stop: { lat: 29.7376, lon: -95.5134 } };

  it('hand to customer requires the 4-digit code', () => {
    expect(podCheck(base)).toBeNull();
    expect(podCheck({ ...base, code: '48' })).toBe('Ask for the 4-digit code from their SMS.');
  });
  it('someone else requires name, relationship and signature', () => {
    const s = { ...base, method: 'SOMEONE_ELSE' as const };
    expect(podCheck(s)).toBe('Enter who received it and their relationship.');
    expect(podCheck({ ...s, recipient: 'Ngozi', relationship: 'Neighbour' })).toBe('Ask the recipient to sign.');
    expect(podCheck({ ...s, recipient: 'Ngozi', relationship: 'Neighbour', signatureLocalUri: 'file:///s.png' })).toBeNull();
  });
  it('blocks safe place for perishables and when not allowed', () => {
    const s = { ...base, method: 'SAFE_PLACE' as const, safePlace: 'Front door' };
    expect(podCheck(s)).toBeNull();
    expect(podCheck({ ...s, perishable: true })).toBe('Perishable items must be handed to a person.');
    expect(podCheck({ ...s, safePlaceAllowed: false })).toMatch(/didn’t allow a safe place/);
    expect(handoverOptions(true, true).find((o) => o.method === 'SAFE_PLACE')?.disabled).toBe(true);
  });
  it('requires a live photo within 150 m', () => {
    expect(podCheck({ ...base, photo: null })).toBe('Take a proof-of-delivery photo.');
    expect(podCheck({ ...base, photo: { ...photo, lat: 29.7406, lon: -95.5134 } })).toMatch(/you’re 334 m away/);
    expect(distanceMeters(29.7376, -95.5134, 29.7386, -95.5134)).toBe(111);
  });
  it('stamps time, GPS and route id', () => {
    expect(stampText(new Date('2026-10-10T19:31:02Z'), 29.7376, -95.5134, 'RT-HOU-PM')).toMatch(/29\.73760, -95\.51340 · RT-HOU-PM$/);
    expect(stampText(new Date(), null, null, 'RT-1')).toMatch(/GPS unavailable · RT-1$/);
  });
});
