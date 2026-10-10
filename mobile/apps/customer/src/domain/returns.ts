/* Returns (S166): pick items, a reason and a method; the result is an RMA with a QR code. */

export const RETURN_REASONS = [
  { label: 'Damaged in transit', code: 'DAMAGED' },
  { label: 'Wrong item', code: 'WRONG_ITEM' },
  { label: 'Close to expiry', code: 'NOT_AS_DESCRIBED' },
  { label: 'No longer needed', code: 'NO_LONGER_NEEDED' }
] as const;

export type ReturnReasonCode = (typeof RETURN_REASONS)[number]['code'];

export const RETURN_METHODS = [
  { key: 'ups', title: 'UPS drop-off · free', detail: 'Show a QR code at any UPS Store. No box or label.' },
  { key: 'driver', title: 'Driver pickup', detail: 'Hand it to your AfroTransact driver on your next delivery.' }
] as const;

export type ReturnMethod = (typeof RETURN_METHODS)[number]['key'];

export interface ReturnableItem {
  atin: string;
  title: string;
  priceMinor: number;
}

export function validateReturn(selected: string[], reason: ReturnReasonCode | null): string | null {
  if (!selected.length) return 'Choose at least one item.';
  if (!reason) return 'Choose a reason.';
  return null;
}

export function refundTotal(items: ReturnableItem[], selected: string[]): number {
  return items.filter((i) => selected.includes(i.atin)).reduce((a, i) => a + i.priceMinor, 0);
}

/** Payload encoded in the return QR code (scanned at the UPS counter or by the driver). */
export function returnQrPayload(rmaIds: string[], method: ReturnMethod): string {
  return `afrotransact:return:${method}:${rmaIds.join(',')}`;
}

export function returnDoneText(method: ReturnMethod): string {
  return method === 'driver'
    ? 'Hand the items to your driver with this code. Your refund is issued as soon as it’s scanned.'
    : 'Show this code at any UPS Store — no box or label needed. Your refund is issued as soon as it’s scanned.';
}
