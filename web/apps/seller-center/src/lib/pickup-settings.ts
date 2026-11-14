import { type CustomerPickup, type CustomerPickupRequest, type PickupWindow, validatePickupHours } from '@afrotransact/api-client';

/**
 * Settings > Pickup: what a location's form may send. The server stays the authority (carrier-gateway refuses off to on
 * without hoursConfirmed and a valid window); these rules stop the form from ever sending an unconfirmed turn-on.
 */
export interface PickupDraft {
  enabled: boolean;
  hours: PickupWindow[];
  /** Typed text: empty until the seller types it (nothing is invented). */
  ready: string;
  /** "Someone will be at this address during these hours." */
  confirmed: boolean;
}

export type PickupSave = { ok: true; request: CustomerPickupRequest } | { ok: false; errors: Record<string, string> };

/** Messages the validation can return, in the seller's language. */
export interface PickupSaveMessages {
  ready: string;
  confirm: string;
}

/** A draft that starts from what is saved: pickup off stays empty and unconfirmed; on shows the saved hours and ready time. */
export function draftOf(saved: CustomerPickup | undefined): PickupDraft {
  if (!saved?.enabled) return { enabled: false, hours: [], ready: '', confirmed: false };
  return { enabled: true, hours: saved.hours, ready: String(saved.readyHours), confirmed: true };
}

export function pickupSave(saved: CustomerPickup | undefined, draft: PickupDraft, msg: PickupSaveMessages): PickupSave {
  const wasOn = saved?.enabled ?? false;
  if (!draft.enabled) {
    // Switching off (or leaving it off) needs no hours and no confirmation.
    return { ok: true, request: { enabled: false, hours: saved?.hours ?? [], readyHours: saved?.readyHours ?? 0, hoursConfirmed: false } };
  }
  const errors = validatePickupHours(true, draft.hours);
  const n = draft.ready.trim() === '' ? Number.NaN : Number(draft.ready);
  if (!Number.isInteger(n) || n < 0 || n > 168) errors.readyHours = msg.ready;
  // Turning it on from off is the explicit confirmation; an edit of a place that stays on keeps the first one.
  if (!wasOn && !draft.confirmed) errors.confirmed = msg.confirm;
  if (Object.keys(errors).length > 0) return { ok: false, errors };
  return { ok: true, request: { enabled: true, hours: draft.hours, readyHours: n, hoursConfirmed: true } };
}

/** The first thing missing, in the order the seller fills the form, for a hint under the button. */
export function pickupHint(saved: CustomerPickup | undefined, draft: PickupDraft): 'hours' | 'ready' | 'confirm' | null {
  if (!draft.enabled) return null;
  const errors = validatePickupHours(true, draft.hours);
  if (draft.hours.length === 0 || Object.keys(errors).some((k) => k.startsWith('hours'))) return 'hours';
  const n = draft.ready.trim() === '' ? Number.NaN : Number(draft.ready);
  if (!Number.isInteger(n) || n < 0 || n > 168) return 'ready';
  if (!(saved?.enabled ?? false) && !draft.confirmed) return 'confirm';
  return null;
}
