'use client';

import { type PlanFeeCollectionMode } from '@afrotransact/api-client';
import { Badge, cx } from '@afrotransact/ui';
import { Coins, CreditCard } from '@phosphor-icons/react/ssr';

import { type MessageKey, type Translator } from '@/i18n/t';

const OPTIONS: { mode: PlanFeeCollectionMode; title: MessageKey; help: MessageKey }[] = [
  { mode: 'EARNINGS_FIRST', title: 'sc.billing.planFee.earnings', help: 'sc.billing.planFee.earningsHelp' },
  { mode: 'CARD', title: 'sc.billing.planFee.card', help: 'sc.billing.planFee.cardHelp' }
];

/**
 * How the plan is paid (S298), as two radio cards: "Pay your plan from your sales" (each invoice taken from pending
 * earnings, the card pays the rest) or "Charge my card" (a separate card expense). Plan & billing and the onboarding
 * Plan step.
 */
export function PlanFeeChoice({ value, onChange, disabled, t, platformDefault }: {
  value: PlanFeeCollectionMode;
  onChange: (mode: PlanFeeCollectionMode) => void;
  disabled?: boolean;
  t: Translator<MessageKey>;
  /** Marks the platform default (onboarding). */
  platformDefault?: PlanFeeCollectionMode;
}) {
  return (
    <div role="radiogroup" aria-label={t('sc.billing.planFee.title')} className="grid grid-cols-1 gap-3 md:grid-cols-2">
      {OPTIONS.map((o) => {
        const on = value === o.mode;
        return (
          <button
            key={o.mode}
            type="button"
            role="radio"
            aria-checked={on}
            disabled={disabled}
            onClick={() => onChange(o.mode)}
            className={cx(
              'flex cursor-pointer flex-col gap-1 rounded-md border-2 p-4 text-left text-text disabled:cursor-default',
              on ? 'border-gold-500 bg-gold-50' : 'border-border bg-surface hover:border-border-strong'
            )}
          >
            <span className="flex items-center gap-2">
              {o.mode === 'EARNINGS_FIRST' ? <Coins size={20} aria-hidden /> : <CreditCard size={20} aria-hidden />}
              <strong>{t(o.title)}</strong>
              {platformDefault === o.mode ? <Badge tone="neutral">{t('sc.billing.planFee.default')}</Badge> : null}
            </span>
            <span className="text-sm text-muted">{t(o.help)}</span>
          </button>
        );
      })}
    </div>
  );
}
