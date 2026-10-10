/* S084/S086 delivery and pickup problems, including the customer-unavailable flow (call, text, 5-minute wait). */
import { type ContactKind, type DeliveryProblemType, type DeliveryResolution, type PickupProblemType, type PickupResolution } from './types';

export const WAIT_MS = 5 * 60_000;
export const MAX_REATTEMPTS = 2;

export const DELIVERY_PROBLEMS: Array<{ type: DeliveryProblemType; label: string; hint: string; resolutions: Array<{ v: DeliveryResolution; label: string }>; photo: boolean }> = [
  { type: 'CUSTOMER_UNAVAILABLE', label: 'Customer not available', hint: 'Call, text, wait 5 minutes', photo: false, resolutions: [{ v: 'REATTEMPT', label: 'Reattempt later on this route' }, { v: 'PICKUP_POINT', label: 'Take to AfroTransact pickup point (customer notified)' }, { v: 'RETURN_TO_FC', label: 'Return to FC' }] },
  { type: 'ACCESS_ISSUE', label: 'Can’t access the building / wrong address', hint: 'Gate code, buzzer, address not found', photo: true, resolutions: [{ v: 'REATTEMPT', label: 'Reattempt after contacting customer' }, { v: 'RETURN_TO_FC', label: 'Return to FC' }] },
  { type: 'DAMAGED', label: 'Package damaged', hint: 'Photo required; customer may refuse', photo: true, resolutions: [{ v: 'RETURN_TO_FC', label: 'Customer refused · return to FC' }, { v: 'ACCEPTED_WITH_DAMAGE', label: 'Customer accepted · log damage claim' }] },
  { type: 'REFUSED', label: 'Customer refused', hint: 'Record the reason', photo: false, resolutions: [{ v: 'RETURN_TO_FC', label: 'Return to FC' }] },
  { type: 'MISSING', label: 'Package missing from van', hint: 'Not found on board', photo: false, resolutions: [{ v: 'REPORT_MISSING', label: 'Report missing · dispatch investigates' }] },
  { type: 'UNSAFE', label: 'Unsafe to deliver', hint: 'Dog, hazard, threatening situation', photo: true, resolutions: [{ v: 'RETURN_TO_FC', label: 'Return to FC' }, { v: 'PICKUP_POINT', label: 'Take to pickup point' }] }
];

export const PICKUP_PROBLEMS: Array<{ type: PickupProblemType; label: string; hint: string; resolutions: Array<{ v: PickupResolution; label: string }>; photo: boolean }> = [
  { type: 'NOT_READY', label: 'Order not ready', hint: 'Seller still packing', photo: false, resolutions: [{ v: 'WAIT', label: 'Wait up to 10 minutes' }, { v: 'SKIP', label: 'Skip · seller reschedules' }] },
  { type: 'CLOSED', label: 'Seller closed', hint: 'No one at the shop', photo: false, resolutions: [{ v: 'SKIP', label: 'Skip · seller reschedules' }] },
  { type: 'WRONG_COUNT', label: 'Package count mismatch', hint: 'Fewer or more packages than the manifest', photo: true, resolutions: [{ v: 'PARTIAL', label: 'Take what’s ready · rest rescheduled' }, { v: 'SKIP', label: 'Refuse all' }] },
  { type: 'DAMAGED', label: 'Damaged at pickup', hint: 'Refuse or accept with photo', photo: true, resolutions: [{ v: 'SKIP', label: 'Refuse · seller repacks' }, { v: 'PARTIAL', label: 'Accept with damage photo' }] }
];

/* ── Customer unavailable: call, text and a 5-minute wait ─────────────────── */

export interface ContactState {
  called: boolean;
  texted: boolean;
  waitStartedAt: number | null;
}

export function contactStateFrom(contacts: Array<{ kind: ContactKind; at: string }>): ContactState {
  const wait = contacts.filter((c) => c.kind === 'WAIT_STARTED').map((c) => Date.parse(c.at));
  return { called: contacts.some((c) => c.kind === 'CALL'), texted: contacts.some((c) => c.kind === 'TEXT'), waitStartedAt: wait.length ? Math.max(...wait) : null };
}

export function waitRemainingMs(waitStartedAt: number | null, now: number): number | null {
  if (waitStartedAt === null) return null;
  return Math.max(0, waitStartedAt + WAIT_MS - now);
}

export function formatWait(ms: number): string {
  const s = Math.ceil(ms / 1000);
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`;
}

export function waitLabel(waitStartedAt: number | null, now: number): string {
  const left = waitRemainingMs(waitStartedAt, now);
  if (left === null) return 'Start 5-minute wait';
  return left > 0 ? `Waiting… ${formatWait(left)}` : '✓ Waited 5 minutes';
}

export function noShowReady(c: ContactState, now: number): boolean {
  return c.called && c.texted && waitRemainingMs(c.waitStartedAt, now) === 0;
}

export interface ProblemInput {
  type: DeliveryProblemType | null;
  resolution: DeliveryResolution | null;
  note: string;
  hasPhoto: boolean;
  contact: ContactState;
  reattempts: number;
  now: number;
}

export function validateDeliveryProblem(i: ProblemInput): string | null {
  if (!i.type) return 'Choose what went wrong.';
  const def = DELIVERY_PROBLEMS.find((p) => p.type === i.type);
  if (!i.resolution || !def?.resolutions.some((r) => r.v === i.resolution)) return 'Choose what happens next.';
  if (i.type === 'CUSTOMER_UNAVAILABLE' && !noShowReady(i.contact, i.now)) return 'Call, text and complete the 5-minute wait first.';
  if (i.type !== 'CUSTOMER_UNAVAILABLE' && i.note.trim().length < 5) return 'Add a short note for the customer and dispatch.';
  if (def.photo && !i.hasPhoto) return 'A photo is required for this problem.';
  if (i.resolution === 'REATTEMPT' && i.reattempts >= MAX_REATTEMPTS) return `This stop has reached the reattempt limit (${MAX_REATTEMPTS}). Choose a pickup point or return.`;
  return null;
}

export function validatePickupProblem(type: PickupProblemType | null, resolution: PickupResolution | null, note: string, hasPhoto: boolean): string | null {
  if (!type) return 'Choose what went wrong.';
  const def = PICKUP_PROBLEMS.find((p) => p.type === type);
  if (!resolution || !def?.resolutions.some((r) => r.v === resolution)) return 'Choose what happens next.';
  if (note.trim().length < 5) return 'Add a short note for the seller and dispatch.';
  if (def.photo && !hasPhoto) return 'A photo is required for this problem.';
  return null;
}
