import { describe, expect, it } from 'vitest';

import { cardBrand, postalNeeded, validateBank, validateCard } from '../src/domain/paymentForm';

const now = new Date('2026-10-10T12:00:00Z');
const ok = { number: '4242 4242 4242 4242', expiry: '08/29', cvc: '123', country: 'US', postal: '77032' };

describe('card form', () => {
  it('detects the brand', () => {
    expect(cardBrand('4242')).toBe('VISA');
    expect(cardBrand('5555')).toBe('MASTERCARD');
    expect(cardBrand('')).toBe('');
  });
  it('requires a ZIP for US cards', () => {
    expect(validateCard(ok, now)).toBeNull();
    expect(validateCard({ ...ok, postal: '' }, now)).toBe('Enter your 5-digit ZIP code.');
    expect(validateCard({ ...ok, country: 'NG', postal: '' }, now)).toBeNull();
    expect(postalNeeded('GH')).toBe(false);
  });
  it('rejects expired cards and bad CVCs', () => {
    expect(validateCard({ ...ok, expiry: '09/26' }, now)).toMatch(/expiry/);
    expect(validateCard({ ...ok, expiry: '10/26' }, now)).toBeNull();
    expect(validateCard({ ...ok, cvc: '12' }, now)).toMatch(/CVC/);
  });
});

describe('bank account form', () => {
  it('validates routing and account numbers', () => {
    const b = { holder: 'Adaeze Okafor', routing: '110000000', account: '000123456789', confirm: '000123456789' };
    expect(validateBank(b)).toBeNull();
    expect(validateBank({ ...b, routing: '1100' })).toBe('Routing numbers are 9 digits.');
    expect(validateBank({ ...b, confirm: '000123456780' })).toBe('Account numbers don’t match.');
  });
});
