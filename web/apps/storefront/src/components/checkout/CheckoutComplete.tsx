'use client';

import { cartKeys, isApiError, type OrderConfirmation, orderKeys } from '@afrotransact/api-client';
import { buttonClassName, CircleNotch } from '@afrotransact/ui';
import { useQueryClient } from '@tanstack/react-query';
import Link from 'next/link';
import { useRouter, useSearchParams } from 'next/navigation';
import { useEffect, useState } from 'react';

import { useT } from '@/i18n/t';
import { browserApi } from '@/lib/api/browser';

import styles from './Checkout.module.css';

type State = { kind: 'checking' } | { kind: 'failed'; message?: string } | { kind: 'pending' } | { kind: 'error'; message: string };

const WAITING = new Set<OrderConfirmation['status']>(['processing', 'awaiting_payment', 'requires_action']);

/**
 * Where Stripe returns the shopper after a redirect-based confirmation (S282). The page never trusts the query string:
 * it asks the BFF to confirm the order's payment (orders reads the PaymentIntent from payments, refreshed from Stripe)
 * and polls until the order is placed, then shows it under Your orders. A failed redirect goes back to checkout.
 */
export function CheckoutComplete({ pollMs = 1000, polls = 10 }: { pollMs?: number; polls?: number }) {
  const t = useT();
  const api = browserApi();
  const qc = useQueryClient();
  const router = useRouter();
  const params = useSearchParams();
  const order = params.get('order') ?? '';
  const redirectStatus = params.get('redirect_status');
  const [state, setState] = useState<State>({ kind: 'checking' });

  useEffect(() => {
    if (!order) return setState({ kind: 'error', message: t('co.errorTitle') });
    if (redirectStatus === 'failed') return setState({ kind: 'failed' });
    let cancelled = false;
    void (async () => {
      try {
        let status = await api.confirmCheckoutPayment(order);
        for (let i = 0; WAITING.has(status.status) && i < polls; i += 1) {
          await new Promise((r) => setTimeout(r, pollMs));
          if (cancelled) return;
          status = await api.getCheckoutStatus(order);
        }
        if (cancelled) return;
        if (status.status !== 'placed') return setState({ kind: 'pending' });
        await Promise.all([qc.invalidateQueries({ queryKey: cartKeys.all }), qc.invalidateQueries({ queryKey: orderKeys.all })]);
        router.replace(`/orders?placed=${encodeURIComponent(status.orderNumber)}`);
      } catch (e) {
        if (cancelled) return;
        if (isApiError(e) && e.status === 402) setState({ kind: 'failed', message: e.message });
        else setState({ kind: 'error', message: isApiError(e) ? e.message : t('auth.genericError') });
      }
    })();
    return () => {
      cancelled = true;
    };
    // api is a stable client per render; the effect runs once per return.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [order, redirectStatus]);

  return (
    <div className={styles.empty} data-testid="checkout-complete">
      <h1 className={styles.title}>{t('co.return.title')}</h1>
      {state.kind === 'checking' && (
        <p className={styles.muted} aria-live="polite">
          <CircleNotch className={styles.spin} aria-hidden /> {t('co.return.checking')}
        </p>
      )}
      {state.kind === 'failed' && (
        <>
          <p className={styles.error} role="alert">
            {state.message ?? t('co.return.failed')}
          </p>
          <Link href="/checkout" className={buttonClassName()}>
            {t('co.return.back')}
          </Link>
        </>
      )}
      {state.kind === 'pending' && (
        <>
          <p className={styles.muted} role="status">
            {t('co.return.pending')}
          </p>
          <Link href="/orders" className={buttonClassName()}>
            {t('co.return.orders')}
          </Link>
        </>
      )}
      {state.kind === 'error' && (
        <>
          <p className={styles.error} role="alert">
            {state.message}
          </p>
          <Link href="/checkout" className={buttonClassName()}>
            {t('co.return.back')}
          </Link>
        </>
      )}
    </div>
  );
}
