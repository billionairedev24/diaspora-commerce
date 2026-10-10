/* What people type in the fee editor, converted exactly (no floating point): dollars → minor units, miles with up to
   two decimals. Shared by the commission model and the AfroTransact Delivery pricing model. */

let seq = 0;
/** Stable React key for an editable row. */
export const rowId = (): string => `r${(seq += 1)}`;

/** Minor units as dollars without the sign ("3.25"). */
export const dollarsText = (minor: number): string => `${Math.floor(minor / 100)}.${String(minor % 100).padStart(2, '0')}`;

/** Dollars typed by people ("3.25", "$3.25", "3") → minor units, exactly; null when not an amount. */
export function parseDollars(text: string): number | null {
  const m = /^\$?\s*(\d{1,6})(?:\.(\d{0,2}))?$/.exec(text.trim());
  if (!m) return null;
  return Number(m[1]) * 100 + Number((m[2] ?? '').padEnd(2, '0'));
}

/** Miles typed by people ("25", "7.5", "0.25") → a number with at most two decimals; null when not a distance. */
export function parseMiles(text: string): number | null {
  const m = /^(\d{1,4})(?:\.(\d{1,2}))?$/.exec(text.trim());
  if (!m) return null;
  return Number(`${m[1]}.${m[2] ?? '0'}`);
}

/** Miles as typed back into a field ("25", "7.5"). */
export const milesText = (miles: number): string => String(Math.round(miles * 100) / 100);
