'use client';

import { isApiError, type PayLink, payLinkKeys } from '@afrotransact/api-client';
import { useFormatters } from '@afrotransact/i18n/react';
import { Button, buttonClassName, CheckCircle, LockSimple, Skeleton, Warning } from '@afrotransact/ui';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import Link from 'next/link';
import { useSearchParams } from 'next/navigation';
import { useEffect, useRef, useState } from 'react';

import { PaymentElementField, type PaymentElementHandle, REDIRECTED } from '@/components/payments/PaymentElementField';
import { QuickPay } from '@/components/payments/QuickPay';
import { useT } from '@/i18n/t';
import { browserApi } from '@/lib/api/browser';
import { shopperQueries } from '@/lib/api/queries';
import { type ConfirmResult } from '@/lib/payments/stripe';

import styles from './PayNow.module.css';

/** Where Stripe sends the customer back after a redirect-based method (3-D Secure page, Cash App, bank). */
const returnUrl = (orderNumber: string, token: string) =>
  `${window.location.origin}/orders/${encodeURIComponent(orderNumber)}/pay?token=${encodeURIComponent(token)}`;

/**
 * "Complete your pre-order payment" (late pre-order adds). The order already has a manual-capture PaymentIntent; the
 * customer confirms it here with the same Stripe Elements and Appearance as checkout (Payment Element + Express
 * Checkout, stripe.confirmPayment({ redirect: 'if_required' })), then orders places the order. The page refuses
 * invalid (404), expired or voided (410) and paid links: no client secret, nothing to pay. Paying is single-flight; a
 * retry after a decline confirms the same PaymentIntent again, so the customer is never charged twice.
 */
export function PayNowView({ orderNumber }: { orderNumber: string }) {
  const t = useT();
  const fmt = useFormatters();
  const api = browserApi();
  const qc = useQueryClient();
  const params = useSearchParams();
  const token = params.get('token') ?? '';
  const returned = params.has('redirect_status') || params.has('payment_intent');
  const link = useQuery({ ...shopperQueries.payLink(api, orderNumber, token), enabled: token.length > 0 });
  const payEl = useRef<PaymentElementHandle>(null);
  const busyRef = useRef(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [paid, setPaid] = useState<PayLink | null>(null);

  const settle = async (confirmed: ConfirmResult) => {
    if (!confirmed.ok) {
      setError(confirmed.message);
      return;
    }
    if (confirmed.status === REDIRECTED) return; // on the way to Stripe and back here
    try {
      const after = await api.confirmPayLink(orderNumber, token);
      qc.setQueryData(payLinkKeys.detail(orderNumber, token), after);
      if (after.status === 'paid') setPaid(after);
      else setError(after.failureMessage ?? t('paynow.error'));
    } catch (e) {
      setError(isApiError(e) ? e.message : t('paynow.error'));
    }
  };

  /** Single-flight: the ref is set before any await, so a double click (or a wallet sheet) pays once. */
  const run = async (fn: () => Promise<void>) => {
    if (busyRef.current) return;
    busyRef.current = true;
    setBusy(true);
    setError(null);
    try {
      await fn();
    } finally {
      busyRef.current = false;
      setBusy(false);
    }
  };

  // Back from a redirect-based method: ask orders to read the PaymentIntent and place the order.
  const checkedReturn = useRef(false);
  useEffect(() => {
    if (!returned || checkedReturn.current || link.data?.status !== 'awaiting_payment') return;
    checkedReturn.current = true;
    void run(() => settle({ ok: true }));
  });

  if (!token) return <Refused title={t('paynow.invalidTitle')} body={t('paynow.invalidBody')} />;
  if (link.isPending) return <Skeleton height={360} />;
  if (link.isError) {
    const e = link.error;
    if (isApiError(e) && e.status === 410) return <Refused title={t('paynow.expiredTitle')} body={t('paynow.expiredBody')} />;
    if (isApiError(e) && (e.status === 404 || e.status === 422)) return <Refused title={t('paynow.invalidTitle')} body={t('paynow.invalidBody')} />;
    return <Refused title={t('paynow.error')} body="" />;
  }
  const p = paid ?? link.data;
  if (p.status === 'paid' || !p.clientSecret) {
    return (
      <main className={styles.page} data-testid="paynow-paid">
        <div className={styles.done}>
          <CheckCircle weight="fill" aria-hidden size={40} />
          <h1 className={styles.title}>{t('paynow.paidTitle')}</h1>
          <p className={styles.muted}>{t('paynow.paidBody', { number: p.orderNumber })}</p>
          <Link href="/orders" className={buttonClassName({ variant: 'secondary' })}>
            {t('paynow.viewOrders')}
          </Link>
        </div>
      </main>
    );
  }
  const secret = p.clientSecret;
  const payWithElement = () =>
    run(async () => {
      const el = payEl.current;
      if (!el) return;
      const submitted = await el.submit();
      if (!submitted.ok) return setError(submitted.message);
      await settle(await el.confirm(secret, returnUrl(orderNumber, token)));
    });

  return (
    <main className={styles.page} data-testid="paynow">
      <h1 className={styles.title}>{t('paynow.title')}</h1>
      <p className={styles.muted}>{t('paynow.intro')}</p>
      <section className={styles.card} aria-label={t('paynow.order', { number: p.orderNumber })}>
        <strong>{t('paynow.order', { number: p.orderNumber })}</strong>
        {p.windowName && p.deliversOn && <span className={styles.muted}>{t('paynow.delivers', { window: p.windowName, date: fmt.date(p.deliversOn) })}</span>}
        <ul className={styles.lines}>
          {p.lines.map((l) => (
            <li key={l.title} className={styles.line}>
              <span>
                {l.title} × {l.quantity}
              </span>
              <span>{fmt.money(l.totalMinor)}</span>
            </li>
          ))}
          {p.deliveryFeeMinor > 0 && (
            <li className={styles.line}>
              <span>{t('paynow.deliveryFee')}</span>
              <span>{fmt.money(p.deliveryFeeMinor)}</span>
            </li>
          )}
        </ul>
        <div className={styles.total}>
          <span>{t('paynow.total')}</span>
          <span data-testid="paynow-total">{fmt.money(p.totalMinor)}</span>
        </div>
        {p.expiresAt && <p className={styles.muted}>{t('paynow.expires', { date: fmt.dateTime(p.expiresAt) })}</p>}
      </section>
      {p.failureMessage && !error && (
        <p className={styles.error} role="alert">
          <Warning aria-hidden /> {t('paynow.lastDeclined', { message: p.failureMessage })}
        </p>
      )}
      <section className={styles.card}>
        <QuickPay
          publishableKey={p.publishableKey}
          amountMinor={p.totalMinor}
          currency={p.currency}
          disabled={busy}
          onPay={async () => {
            if (busyRef.current) return null;
            busyRef.current = true;
            setBusy(true);
            setError(null);
            return { clientSecret: secret, returnUrl: returnUrl(orderNumber, token) };
          }}
          onConfirmed={(r) => {
            void settle(r).finally(() => {
              busyRef.current = false;
              setBusy(false);
            });
          }}
        />
        <div className={styles.or}>
          <span />
          {t('paynow.or')}
          <span />
        </div>
        <PaymentElementField ref={payEl} publishableKey={p.publishableKey} amountMinor={p.totalMinor} currency={p.currency} save={false} />
        {error && (
          <p className={styles.error} role="alert" data-testid="paynow-error">
            {error}
          </p>
        )}
        <Button size="lg" block loading={busy} onClick={() => void payWithElement()} data-testid="paynow-pay">
          {busy ? t('paynow.paying') : t('paynow.pay', { amount: fmt.money(p.totalMinor) })}
        </Button>
        <span className={styles.muted}>
          <LockSimple weight="fill" aria-hidden /> {t('paynow.secure')}
        </span>
      </section>
    </main>
  );
}

function Refused({ title, body }: { title: string; body: string }) {
  return (
    <main className={styles.page} data-testid="paynow-refused">
      <h1 className={styles.title}>{title}</h1>
      {body && <p className={styles.muted}>{body}</p>}
    </main>
  );
}
