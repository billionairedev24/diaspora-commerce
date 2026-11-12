import { type CustomerPickupRequest, type PickupPrompt, type PickupPromptAddress, type PickupWindow, validatePickupHours } from '@afrotransact/api-client';

/**
 * Rules of the one-time "let customers collect orders here" card. The server decides and remembers (per organisation, in
 * carrier-gateway); these functions double-check what it sent and gate the seller's actions, so pickup is never switched
 * on without hours the seller typed and an explicit confirmation.
 */

/** The card: an approved seller with a ship-from address, no pickup place yet, and not dismissed (or the snooze is over). */
export function cardVisible(prompt: PickupPrompt | undefined, now: Date = new Date()): boolean {
  if (!prompt || !prompt.eligible || prompt.hasPickupPlace || !prompt.address) return false;
  if (prompt.state === 'NEVER') return false;
  if (prompt.state === 'SNOOZED') {
    const until = prompt.snoozedUntil ? Date.parse(prompt.snoozedUntil) : Number.NaN;
    if (Number.isNaN(until) || now.getTime() < until) return false;
  }
  return prompt.show;
}

/** The small inline reminder on checkout settings: only while no pickup place exists, whatever was dismissed. */
export function nudgeVisible(prompt: PickupPrompt | undefined): boolean {
  return Boolean(prompt && prompt.eligible && !prompt.hasPickupPlace && prompt.nudge);
}

/** A first window to type into: nothing is invented, so opening and closing times start empty and are required. */
export function blankWindow(): PickupWindow {
  return { day: 'MONDAY', opens: '', closes: '' };
}

/** The 5-digit ZIP shoppers are matched on, or null when the address has none. */
export function previewZip(address: Pick<PickupPromptAddress, 'postalCode'> | null | undefined): string | null {
  const m = /^\d{5}/.exec(address?.postalCode.trim() ?? '');
  return m ? m[0] : null;
}

export interface TurnOnCheck {
  ok: boolean;
  /** Keys: hours, hours.<i>.opens|closes (from validatePickupHours), readyHours, confirmed. */
  errors: Record<string, string>;
}

/** What must be true before "Turn on pickup" works: valid hours, a ready time, and the explicit confirmation. */
export function checkTurnOn(hours: PickupWindow[], ready: string, confirmed: boolean, messages: { ready: string; confirm: string }): TurnOnCheck {
  const errors = validatePickupHours(true, hours);
  const n = ready.trim() === '' ? Number.NaN : Number(ready);
  if (!Number.isInteger(n) || n < 0 || n > 168) errors.readyHours = messages.ready;
  if (!confirmed) errors.confirmed = messages.confirm;
  return { ok: Object.keys(errors).length === 0, errors };
}

/** The request that switches pickup on, or null while anything is missing (the card never sends an unconfirmed one). */
export function turnOnRequest(hours: PickupWindow[], ready: string, confirmed: boolean): CustomerPickupRequest | null {
  if (!checkTurnOn(hours, ready, confirmed, { ready: 'x', confirm: 'x' }).ok) return null;
  return { enabled: true, hours, readyHours: Number(ready), hoursConfirmed: true };
}
