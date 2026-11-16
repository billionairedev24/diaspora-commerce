'use client';

import { type ReactNode } from 'react';

import { useT } from '@/i18n/t';

import styles from './Checkout.module.css';

export type StepState = 'open' | 'done' | 'pending';

/**
 * One numbered checkout section (Amazon pattern). Open: the full body. Done or pending: the heading, a one-line summary and a
 * Change link that opens it. `keepMounted` keeps the body in the page (hidden) while collapsed: the Stripe Payment Element
 * has to stay mounted so Place your order can confirm it.
 */
export function CheckoutStep({
  n,
  id,
  title,
  state,
  summary,
  onChange,
  keepMounted = false,
  alwaysSummary = false,
  children
}: {
  n: number;
  id: string;
  title: string;
  state: StepState;
  /** The one-line summary shown while collapsed. */
  summary?: ReactNode;
  onChange?: () => void;
  keepMounted?: boolean;
  /** Show the summary line while open too (the items section: how many items and when they arrive). */
  alwaysSummary?: boolean;
  children: ReactNode;
}) {
  const t = useT();
  const open = state === 'open';
  return (
    <section className={styles.step} aria-labelledby={id} data-state={state} data-testid={`checkout-step-${n}`}>
      <span className={styles.stepNum} aria-hidden>
        {n}
      </span>
      <h2 id={id} className={styles.stepTitle}>
        {title}
      </h2>
      {!open && onChange && (
        <button type="button" className={`link-button ${styles.stepChange}`} onClick={onChange} aria-label={t('co.changeAria', { step: title })} data-testid={`checkout-change-${n}`}>
          {t('co.change')}
        </button>
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
    </section>
  );
}
