/* Pre-order copy, exactly as design/AfroTransact Pre-Order v2.dc.html words it (ribbon, kicker, CTA, notes, rules,
   pay notes, locked text), built from the resolution and the window's payment setup. Pure: components and tests call
   it with a translator and formatters. Dates are shown in the window's market time zone (delivery dates are calendar
   days), so server and client render the same strings. */
import { type PreorderPaymentMethod, type PreorderStage, type PreorderTerms } from '@afrotransact/api-client';
import { type Formatters } from '@afrotransact/i18n';

import { type MessageKey, type Translator } from '@/i18n/t';

type T = Translator<MessageKey>;
type Money = Pick<Formatters, 'money'>;

export interface WindowDates {
  opensAt: string;
  closesAt: string;
  deliversOn: string;
  timeZone: string;
}

export interface PreorderDateLabels {
  /** "Tue, Oct 6, 9:00 AM" */
  opensT: string;
  /** "Thu, Oct 22, 11:59 PM" */
  closesT: string;
  /** "Sun, Oct 25" */
  deliversT: string;
  /** "Oct 6" */
  opensS: string;
  closesS: string;
  deliversS: string;
}

const dayOf = (iso: string) => new Date(`${iso}T12:00:00Z`);

/** The design's fD / fS / fDT for a window's dates. */
export function preorderDates(w: WindowDates, tag: string): PreorderDateLabels {
  const tz = w.timeZone;
  const fD = (d: Date, timeZone: string) => d.toLocaleDateString(tag, { weekday: 'short', month: 'short', day: 'numeric', timeZone });
  const fS = (d: Date, timeZone: string) => d.toLocaleDateString(tag, { month: 'short', day: 'numeric', timeZone });
  const fDT = (d: Date) => `${fD(d, tz)}, ${d.toLocaleTimeString(tag, { hour: 'numeric', minute: '2-digit', timeZone: tz })}`;
  const opens = new Date(w.opensAt);
  const closes = new Date(w.closesAt);
  const delivers = dayOf(w.deliversOn);
  return { opensT: fDT(opens), closesT: fDT(closes), deliversT: fD(delivers, 'UTC'), opensS: fS(opens, tz), closesS: fS(closes, tz), deliversS: fS(delivers, 'UTC') };
}

type StageKey = 'open' | 'upcoming' | 'closed';
export const stageKey = (s: PreorderStage): StageKey => (s === 'OPEN' ? 'open' : s === 'UPCOMING' ? 'upcoming' : 'closed');

/** "$7.99 flat or free pickup" from the window's door / pickup setup (never hard-coded). */
export function deliveryTerms(t: T, fmt: Money, terms: Pick<PreorderTerms, 'door' | 'pickup' | 'doorFeeMinor' | 'pickupFeeMinor' | 'currency'>): string {
  const door = terms.door ? (terms.doorFeeMinor > 0 ? t('preorder.terms.door', { fee: fmt.money(terms.doorFeeMinor, terms.currency) }) : t('preorder.terms.doorFree')) : '';
  const pickup = terms.pickup ? (terms.pickupFeeMinor > 0 ? t('preorder.terms.pickup', { fee: fmt.money(terms.pickupFeeMinor, terms.currency) }) : t('preorder.terms.pickupFree')) : '';
  return door && pickup ? t('preorder.terms.or', { a: door, b: pickup }) : door || pickup;
}

const METHOD_KEY: Record<PreorderPaymentMethod, MessageKey> = { CARD: 'preorder.pay.card', WALLET: 'preorder.pay.wallet', ACH: 'preorder.pay.ach' };

/** "card, Apple Pay or ACH" from the window's allowed payment methods. */
export function paymentMethodsText(t: T, methods: readonly PreorderPaymentMethod[]): string {
  const names = [...new Set(methods)].map((m) => t(METHOD_KEY[m]));
  if (names.length <= 1) return names[0] ?? '';
  return t('preorder.terms.or', { a: names.slice(0, -1).join(', '), b: names[names.length - 1] ?? '' });
}

export interface StageCopyInput {
  stage: PreorderStage;
  city: string;
  /** The window's own ribbon text (shown while open). */
  ribbon: string;
  headline: string;
  phone: string;
  dates: PreorderDateLabels;
  /** Formatted door fee ("$7.99"). */
  doorFee: string;
}

export interface StageCopy {
  ribbon: string;
  ribbonSub: string;
  kicker: string;
  stageLabel: string;
  cdLabel: string;
  cta: string;
  headline: string;
  pdpNote: string;
  /** Product card tag line: "Pre-order · delivered Oct 25" / "Pre-order opens Oct 15" / "Pre-order closed". */
  tag: string;
  /** Disabled add-to-cart label on cards (null while open). */
  addLabel: string | null;
  /** Disabled add-to-cart label on the PDP (null while open). */
  pdpAddLabel: string | null;
  /** Cart / checkout explanation when the window is not open (null while open). */
  lockedText: string | null;
}

/** Every stage-dependent string of the storefront (spec §5). */
export function stageCopy(t: T, c: StageCopyInput): StageCopy {
  const k = stageKey(c.stage);
  const d = c.dates;
  const vars = { city: c.city, opens: d.opensT, closes: d.closesT, delivers: d.deliversT, phone: c.phone };
  return {
    ribbon: k === 'open' ? c.ribbon : k === 'upcoming' ? t('preorder.ribbon.upcoming', { city: c.city, date: d.opensS }) : t('preorder.ribbon.closed', { city: c.city }),
    ribbonSub:
      k === 'open'
        ? t('preorder.ribbonSub.open', { closes: d.closesT, delivers: d.deliversT, fee: c.doorFee })
        : k === 'upcoming'
          ? t('preorder.ribbonSub.upcoming', { opens: d.opensS, closes: d.closesS, delivers: d.deliversT })
          : t('preorder.ribbonSub.closed', { delivers: d.deliversT, phone: c.phone }),
    kicker: t(`preorder.kicker.${k}`, { city: c.city }),
    stageLabel: t(`preorder.stage.${k}`),
    cdLabel: t(`preorder.cd.${k}`),
    cta: t(`preorder.cta.${k}`),
    headline: k === 'closed' ? t('preorder.headline.closed', { city: c.city, delivers: d.deliversT }) : c.headline,
    pdpNote: k === 'open' ? t('preorder.pdp.noteOpen', vars) : k === 'upcoming' ? t('preorder.pdp.noteUpcoming', vars) : t('preorder.pdp.noteClosed', vars),
    tag: k === 'open' ? t('preorder.tag.open', { date: d.deliversS }) : k === 'upcoming' ? t('preorder.tag.upcoming', { date: d.opensS }) : t('preorder.tag.closed'),
    addLabel: k === 'open' ? null : k === 'upcoming' ? t('preorder.add.opens', { date: d.opensS }) : t('preorder.add.closed'),
    pdpAddLabel: k === 'open' ? null : k === 'upcoming' ? t('preorder.pdp.addUpcoming', { opens: d.opensT }) : t('preorder.pdp.addClosed'),
    lockedText: k === 'open' ? null : k === 'upcoming' ? t('preorder.locked.upcoming', { opens: d.opensT }) : t('preorder.locked.closed', { closes: d.closesT, phone: c.phone })
  };
}

/** The four "How this pre-order works" bullets. */
export function preorderRules(t: T, fmt: Money, dates: PreorderDateLabels, terms: Parameters<typeof deliveryTerms>[2], phone: string): string[] {
  return [
    t('preorder.rules.window', { opens: dates.opensT, closes: dates.closesT }),
    t('preorder.rules.pay'),
    t('preorder.rules.delivery', { delivers: dates.deliversT, terms: deliveryTerms(t, fmt, terms) }),
    t('preorder.rules.missed', { phone })
  ];
}

/** The four "How payment works" notes of the cart summary. */
export function payNotes(t: T, input: { total: string; methods: readonly PreorderPaymentMethod[]; dates: PreorderDateLabels; split: boolean }): string[] {
  return [
    t('preorder.pay.one', { total: input.total, methods: paymentMethodsText(t, input.methods) }),
    input.split ? t('preorder.pay.split', { delivers: input.dates.deliversT }) : t('preorder.pay.together', { delivers: input.dates.deliversT }),
    t('preorder.pay.cancel', { closes: input.dates.closesT }),
    t('preorder.pay.missed')
  ];
}

/** "Lubbock, El Paso, Toronto + 1 more" for the send-to-family banner. */
export function familyCities(t: T, cities: readonly string[]): string {
  const unique = [...new Set(cities)];
  const head = unique.slice(0, 3).join(', ');
  return unique.length > 3 ? t('preorder.family.more', { cities: head, n: unique.length - 3 }) : head;
}
