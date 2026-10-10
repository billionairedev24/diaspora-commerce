/* Fee rates travel in basis points (1 bp = 0.01 %, 1250 = 12.50 %); the screens show and take percentages with two
   decimals. These helpers are the one place both directions are converted. */

/** Basis points as a percentage with two decimals, without the sign ("12.50"). */
export function bpsToPercentText(bps: number): string {
  const sign = bps < 0 ? '-' : '';
  const abs = Math.abs(Math.round(bps));
  return `${sign}${Math.floor(abs / 100)}.${String(abs % 100).padStart(2, '0')}`;
}

/** Basis points as a percentage label ("12.50%"). */
export function formatBps(bps: number): string {
  return `${bpsToPercentText(bps)}%`;
}

/**
 * Parses a percentage typed by a person ("12.5", "12.50", "12.50 %", "0") into basis points, exactly (no floating
 * point): at most two decimals. Returns null for anything else (empty, negative, letters, three decimals).
 */
export function parsePercent(text: string): number | null {
  const t = text.trim().replace(/\s*%$/, '').trim();
  const m = /^(\d{1,5})(?:\.(\d{0,2}))?$/.exec(t);
  if (!m) return null;
  const whole = Number(m[1]);
  const frac = (m[2] ?? '').padEnd(2, '0');
  return whole * 100 + Number(frac);
}

/** Commission (or any rate) on an amount in minor units, half-up like payments' split. */
export function applyBps(amountMinor: number, bps: number): number {
  return Math.floor((amountMinor * bps + 5_000) / 10_000);
}
