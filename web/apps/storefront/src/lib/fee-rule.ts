import { type DeliveryFeeRuleRef } from '@afrotransact/api-client';

import { type MessageKey, type Translator } from '@/i18n/t';

/**
 * One line saying which staff delivery fee rule a fee comes from (ADR 0005 §3): the distance band for AfroTransact delivery,
 * a pre-order's own fee, the pickup fee, or the carrier rate. Shown beside the amount in the cart, at checkout and on the
 * order, so a shopper can see why the fee is what it is.
 */
export function feeRuleNote(t: Translator<MessageKey>, rule: DeliveryFeeRuleRef): string {
  if (rule.source === 'WINDOW_OVERRIDE') return t('fee.rule.override');
  switch (rule.method) {
    case 'PICKUP':
      return t('fee.rule.pickup');
    case 'CARRIER':
      return rule.source === 'CARRIER_QUOTE' ? t('fee.rule.carrierQuote') : t('fee.rule.carrierFallback');
    case 'AFROTRANSACT_DELIVERY':
      return rule.band ? t('fee.rule.band', { band: rule.band }) : t('fee.rule.door');
  }
}
