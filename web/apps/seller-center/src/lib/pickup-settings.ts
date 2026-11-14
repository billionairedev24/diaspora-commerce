import { type CustomerPickup, type CustomerPickupRequest } from '@afrotransact/api-client';

import { checkWeek, type DayRow, sameWeek, type WeekMessages, weekOf, windowsOf } from './weekly-hours';

/**
 * Settings > Pickup: what a location's form may send. The server stays the authority (carrier-gateway refuses off to on
 * without hoursConfirmed and a valid window); these rules stop the form from ever sending an unconfirmed turn-on.
 * The one switch is the confirmation: turning it on says someone will be there, so a turn-on always carries it.
 */
export interface PickupDraft {
  enabled: boolean;
  week: DayRow[];
  /** Hours as a string: '' until the seller chooses (nothing is invented). */
  ready: string;
}

export type PickupSave = { ok: true; request: CustomerPickupRequest } | { ok: false; message: string; days: DayRow['day'][] };

/** Messages the validation can return, in the seller's language. */
export interface PickupSaveMessages extends WeekMessages {
  /** No ready time chosen. */
  ready: string;
}

/** A draft that starts from what is saved: pickup off stays closed all week and empty; on shows the saved hours and ready time. */
export function draftOf(saved: CustomerPickup | undefined): PickupDraft {
  if (!saved?.enabled) return { enabled: false, week: weekOf([]), ready: '' };
  return { enabled: true, week: weekOf(saved.hours), ready: String(saved.readyHours) };
}

function readyHours(ready: string): number | null {
  const n = ready.trim() === '' ? Number.NaN : Number(ready);
  return Number.isInteger(n) && n >= 0 && n <= 168 ? n : null;
}

/** The first thing wrong, or null: nothing to fix when pickup is off. */
function problemOf(draft: PickupDraft, msg: PickupSaveMessages): { message: string; days: DayRow['day'][] } | null {
  if (!draft.enabled) return null;
  const week = checkWeek(draft.week, msg);
  if (!week.ok) return { message: week.message ?? msg.none, days: week.days };
  if (readyHours(draft.ready) === null) return { message: msg.ready, days: [] };
  return null;
}

/** Has the seller changed anything from what is saved? */
export function pickupDirty(saved: CustomerPickup | undefined, draft: PickupDraft): boolean {
  const was = draftOf(saved);
  if (draft.enabled !== was.enabled) return true;
  if (!draft.enabled) return false;
  return draft.ready !== was.ready || !sameWeek(draft.week, was.week);
}

/** The Save button: there is a change, and it is valid. */
export function pickupCanSave(saved: CustomerPickup | undefined, draft: PickupDraft, msg: PickupSaveMessages): boolean {
  return pickupDirty(saved, draft) && problemOf(draft, msg) === null;
}

/** The plain line under the list: what to fix, shown while pickup is on and the draft is not valid. */
export function pickupProblem(draft: PickupDraft, msg: PickupSaveMessages): { message: string; days: DayRow['day'][] } | null {
  return problemOf(draft, msg);
}

export function pickupSave(saved: CustomerPickup | undefined, draft: PickupDraft, msg: PickupSaveMessages): PickupSave {
  if (!draft.enabled) {
    // Switching off (or leaving it off) needs no hours.
    return { ok: true, request: { enabled: false, hours: saved?.hours ?? [], readyHours: saved?.readyHours ?? 0, hoursConfirmed: false } };
  }
  const p = problemOf(draft, msg);
  const n = readyHours(draft.ready);
  if (p || n === null) return { ok: false, message: p?.message ?? msg.ready, days: p?.days ?? [] };
  // Turning it on from off is the explicit confirmation; an edit of a place that stays on keeps the first one.
  return { ok: true, request: { enabled: true, hours: windowsOf(draft.week), readyHours: n, hoursConfirmed: true } };
}
