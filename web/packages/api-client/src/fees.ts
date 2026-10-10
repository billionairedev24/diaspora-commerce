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

/* AfroTransact Delivery from the seller's store (S255): payments' tariff rules, used by the staff editor's quote preview
   of a draft (the live version is quoted by payments through the BFF). */

/** Structural tariff (DeliveryTariff of the staff and Seller Center contracts). */
export interface TariffLike {
  mode: 'FLAT' | 'DISTANCE';
  flatPerPackageMinor: number | null;
  baseMinor: number | null;
  perMileMinor: number | null;
  bands: { fromMiles: number; toMiles: number; feeMinor: number }[];
  minMinor: number | null;
  maxMinor: number | null;
}

/** FLAT: per package × packages. DISTANCE (per trip): the band covering the miles, else base + per mile × miles (half
 *  up), then at least min and at most max. A null tariff is free. */
export function quoteTariff(t: TariffLike | null, miles: number, packages = 1): number {
  if (!t) return 0;
  if (t.mode === 'FLAT') return (t.flatPerPackageMinor ?? 0) * Math.max(1, packages);
  const band = t.bands.find((b) => miles >= b.fromMiles && miles < b.toMiles);
  let fee = band ? band.feeMinor : (t.baseMinor ?? 0) + Math.floor((t.perMileMinor ?? 0) * miles + 0.5);
  if (t.minMinor !== null && fee < t.minMinor) fee = t.minMinor;
  if (t.maxMinor !== null && fee > t.maxMinor) fee = t.maxMinor;
  return fee;
}

const usd = (minor: number) => `$${Math.floor(minor / 100)}.${String(minor % 100).padStart(2, '0')}`;

/** One line: "$2.00 per package", "$4.99 + $0.85/mi, at least $5.99, at most $24.99" (the BFF's TariffSummary). */
export function tariffSummary(t: TariffLike | null): string {
  if (!t) return 'No fee';
  if (t.mode === 'FLAT') return `${usd(t.flatPerPackageMinor ?? 0)} per package`;
  const bands = t.bands.length ? `${t.bands.map((b) => `${b.fromMiles}–${b.toMiles} mi ${usd(b.feeMinor)}`).join(', ')}; otherwise ` : '';
  let s = `${bands}${usd(t.baseMinor ?? 0)} + ${usd(t.perMileMinor ?? 0)}/mi`;
  if (t.minMinor !== null && t.minMinor > 0) s += `, at least ${usd(t.minMinor)}`;
  if (t.maxMinor !== null) s += `, at most ${usd(t.maxMinor)}`;
  return s;
}
