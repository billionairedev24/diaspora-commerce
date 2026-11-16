'use client';

import { type CartPreorder, type CheckoutSummary, legalHref, type TotalChange } from '@afrotransact/api-client';
import { useFormatters } from '@afrotransact/i18n/react';
import { Button, CheckCircle, Circle, CircleNotch } from '@afrotransact/ui';
import Link from 'next/link';

import memberStyles from '@/components/membership/Membership.module.css';
import { MembershipUpsell } from '@/components/membership/MembershipUpsell';
import { benefitName } from '@/components/membership/memberText';
import { RunNote } from '@/components/preorder/CartPreorder';
import { useT } from '@/i18n/t';

import styles from './Checkout.module.css';
import { CheckoutCrossSell } from './CheckoutCrossSell';
import { TaxProblem } from './TaxProblem';
import { TaxZipEstimate } from './TaxZipEstimate';

const STEPS = ['co.placing.payment', 'co.placing.confirm', 'co.placing.stock', 'co.placing.split'] as const;

type Props = {
  s: CheckoutSummary;
  pickups: number;
  /** Shipping before coupon (matches selected delivery options; may lead the BFF summary while refetching). */
  shippingMinor: number;
  total: number;
  itemCount: number;
  /** Address and payment are both valid: Place your order is on (the other reasons are `blocked` and the notes below it). */
  paymentChosen: boolean;
  /** Plain words for what is missing before the order can be placed (address or payment); null when nothing is. */
  needReason?: string | null;
  /** AfroTransact credit applied to the total and what the card pays then (S320); 0 without credit. */
  creditMinor?: number;
  addressOk: boolean;
  blocked: boolean;
  preLocked: boolean;
  preLockedText?: string | null;
  /** The delivery run the order goes on (S261): its minimum progress and cutoff are shown next to the button. */
  preRun?: CartPreorder | null;
  undeliverable: boolean;
  /** An item can't be sold yet (no definite tax decision): Remove it to place the order. */
  taxBlocked?: boolean;
  /** Plain words for what delivery stops the order (a package to quote, or an unknown fee); null when nothing does. */
  deliveryReason?: string | null;
  /** Plain words for why card payment stops the order (it could not load); null when it does not. */
  paymentReason?: string | null;
  addressList: boolean;
  busy: boolean;
  placing: number;
  error: string | null;
  /** The error is "your cart changed": offer the way back to the cart. */
  errorCartLink?: boolean;
  onPlace: () => void;
  /** The summary is being worked out again (a delivery option, address or coupon changed): the shown total is not final yet. */
  taxPending?: boolean;
  /** Tax-returns could not answer: "We couldn't work out your tax right now. Try again." with Retry. */
  onRetryTax?: () => void;
  retryingTax?: boolean;
  /** The chosen address has no state or ZIP code: the row offers Edit address. */
  onEditAddress?: () => void;
  /** Place order was refused because the total would be higher: the new total, confirmed before anything is charged. */
  totalChange?: TotalChange | null;
  onConfirmTotal?: () => void;
  onDismissTotal?: () => void;
};

/** Sticky checkout rail, Amazon style: total and Place order first, then the items, promo code and the breakdown. */
export function CheckoutOrderPanel(props: Props) {
  const fmt = useFormatters();
  const t = useT();
  const {
    s,
    pickups,
    shippingMinor,
    total,
    itemCount,
    paymentChosen,
    needReason = null,
    creditMinor = 0,
    addressOk,
    blocked,
    preLocked,
    preLockedText,
    preRun,
    undeliverable,
    taxBlocked = false,
    deliveryReason = null,
    paymentReason = null,
    addressList,
    busy,
    placing,
    error,
    errorCartLink,
    onPlace,
    taxPending = false,
    onRetryTax,
    retryingTax = false,
    onEditAddress,
    totalChange = null,
    onConfirmTotal,
    onDismissTotal
  } = props;

  const waivedMinor = s.membershipWaivedMinor ?? 0;
  const netShipping = Math.max(0, shippingMinor - s.shippingDiscountMinor - waivedMinor);
  const estimated = s.taxEstimateStatus === 'ESTIMATED';
  const reason = s.taxUnavailableReason;
  const beforeTax = s.subtotalMinor - s.discountMinor + netShipping;
  const placeDisabled = !paymentChosen || !addressOk || addressList || blocked || taxPending || !!totalChange;

  const placeLabel = busy ? t('co.placing') : preLocked ? t('preorder.locked.button') : t('co.place');
  const notes = (
    <>
      {totalChange && (
        <div className={styles.totalChanged} role="alert" data-testid="checkout-total-changed">
          <strong>{t('co.totalChanged.title')}</strong>
          <p>{t('co.totalChanged.body', { old: fmt.money(totalChange.previousTotalMinor), new: fmt.money(totalChange.totalMinor) })}</p>
          <div className={styles.totalChangedActions}>
            <Button size="sm" onClick={onConfirmTotal} loading={busy}>
              {t('co.totalChanged.confirm', { total: fmt.money(totalChange.totalMinor) })}
            </Button>
            <Button size="sm" variant="secondary" onClick={onDismissTotal} disabled={busy}>
              {t('co.totalChanged.review')}
            </Button>
          </div>
        </div>
      )}
      {error && !totalChange && (
        <p className={styles.error} role="alert" data-testid="checkout-error-note">
          {error}
          {errorCartLink && (
            <>
              {' '}
              <Link href="/cart" data-testid="checkout-review-cart">
                {t('co.cartChanged.link')}
              </Link>
            </>
          )}
        </p>
      )}
      {preRun && <RunNote block={preRun} />}
      {preLocked && preLockedText && (
        <p className={styles.fine} role="note" data-testid="preorder-checkout-locked">
          {preLockedText}
        </p>
      )}
    </>
  );
  const why = (
    <>
      {needReason && (
        <p className={styles.fine} role="status" data-testid="place-reason">
          {needReason}
        </p>
      )}
      {deliveryReason && (
        <p className={styles.fine} role="status" data-testid="delivery-blocked-note">
          {deliveryReason}
        </p>
      )}
      {paymentReason && (
        <p className={styles.fine} role="status" data-testid="payment-blocked-note">
          {paymentReason}
        </p>
      )}
      {taxBlocked && (s.blockedLines ?? []).length > 0 && (
        <p className={styles.fine} data-testid="tax-blocked-note">
          {t('co.blocked.placeHint')}
        </p>
      )}
      {taxBlocked && (s.blockedLines ?? []).length === 0 && !estimated && reason !== 'NEEDS_ZIP' && reason !== 'ADDRESS_INCOMPLETE' && (
        <p className={styles.fine} data-testid="tax-retry-hint">
          {t('co.taxRetry.hint')}
        </p>
      )}
      {undeliverable && !preLocked && (
        <p className={styles.fine} data-testid="local-delivery-blocked">
          {t('ld.blocked')}
        </p>
      )}
    </>
  );

  return (
    <>
      <aside className={styles.rail} aria-label={t('co.orderSummary')}>
        <div className={styles.railBox}>
          <Button size="lg" block onClick={onPlace} loading={busy} disabled={placeDisabled} data-place-order="1">
            {placeLabel}
          </Button>
          <p className={styles.terms} data-testid="checkout-terms">
            {t('co.agreePrefix')} <Link href={legalHref('conditions-of-use')}>{t('co.conditions')}</Link> {t('co.and')} <Link href={legalHref('privacy')}>{t('co.privacy')}</Link>.
          </p>
          {preRun && (
            <p className={styles.fine} data-testid="preorder-terms-note">
              {t('co.preorderPrefix')} <Link href={legalHref('preorder-delivery-terms')}>{t('legal.doc.preorder-delivery-terms')}</Link>.
            </p>
          )}
          {notes}
          {why}
          {busy && (
            <ol className={styles.steps} aria-live="polite">
              {STEPS.map((k, i) => (
                <li key={k}>
                  {i < placing ? <CheckCircle weight="fill" aria-hidden /> : i === placing ? <CircleNotch className={styles.spin} aria-hidden /> : <Circle aria-hidden />} {t(k)}
                </li>
              ))}
            </ol>
          )}

          <h2 className={styles.summaryTitle}>{t('co.orderSummary')}</h2>
          <dl className={styles.totals} data-testid="order-summary">
            <div className={styles.row}>
              <dt>{t('co.itemsTotal', { n: itemCount })}</dt>
              <dd>{fmt.money(s.subtotalMinor)}</dd>
            </div>
            <div className={styles.row}>
              <dt>{t('co.deliveryShort')}</dt>
              <dd>
                {netShipping === 0 ? t('co.free') : fmt.money(netShipping)}
                {s.shippingDiscountMinor > 0 && shippingMinor > netShipping && <span className={styles.muted}> ({t('co.shippingWas', { amount: fmt.money(shippingMinor) })})</span>}
              </dd>
              {pickups > 0 && <dd className={styles.rowNote}>{t('co.pickupNote', { n: pickups })}</dd>}
            </div>
            {s.discountMinor > 0 && (
              <div className={styles.row}>
                <dt>{t('co.discount')}</dt>
                <dd className={styles.discount}>−{fmt.money(s.discountMinor)}</dd>
              </div>
            )}
            {waivedMinor > 0 && (
              <div className={styles.row} data-testid="checkout-membership-waived">
                <dt>{t('co.mem.waivedRow')}</dt>
                <dd className={memberStyles.waived}>−{fmt.money(waivedMinor)}</dd>
                {s.membership?.waivers.map((w) => (
                  <dd key={w.packageId} className={`${styles.rowNote} ${memberStyles.waivedNote}`}>
                    {t('co.mem.waivedNote', { tier: s.membership?.tierName ?? '', benefit: benefitName(t, fmt, w.benefit), amount: fmt.money(w.waivedMinor) })} · {w.seller}
                  </dd>
                ))}
              </div>
            )}
            <div className={styles.row} data-testid="checkout-before-tax">
              <dt>{t('co.totalBeforeTax')}</dt>
              <dd>{fmt.money(beforeTax)}</dd>
            </div>
            <div className={styles.row}>
              <dt>{t('co.tax')}</dt>
              <dd data-testid="checkout-tax-amount">{estimated ? fmt.money(s.taxMinor) : '—'}</dd>
              {!estimated && (
                <dd className={styles.taxState} data-testid="checkout-tax-state" data-reason={reason ?? 'UNAVAILABLE'}>
                  {reason === 'BLOCKED_ITEMS' ? (
                    <TaxProblem problem={{ kind: 'blocked', lines: s.blockedLines ?? [] }} />
                  ) : reason === 'ADDRESS_INCOMPLETE' ? (
                    <TaxProblem problem={{ kind: 'incomplete' }} onEditAddress={onEditAddress} />
                  ) : (s.taxEstimateStatus === 'NEEDS_ADDRESS' || reason === 'NEEDS_ZIP') && !addressOk ? (
                    // No delivery address yet: the one place checkout asks for a ZIP code. With an address chosen the estimate is for it.
                    <TaxZipEstimate useSavedAddress />
                  ) : (
                    <TaxProblem problem={{ kind: 'retry' }} onRetry={onRetryTax} busy={retryingTax} />
                  )}
                </dd>
              )}
            </div>
            {estimated && (
              <div className={`${styles.row} ${styles.rowTotal}`}>
                <dt>{t('co.total')}</dt>
                <dd data-testid="checkout-total">{fmt.money(total)}</dd>
              </div>
            )}
            {creditMinor > 0 && (
              <div className={styles.row} data-testid="checkout-credit-row">
                <dt>{t('co.credit.row')}</dt>
                <dd className={styles.creditRow}>−{fmt.money(creditMinor)}</dd>
              </div>
            )}
          </dl>
          {(s.membership?.cashbackMinor ?? 0) > 0 && (
            <p className={styles.fine} data-testid="checkout-membership-earn">
              {t('co.mem.earn', { amount: fmt.money(s.membership?.cashbackMinor ?? 0) })}
            </p>
          )}
          {s.membership?.upsell && (
            <div className={styles.promoWrap}>
              <MembershipUpsell upsell={s.membership.upsell} />
            </div>
          )}
          {s.crossSell && (
            <div className={styles.promoWrap}>
              <CheckoutCrossSell rail={s.crossSell} />
            </div>
          )}
        </div>
      </aside>

      {/* Phones: the button stays within reach while the sections scroll. */}
      <div className={styles.bar} data-testid="checkout-bar" data-noprint>
        <div className={styles.barTotal}>
          <span className={styles.barLabel}>{estimated ? t('co.totalShort') : t('co.totalBeforeTax')}</span>
          <strong data-testid="checkout-bar-total">{fmt.money(estimated ? total : beforeTax)}</strong>
        </div>
        <Button size="lg" onClick={onPlace} loading={busy} disabled={placeDisabled} className={styles.barButton}>
          {busy ? t('co.placing') : preLocked ? t('preorder.locked.button') : t('co.placeShort')}
        </Button>
      </div>
    </>
  );
}
