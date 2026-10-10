/** Formats minor units as currency, e.g. 1850 → "$18.50". */
export function formatMoney(minor: number, currency = 'USD', locale = 'en-US'): string {
  return new Intl.NumberFormat(locale, { style: 'currency', currency }).format(minor / 100);
}

/** Splits a price for the storefront's superscript rendering: 1850 → { whole: "18", cents: "50" }. */
export function splitMoney(minor: number): { whole: string; cents: string } {
  const abs = Math.abs(Math.round(minor));
  return { whole: Math.floor(abs / 100).toLocaleString('en-US'), cents: String(abs % 100).padStart(2, '0') };
}

export function percentOff(priceMinor: number, listMinor: number | null): number {
  if (!listMinor || listMinor <= priceMinor) return 0;
  return Math.round((1 - priceMinor / listMinor) * 100);
}
