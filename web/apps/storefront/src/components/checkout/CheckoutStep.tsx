'use client';

import { CheckCircle } from '@afrotransact/ui';
import Link from 'next/link';
import { type ReactNode } from 'react';

import { useT } from '@/i18n/t';

import styles from './Checkout.module.css';

export type StepState = 'open' | 'done' | 'pending';

/**
 * One checkout section, a flat block between two rules. The marker shows where the shopper is: a number while the section is
 * to do or open, a check once it is done (and the state is said in words for screen readers). Open: the full body. Done or
 * pending: the heading, a one-line summary and a Change link that opens it. `keepMounted` keeps the body in the page (hidden)
 * while collapsed: the Stripe Payment Element has to stay mounted so Place your order can confirm it. `changeHref` makes
 * Change a link to another page (the items section changes in the cart); `extra` is always visible under the section.
 */
export function CheckoutStep({
  n,
  id,
  title,
  state,
  summary,
  onChange,
  changeHref,
  changeLabel,
  keepMounted = false,
  alwaysSummary = false,
  extra,
  children
}: {
  n: number;
  id: string;
  title: string;
  state: StepState;
  /** The one-line summary shown while collapsed. */
  summary?: ReactNode;
  onChange?: () => void;
  /** Change goes to this page instead of opening the section; shown in every state. */
  changeHref?: string;
  /** Accessible name of the Change link when it says more than the title (e.g. "Change items in your cart"). */
  changeLabel?: string;
  keepMounted?: boolean;
  /** Show the summary line while open too (the items section: how many items and when they arrive). */
  alwaysSummary?: boolean;
  /** Shown under the section whatever its state (the gift card or promo code line under payment). */
  extra?: ReactNode;
  children: ReactNode;
}) {
  const t = useT();
  const open = state === 'open';
  const aria = changeLabel ?? t('co.changeAria', { step: title });
  return (
    <section className={styles.step} aria-labelledby={id} data-state={state} data-testid={`checkout-step-${n}`}>
      <span className={styles.stepNum} data-state={state}>
        {state === 'done' ? <CheckCircle weight="fill" aria-hidden /> : <span aria-hidden>{n}</span>}
        <span className="at-visually-hidden">{t(state === 'done' ? 'co.status.done' : state === 'open' ? 'co.status.open' : 'co.status.todo')}. </span>
      </span>
      <h2 id={id} className={styles.stepTitle}>
        {title}
      </h2>
      {changeHref ? (
        <Link href={changeHref} className={`link-button ${styles.stepChange}`} aria-label={aria} data-testid={`checkout-change-${n}`}>
          {t('co.change')}
        </Link>
      ) : (
        !open &&
        onChange && (
          <button type="button" className={`link-button ${styles.stepChange}`} onClick={onChange} aria-label={aria} data-testid={`checkout-change-${n}`}>
            {t('co.change')}
          </button>
        )
      )}
      {(alwaysSummary || !open) && summary && (
        <div className={styles.stepSummary} data-testid={`checkout-summary-${n}`}>
          {summary}
        </div>
      )}
      {(open || keepMounted) && (
        <div className={styles.stepBody} hidden={!open}>
          {children}
        </div>
      )}
      {extra && <div className={styles.stepExtra}>{extra}</div>}
    </section>
  );
}
