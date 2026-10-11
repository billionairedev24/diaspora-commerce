/* The labelled dev-only stand-in for Stripe's Payment Element (no publishable key, or the mock BFF's pk_test_mock):
   card checks with Stripe's test numbers and Stripe-style messages. Never used once STRIPE_PUBLISHABLE_KEY is set. */

export const digits = (s: string) => s.replace(/\D/g, '');

export function luhn(num: string): boolean {
  const d = digits(num);
  if (d.length < 12 || d.length > 19) return false;
  let sum = 0;
  for (let i = 0; i < d.length; i += 1) {
    let n = Number(d[d.length - 1 - i]);
    if (i % 2 === 1) n = n * 2 > 9 ? n * 2 - 9 : n * 2;
    sum += n;
  }
  return sum % 10 === 0;
}

export function cardBrand(num: string): string {
  const d = digits(num);
  if (d.startsWith('4')) return 'visa';
  if (/^(5[1-5]|2[2-7])/.test(d)) return 'mastercard';
  if (/^3[47]/.test(d)) return 'amex';
  if (/^6(011|5)/.test(d)) return 'discover';
  return 'card';
}

/** Stripe's test card that is always declined. */
export const DECLINED_CARD = '4000000000009995';
/** Stripe's test card that always asks for 3-D Secure. */
export const THREE_DS_CARD = '4000002760003184';

export interface MockCard {
  number: string;
  exp: string;
  cvc: string;
  zip: string;
}

export type MockCardErrorKey = 'pay.cardNumberInvalid' | 'pay.expInvalid' | 'pay.cvcInvalid' | 'pay.zipInvalid';

/** Field errors of a mock card (declines are decided at confirmation, like Stripe). */
export function checkMockCard(c: MockCard, now: Date = new Date()): Partial<Record<keyof MockCard, MockCardErrorKey>> {
  const out: Partial<Record<keyof MockCard, MockCardErrorKey>> = {};
  const [mm, yy] = c.exp.split('/').map((x) => Number(x.trim()));
  const expYear = 2000 + (yy ?? 0);
  if (!luhn(c.number)) out.number = 'pay.cardNumberInvalid';
  if (!mm || mm > 12 || !yy || expYear < now.getFullYear() || (expYear === now.getFullYear() && mm < now.getMonth() + 1)) out.exp = 'pay.expInvalid';
  if (!/^\d{3,4}$/.test(c.cvc)) out.cvc = 'pay.cvcInvalid';
  if (!/^\d{5}$/.test(c.zip)) out.zip = 'pay.zipInvalid';
  return out;
}
