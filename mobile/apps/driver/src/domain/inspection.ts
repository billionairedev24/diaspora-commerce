/* S082 pre-trip inspection (DVIR) rules — pure; unit-tested. */
import { type CheckKey, type CheckResult, type InspectionPayload, type PhotoCapture, type PhotoSide } from './types';

export const CHECKS: Array<{ key: CheckKey; label: string; critical: boolean }> = [
  { key: 'TYRES', label: 'Tyres & wheels', critical: true },
  { key: 'BRAKES', label: 'Brakes', critical: true },
  { key: 'LIGHTS', label: 'Lights & signals', critical: true },
  { key: 'MIRRORS', label: 'Mirrors & windshield', critical: false },
  { key: 'HORN_WIPERS', label: 'Horn & wipers', critical: false },
  { key: 'NO_LEAKS', label: 'No fluid leaks', critical: false },
  { key: 'CARGO_AREA', label: 'Cargo area clean & secure', critical: false },
  { key: 'COLD_BOX', label: 'Cold box powered', critical: false },
  { key: 'SAFETY_KIT', label: 'Extinguisher & first-aid kit', critical: false },
  { key: 'PHONE_MOUNT', label: 'Phone mount & charger', critical: false }
];

export const PHOTO_SIDES: PhotoSide[] = ['FRONT', 'BACK', 'LEFT', 'RIGHT'];
export const COLD_BOX_MIN_F = 33;
export const COLD_BOX_MAX_F = 40;

export interface InspectionDraft {
  checks: Partial<Record<CheckKey, CheckResult>>;
  odometer: string;
  fuel: string;
  coldBox: string;
  photos: Partial<Record<PhotoSide, PhotoCapture>>;
  defectNote: string;
  certified: boolean;
  signatureLocalUri: string | null;
}

export const emptyInspection = (): InspectionDraft => ({ checks: {}, odometer: '', fuel: '', coldBox: '', photos: {}, defectNote: '', certified: false, signatureLocalUri: null });

export interface InspectionContext {
  lastOdometer: number;
  perishable: boolean;
}

export function criticalFailures(d: InspectionDraft): CheckKey[] {
  return CHECKS.filter((c) => c.critical && d.checks[c.key] === 'FAIL').map((c) => c.key);
}

export function anyFailure(d: InspectionDraft): boolean {
  return CHECKS.some((c) => d.checks[c.key] === 'FAIL');
}

const num = (v: string) => (v.trim() === '' ? NaN : Number(v));

/** Inline help under each reading (design copy). */
export function readingHelp(field: 'odometer' | 'fuel' | 'coldBox', d: InspectionDraft, ctx: InspectionContext): { text: string; tone: 'danger' | 'warning' } | null {
  if (field === 'odometer') {
    const v = num(d.odometer);
    return d.odometer && !(v >= ctx.lastOdometer) ? { text: `Must be ≥ ${ctx.lastOdometer}`, tone: 'danger' } : null;
  }
  if (field === 'fuel') {
    const v = num(d.fuel);
    if (!d.fuel) return null;
    if (!(v >= 0 && v <= 100)) return { text: '0–100', tone: 'danger' };
    return v < 25 ? { text: 'Low — refuel before perishables', tone: 'warning' } : null;
  }
  const v = num(d.coldBox);
  if (!ctx.perishable || !d.coldBox) return null;
  return v >= COLD_BOX_MIN_F && v <= COLD_BOX_MAX_F ? null : { text: `Perishables on board: ${COLD_BOX_MIN_F}–${COLD_BOX_MAX_F}°F`, tone: 'danger' };
}

/** First blocking error, or null when the inspection can be submitted (design order of checks). */
export function validateInspection(d: InspectionDraft, ctx: InspectionContext): string | null {
  const missing = CHECKS.filter((c) => !d.checks[c.key]).length;
  if (missing) return `Mark every item pass or fail (${missing} left).`;
  const odo = num(d.odometer);
  if (!(odo >= ctx.lastOdometer)) return 'Enter a valid odometer reading.';
  const fuel = num(d.fuel);
  if (!(fuel >= 0 && fuel <= 100)) return 'Enter fuel or charge %.';
  const cold = num(d.coldBox);
  if (ctx.perishable && !(cold >= COLD_BOX_MIN_F && cold <= COLD_BOX_MAX_F)) return `Cold box must read ${COLD_BOX_MIN_F}–${COLD_BOX_MAX_F}°F for perishables.`;
  if (PHOTO_SIDES.some((s) => !d.photos[s])) return 'Take all 4 vehicle photos.';
  if (PHOTO_SIDES.some((s) => d.photos[s]?.source !== 'LIVE_CAMERA')) return 'Vehicle photos must come from the live camera.';
  if (anyFailure(d) && !d.defectNote.trim()) return 'Describe the defect.';
  if (!d.certified) return 'Certify the inspection.';
  if (!d.signatureLocalUri) return 'Sign the inspection.';
  return null;
}

export type InspectionOutcome = 'passed' | 'passed-with-defect' | 'blocked';

/** Failing brakes, tyres or lights blocks the route and reports a defect; other failures report a defect only. */
export function inspectionOutcome(d: InspectionDraft): InspectionOutcome {
  if (criticalFailures(d).length) return 'blocked';
  return anyFailure(d) ? 'passed-with-defect' : 'passed';
}

export function toPayload(d: InspectionDraft, vehicleId: string, ctx: InspectionContext): InspectionPayload {
  const checks = Object.fromEntries(CHECKS.map((c) => [c.key, d.checks[c.key] ?? 'FAIL'])) as Record<CheckKey, CheckResult>;
  const photos = Object.fromEntries(PHOTO_SIDES.map((s) => [s, d.photos[s] as PhotoCapture])) as Record<PhotoSide, PhotoCapture>;
  return {
    vehicleId,
    checks,
    odometerMiles: Number(d.odometer),
    fuelPercent: Number(d.fuel),
    ...(d.coldBox.trim() !== '' || ctx.perishable ? { coldBoxF: Number(d.coldBox) } : {}),
    photos,
    ...(anyFailure(d) ? { defectNote: d.defectNote.trim() } : {}),
    certified: d.certified,
    ...(d.signatureLocalUri ? { signatureLocalUri: d.signatureLocalUri } : {})
  };
}
