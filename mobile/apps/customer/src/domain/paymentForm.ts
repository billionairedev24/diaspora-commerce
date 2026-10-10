/* Add payment method (S165): card with ZIP and US bank account (ACH). Used by the dev mock form; in Stripe mode the
   native PaymentSheet collects and validates the same fields. Messages follow the design. */

export type CardBrand = 'VISA' | 'MASTERCARD' | 'AMEX' | 'DISCOVER' | '';

export function cardBrand(number: string): CardBrand {
  const d = number.replace(/\D/g, '');
  if (d.startsWith('4')) return 'VISA';
  if (d.startsWith('5')) return 'MASTERCARD';
  if (d.startsWith('3')) return 'AMEX';
  if (d.startsWith('6')) return 'DISCOVER';
  return '';
}

export function postalLabel(country: string): string {
  return country === 'US' ? 'ZIP' : 'Postal code';
}

export function postalNeeded(country: string): boolean {
  return ['US', 'CA', 'GB'].includes(country);
}

export interface CardInput {
  number: string;
  expiry: string;
  cvc: string;
  country: string;
  postal: string;
}

/** `now` lets tests pin the current month. */
export function validateCard(c: CardInput, now = new Date()): string | null {
  const num = c.number.replace(/\D/g, '');
  if (num.length < 15 || num.length > 16) return 'Enter a valid card number.';
  const m = /^(\d{2})\s*\/?\s*(\d{2})$/.exec(c.expiry.trim());
  const month = m ? Number(m[1]) : 0;
  const year = m ? 2000 + Number(m[2]) : 0;
  if (!m || month < 1 || month > 12 || year * 12 + month < now.getFullYear() * 12 + now.getMonth() + 1) return 'Enter a valid expiry date (MM/YY).';
  if (!/^\d{3,4}$/.test(c.cvc)) return 'Enter the 3- or 4-digit CVC.';
  const z = c.postal.trim();
  if (c.country === 'US' && !/^\d{5}$/.test(z)) return 'Enter your 5-digit ZIP code.';
  if (c.country === 'CA' && !/^[a-z]\d[a-z] ?\d[a-z]\d$/i.test(z)) return 'Enter a valid postal code, e.g. M5V 2T6.';
  if (c.country === 'GB' && !/^[a-z]{1,2}\d[a-z\d]? ?\d[a-z]{2}$/i.test(z)) return 'Enter a valid postcode, e.g. SW1A 1AA.';
  return null;
}

export interface BankInput {
  holder: string;
  routing: string;
  account: string;
  confirm: string;
}

export function validateBank(b: BankInput): string | null {
  if (!b.holder.trim()) return 'Enter the account holder’s name.';
  if (b.routing.replace(/\D/g, '').length !== 9) return 'Routing numbers are 9 digits.';
  const a = b.account.replace(/\D/g, '');
  if (a.length < 4 || a.length > 17) return 'Enter a valid account number.';
  if (a !== b.confirm.replace(/\D/g, '')) return 'Account numbers don’t match.';
  return null;
}
