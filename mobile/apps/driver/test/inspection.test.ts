import { describe, expect, it } from 'vitest';

import { CHECKS, emptyInspection, type InspectionDraft, inspectionOutcome, PHOTO_SIDES, readingHelp, toPayload, validateInspection } from '../src/domain/inspection';
import { type PhotoCapture } from '../src/domain/types';

const photo: PhotoCapture = { source: 'LIVE_CAMERA', capturedAt: '2026-10-10T18:00:00Z', lat: 29.7, lon: -95.5, accuracyM: 5, stampRouteId: 'RT-1', sha256: 'abc', localUri: 'file:///p.jpg' };
const ctx = { lastOdometer: 48210, perishable: true };

function complete(over: Partial<InspectionDraft> = {}): InspectionDraft {
  return {
    ...emptyInspection(),
    checks: Object.fromEntries(CHECKS.map((c) => [c.key, 'PASS'])),
    odometer: '48222', fuel: '75', coldBox: '37',
    photos: Object.fromEntries(PHOTO_SIDES.map((s) => [s, photo])),
    certified: true, signatureLocalUri: 'file:///sig.png',
    ...over
  };
}

describe('vehicle inspection', () => {
  it('passes a complete inspection', () => {
    expect(validateInspection(complete(), ctx)).toBeNull();
    expect(inspectionOutcome(complete())).toBe('passed');
  });

  it('requires every check, valid readings, photos and certification', () => {
    expect(validateInspection(emptyInspection(), ctx)).toBe('Mark every item pass or fail (10 left).');
    expect(validateInspection(complete({ odometer: '48000' }), ctx)).toBe('Enter a valid odometer reading.');
    expect(validateInspection(complete({ fuel: '120' }), ctx)).toBe('Enter fuel or charge %.');
    expect(validateInspection(complete({ photos: { FRONT: photo } }), ctx)).toBe('Take all 4 vehicle photos.');
    expect(validateInspection(complete({ certified: false }), ctx)).toBe('Certify the inspection.');
    expect(validateInspection(complete({ signatureLocalUri: null }), ctx)).toBe('Sign the inspection.');
  });

  it('requires the cold box at 33–40°F only when perishables are on board', () => {
    expect(validateInspection(complete({ coldBox: '45' }), ctx)).toBe('Cold box must read 33–40°F for perishables.');
    expect(validateInspection(complete({ coldBox: '' }), { ...ctx, perishable: false })).toBeNull();
    expect(readingHelp('coldBox', complete({ coldBox: '30' }), ctx)?.text).toBe('Perishables on board: 33–40°F');
    expect(readingHelp('fuel', complete({ fuel: '20' }), ctx)?.tone).toBe('warning');
  });

  it('accepts only live-camera photos', () => {
    const gallery = { ...photo, source: 'GALLERY' } as unknown as PhotoCapture;
    expect(validateInspection(complete({ photos: { FRONT: gallery, BACK: photo, LEFT: photo, RIGHT: photo } }), ctx)).toBe('Vehicle photos must come from the live camera.');
  });

  it('blocks the route when brakes, tyres or lights fail', () => {
    for (const key of ['BRAKES', 'TYRES', 'LIGHTS'] as const) {
      const d = complete({ checks: { ...complete().checks, [key]: 'FAIL' }, defectNote: 'Worn' });
      expect(inspectionOutcome(d)).toBe('blocked');
    }
    const minor = complete({ checks: { ...complete().checks, HORN_WIPERS: 'FAIL' }, defectNote: 'Left wiper torn' });
    expect(inspectionOutcome(minor)).toBe('passed-with-defect');
    expect(validateInspection({ ...minor, defectNote: '' }, ctx)).toBe('Describe the defect.');
  });

  it('builds the contract payload', () => {
    const p = toPayload(complete(), 'VEH-1', ctx);
    expect(p.checks.BRAKES).toBe('PASS');
    expect(p.odometerMiles).toBe(48222);
    expect(p.coldBoxF).toBe(37);
    expect(Object.keys(p.photos)).toEqual(['FRONT', 'BACK', 'LEFT', 'RIGHT']);
  });
});
