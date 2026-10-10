/* Bookings (S167): booking copy and rules derived from the bookings-service contract. */
import { formatMoney } from '@afrotransact/api-client';

export interface QuoteLike {
  depositMinor?: number;
  refundMinor?: number;
  refundSharePercent?: number;
  policy?: string;
  currency?: string;
}

/** Exact refund shown before cancelling (acceptance criterion of S167). */
export function cancelText(q: QuoteLike): string {
  const deposit = q.depositMinor ?? 0;
  const refund = q.refundMinor ?? 0;
  const cur = q.currency ?? 'USD';
  if (!deposit) return 'No charge to cancel.';
  if (refund >= deposit) return `You’ll get your full ${formatMoney(deposit, cur)} deposit back.`;
  if (refund > 0) return `You’ll get ${formatMoney(refund, cur)} of your ${formatMoney(deposit, cur)} deposit back under the ${q.policy ?? 'cancellation'} policy.`;
  return `You’re inside the cancellation window, so the ${formatMoney(deposit, cur)} deposit isn’t refunded.`;
}

/** Statuses of bookings-service's BookingStatus that hold calendar time. */
export const ACTIVE_BOOKING_STATUSES = ['PENDING_PAYMENT', 'REQUESTED', 'CONFIRMED', 'RESCHEDULE_REQUESTED', 'RESCHEDULE_PROPOSED'];

export function isActive(status: string | undefined): boolean {
  return ACTIVE_BOOKING_STATUSES.includes((status ?? '').toUpperCase());
}

export type BookingAction = 'accept-new-time' | 'decline-new-time' | 'reschedule' | 'add-to-calendar' | 'cancel' | 'book-again';

/** Actions on the booking detail screen, by status (design: Appointment screen). */
export function bookingActions(status: string | undefined): BookingAction[] {
  switch ((status ?? '').toUpperCase()) {
    case 'RESCHEDULE_PROPOSED':
      return ['accept-new-time', 'decline-new-time'];
    case 'REQUESTED':
    case 'CONFIRMED':
      return ['reschedule', 'add-to-calendar', 'cancel'];
    case 'RESCHEDULE_REQUESTED':
      return ['add-to-calendar', 'cancel'];
    case 'COMPLETED':
      return ['book-again'];
    default:
      return [];
  }
}

export function bookButtonLabel(instant: boolean, depositMinor: number, currency = 'USD', hasSlot = true): string {
  if (!hasSlot) return 'Pick a time';
  return `${instant ? 'Book' : 'Request'} · ${formatMoney(depositMinor, currency)} deposit`;
}

export function bookingFinePrint(instant: boolean, providerName: string): string {
  return instant
    ? 'Confirmed instantly. Deposit charged now, balance after the appointment.'
    : `Your card is authorised, not charged, until ${providerName} confirms (within 4 h). If they don’t, the hold is released.`;
}

/** "Sat, Oct 12 · 10:00 AM" in the provider's time zone. */
export function formatWhen(iso: string, timeZone?: string): string {
  const d = new Date(iso);
  const day = d.toLocaleDateString('en-US', { weekday: 'short', month: 'short', day: 'numeric', ...(timeZone ? { timeZone } : {}) });
  const time = d.toLocaleTimeString('en-US', { hour: 'numeric', minute: '2-digit', ...(timeZone ? { timeZone } : {}) });
  return `${day} · ${time}`;
}
