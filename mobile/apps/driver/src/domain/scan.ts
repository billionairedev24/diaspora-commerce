/* S083/S084 label scanning (pure; unit-tested). Shipment labels are Code 128 "ATS" + 9 characters. */

export const LABEL_RE = /^ATS[0-9A-Z]{9}$/;

export function normalizeLabel(raw: string): string {
  return raw.trim().toUpperCase().replace(/\s+/g, '');
}

export type ScanCheck = { ok: true; label: string } | { ok: false; message: string };

/** Unknown or already-scanned labels are rejected (design copy). */
export function checkScan(raw: string, expected: string[], scanned: string[]): ScanCheck {
  const label = normalizeLabel(raw);
  if (!label) return { ok: false, message: 'Scan or type a label number.' };
  if (!LABEL_RE.test(label)) return { ok: false, message: 'Not an AfroTransact shipment label (ATS + 9 characters).' };
  if (!expected.includes(label)) return { ok: false, message: `${label} isn’t expected here. Set it aside and tell dispatch.` };
  if (scanned.includes(label)) return { ok: false, message: `${label} was already scanned.` };
  return { ok: true, label };
}

/** Camera scanners fire repeatedly while a label stays in frame: ignore the same code within the window. */
export function isRepeat(last: { label: string; at: number } | null, label: string, now: number, windowMs = 2500): boolean {
  return !!last && last.label === label && now - last.at < windowMs;
}
