import { describe, expect, it } from 'vitest';

import { bookButtonLabel, bookingActions, cancelText, isActive } from '../src/domain/bookings';

describe('bookings', () => {
  it('shows the exact refund before cancelling', () => {
    expect(cancelText({ depositMinor: 0 })).toBe('No charge to cancel.');
    expect(cancelText({ depositMinor: 2500, refundMinor: 2500 })).toBe('You’ll get your full $25.00 deposit back.');
    expect(cancelText({ depositMinor: 2500, refundMinor: 1250, policy: 'Moderate' })).toBe('You’ll get $12.50 of your $25.00 deposit back under the Moderate policy.');
    expect(cancelText({ depositMinor: 2500, refundMinor: 0 })).toBe('You’re inside the cancellation window, so the $25.00 deposit isn’t refunded.');
  });

  it('offers actions by status', () => {
    expect(bookingActions('RESCHEDULE_PROPOSED')).toEqual(['accept-new-time', 'decline-new-time']);
    expect(bookingActions('CONFIRMED')).toContain('cancel');
    expect(bookingActions('COMPLETED')).toEqual(['book-again']);
    expect(bookingActions('CANCELLED_CUSTOMER')).toEqual([]);
    expect(isActive('REQUESTED')).toBe(true);
    expect(isActive('EXPIRED')).toBe(false);
  });

  it('labels the book button', () => {
    expect(bookButtonLabel(true, 2000)).toBe('Book · $20.00 deposit');
    expect(bookButtonLabel(false, 2000)).toBe('Request · $20.00 deposit');
    expect(bookButtonLabel(false, 2000, 'USD', false)).toBe('Pick a time');
  });
});
