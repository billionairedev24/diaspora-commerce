/* Returns (S114, S166): pick the items of one shipment, a reason and a method; the result is an RMA with a QR code.
   Food and other perishables can only be reported as a problem (no return), and only with a seller-fault reason. */
import { SELLER_FAULT_REASONS } from '@afrotransact/api-client';

import type { ReturnableLine, ReturnMethod, ReturnReason } from '@/api/types';

export const RETURN_REASONS: ReadonlyArray<{ label: string; code: ReturnReason }> = [
  { label: 'Damaged in transit', code: 'DAMAGED' },
  { label: 'Wrong item', code: 'WRONG_ITEM' },
  { label: 'Not as described', code: 'NOT_AS_DESCRIBED' },
  { label: 'Expired or spoiled', code: 'EXPIRED_OR_SPOILED' },
  { label: 'No longer needed', code: 'NO_LONGER_NEEDED' }
];

export const METHOD_COPY: Record<ReturnMethod, { title: string; detail: string }> = {
  DROP_OFF: { title: 'UPS drop-off · free', detail: 'Show a QR code at any UPS Store. No box or label.' },
  DRIVER_PICKUP: { title: 'Driver pickup', detail: 'Hand it to your AfroTransact driver on your next delivery.' },
  NO_RETURN: { title: 'Report a problem', detail: 'No need to send it back. We refund you once we have reviewed it.' }
};

/** The lines of a shipment a person can act on (returnable, or reportable for food). */
export const actionable = (lines: ReturnableLine[]): ReturnableLine[] => lines.filter((l) => l.eligible);

/** Reasons that fit the chosen lines: food can only be reported with a seller-fault reason. */
export function reasonsFor(lines: ReturnableLine[]): ReadonlyArray<{ label: string; code: ReturnReason }> {
  return lines.some((l) => l.reportProblemOnly) ? RETURN_REASONS.filter((r) => SELLER_FAULT_REASONS.includes(r.code)) : RETURN_REASONS;
}

/** Methods every chosen line allows (the order is the first line's). */
export function commonMethods(lines: ReturnableLine[]): ReturnMethod[] {
  return lines.reduce<ReturnMethod[]>((acc, l, i) => (i === 0 ? [...l.methods] : acc.filter((m) => l.methods.includes(m))), []);
}

export function validateReturn(selected: string[], reason: ReturnReason | null, method: ReturnMethod | null = 'DROP_OFF'): string | null {
  if (!selected.length) return 'Choose at least one item.';
  if (!reason) return 'Choose a reason.';
  if (!method) return 'These items can’t be sent back together. Choose fewer items.';
  return null;
}

/** What the items add up to (before any restocking fee; the exact refund is quoted by the server). */
export function refundTotal(items: Array<{ atin: string; priceMinor: number }>, selected: string[]): number {
  return items.filter((i) => selected.includes(i.atin)).reduce((a, i) => a + i.priceMinor, 0);
}

export const lineTotal = (l: ReturnableLine): number => l.unitPriceMinor * l.maxQuantity;

/** Payload of the return QR code when the server sent none (scanned at the UPS counter or by the driver). */
export function returnQrPayload(rmaIds: string[], method: 'ups' | 'driver' | ReturnMethod): string {
  const m = method === 'DROP_OFF' ? 'ups' : method === 'DRIVER_PICKUP' ? 'driver' : method;
  return `afrotransact:return:${m}:${rmaIds.join(',')}`;
}

export function returnDoneText(method: ReturnMethod | 'ups' | 'driver'): string {
  if (method === 'NO_RETURN') return 'We have your report and will review it. You do not need to send anything back.';
  return method === 'driver' || method === 'DRIVER_PICKUP'
    ? 'Hand the items to your driver with this code. Your refund is issued as soon as it’s scanned.'
    : 'Show this code at any UPS Store — no box or label needed. Your refund is issued as soon as it’s scanned.';
}
