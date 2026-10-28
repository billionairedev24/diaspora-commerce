import { isApiError, type MembershipBenefit, type MembershipInterval } from '@afrotransact/api-client';
import { type Formatters } from '@afrotransact/i18n';

import { type MessageKey, type Translator } from '@/i18n/t';

/* Words for membership (S323): the same benefit reads the same on the offer page, the account page, the checkout and the
   order. Tiers and their numbers come from staff settings, so nothing here knows what Plus gives. */

const BENEFIT_KEYS: Record<string, MessageKey> = {
  FREE_DELIVERY: 'mem.b.FREE_DELIVERY',
  FREE_SHIPPING: 'mem.b.FREE_SHIPPING',
  CASHBACK: 'mem.b.CASHBACK',
  MEMBER_PRICES: 'mem.b.MEMBER_PRICES',
  EARLY_ACCESS: 'mem.b.EARLY_ACCESS',
  PRIORITY_SUPPORT: 'mem.b.PRIORITY_SUPPORT',
  FREE_RETURNS: 'mem.b.FREE_RETURNS',
  BOOSTED_REWARDS: 'mem.b.BOOSTED_REWARDS',
  MONTHLY_CREDIT: 'mem.b.MONTHLY_CREDIT'
};

const percentText = (fmt: Formatters, bps: number | null) => `${fmt.number((bps ?? 0) / 100, { maximumFractionDigits: 2 })}%`;

/** "Free AfroTransact delivery", "2% back as credit on your orders"… */
export function benefitTitle(t: Translator<MessageKey>, fmt: Formatters, b: MembershipBenefit): string {
  const key = BENEFIT_KEYS[b.code];
  if (!key) return b.code;
  return t(key, {
    percent: percentText(fmt, b.percentBps),
    hours: String(b.hours ?? 0),
    amount: fmt.money(b.amountMinor ?? 0)
  });
}

/** The order minimum and cap of a delivery or shipping benefit ("On orders from $25.00, up to $15.00 off per order"). */
export function benefitDetail(t: Translator<MessageKey>, fmt: Formatters, b: MembershipBenefit, currency: string): string | null {
  if (b.code !== 'FREE_DELIVERY' && b.code !== 'FREE_SHIPPING' && b.code !== 'CASHBACK') return null;
  const min = b.minOrderMinor ?? 0;
  if (b.code === 'CASHBACK' && min === 0 && b.capMinor === null) return null;
  const base = min > 0 ? t('mem.b.minOrder', { min: fmt.money(min, currency) }) : t('mem.b.everyOrder');
  return b.capMinor === null ? base : `${base}, ${t('mem.b.cap', { cap: fmt.money(b.capMinor, currency) })}`;
}

/** Name of a benefit in a sentence ("Free AfroTransact delivery") when the benefit has no numbers in its title. */
export function benefitName(t: Translator<MessageKey>, fmt: Formatters, code: string): string {
  const key = BENEFIT_KEYS[code];
  return key ? benefitTitle(t, fmt, { code: code as MembershipBenefit['code'], minOrderMinor: null, capMinor: null, percentBps: null, hours: null, amountMinor: null }) : code;
}

export const everyKey = (interval: MembershipInterval): MessageKey => (interval === 'YEARLY' ? 'mem.every.YEARLY' : 'mem.every.MONTHLY');

const ERROR_KEYS: Record<string, MessageKey> = {
  MEMBERSHIP_NO_CARD: 'mem.err.MEMBERSHIP_NO_CARD',
  MEMBERSHIP_CHARGE_FAILED: 'mem.err.MEMBERSHIP_CHARGE_FAILED',
  MEMBERSHIP_AUTHENTICATION_REQUIRED: 'mem.err.MEMBERSHIP_AUTHENTICATION_REQUIRED',
  MEMBERSHIP_EXISTS: 'mem.err.MEMBERSHIP_EXISTS',
  MEMBERSHIP_PAST_DUE: 'mem.err.MEMBERSHIP_PAST_DUE',
  MEMBERSHIP_REFUND_NOT_ELIGIBLE: 'mem.err.MEMBERSHIP_REFUND_NOT_ELIGIBLE'
};

/** What went wrong, in the shopper's words: the answers a shopper can act on, else a generic line. */
export function membershipError(t: Translator<MessageKey>, e: unknown): string {
  const key = isApiError(e) && e.code ? ERROR_KEYS[e.code] : undefined;
  return t(key ?? 'mem.err.generic');
}
