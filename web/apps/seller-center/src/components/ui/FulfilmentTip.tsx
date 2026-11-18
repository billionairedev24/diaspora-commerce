'use client';

import { InfoTip } from '@afrotransact/ui';

import { type MessageKey, useT } from '@/i18n/t';

/** The ways a customer gets an order, each explained in one or two plain sentences in a tooltip instead of long inline text. */
export type FulfilmentTipMode = 'pickup' | 'fba' | 'sellerShips';

const TEXT: Record<FulfilmentTipMode, MessageKey> = { pickup: 'sc.ff.tip.pickup', fba: 'sc.ff.tip.fba', sellerShips: 'sc.ff.tip.sellerShips' };
const NAME: Record<FulfilmentTipMode, MessageKey> = { pickup: 'sc.ff.mode.pickup', fba: 'sc.ff.mode.fba', sellerShips: 'sc.ff.mode.sellerShips' };

/** An info button (keyboard focus, tap, aria-describedby) that explains one fulfilment mode. Uses the shared InfoTip. */
export function FulfilmentTip({ mode }: { mode: FulfilmentTipMode }) {
  const t = useT();
  return <InfoTip label={t('sc.ff.tip.label', { mode: t(NAME[mode]) })}>{t(TEXT[mode])}</InfoTip>;
}
