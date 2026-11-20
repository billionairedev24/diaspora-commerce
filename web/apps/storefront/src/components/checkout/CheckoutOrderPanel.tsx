'use client';

import { type CartPreorder, type CheckoutSummary, legalHref, type TotalChange } from '@afrotransact/api-client';
import { useFormatters } from '@afrotransact/i18n/react';
import { Button, CaretLeft, CheckCircle, Circle, CircleNotch, InfoTip } from '@afrotransact/ui';
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
  /** Plain words for what is missing before the order can be placed (address or payment); null when nothing is. */
  needReason?: string | null;
  /** AfroTransact credit applied to the total and what the card pays then (S320); 0 without credit. */
  creditMinor?: number;
  addressOk: boolean;
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
  busy: boolean;
  placing: number;
  error: string | null;
  /** The error is "your cart changed": offer the way back to the cart. */
  errorCartLink?: boolean;
  onPlace: () => void;
  /** Place your order is off: address, payment or anything that stops the order is not settled (worked out once, by the page). */
  placeDisabled: boolean;
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

/** The one legal line next to Place your order: placing the order means the privacy notice and the conditions of use. */
function PlaceTerms({ testId, preRun }: { testId?: string; preRun?: CartPreorder | null }) {
  const t = useT();
  return (
    <>
      <p className={styles.terms} data-testid={testId}>
        {t('co.agreePrefix')} <Link href={legalHref('privacy')}>{t('co.privacy')}</Link> {t('co.and')} <Link href={legalHref('conditions-of-use')}>{t('co.conditions')}</Link>.
      </p>
      {preRun && (
        <p className={styles.fine} data-testid={testId ? `${testId}-preorder` : undefined}>
          {t('co.preorderPrefix')} <Link href={legalHref('preorder-delivery-terms')}>{t('legal.doc.preorder-delivery-terms')}</Link>.
        </p>
      )}
    </>
  );
}

/**
 * The end of the page, after the items (Amazon pattern): a second Place your order with the order total beside it and the
 * legal line, then the notes a shopper looks for (tax, help, returns) and the way back to the cart. The total is the one the
 * summary shows: "before tax" until the tax has been calculated, never a guessed figure.
 */
export function CheckoutFinish({
  total,
  beforeTax,
  estimated,
  placeDisabled,
  busy,
  preLocked,
  preRun,
  onPlace
}: {
  total: number;
  beforeTax: number;
  estimated: boolean;
  placeDisabled: boolean;
  busy: boolean;
  preLocked: boolean;
  preRun?: CartPreorder | null;
  onPlace: () => void;
}) {
  const fmt = useFormatters();
  const t = useT();
  const shown = fmt.money(estimated ? total : beforeTax);
  const label = busy ? t('co.placing') : preLocked ? t('preorder.locked.button') : t('co.place');
  return (
    <section className={styles.finish} aria-label={t('co.finish.aria')} data-testid="checkout-finish">
      <div className={styles.finishPlace}>
        <Button size="lg" onClick={onPlace} loading={busy} disabled={placeDisabled} aria-describedby="co-finish-total" className={styles.finishButton} data-place-order="1">
          {label}
        </Button>
        <div className={styles.finishText}>
          <p id="co-finish-total" className={styles.finishTotal} data-testid="checkout-finish-total">
            {estimated ? t('co.total') : t('co.totalBeforeTax')}: <strong>{shown}</strong>
          </p>
          <PlaceTerms testId="checkout-terms-bottom" preRun={preRun} />
        </div>
      </div>
      <div className={styles.notes} data-testid="checkout-notes">
        <p className={styles.noteLine}>
          {t('co.note.tax')}
          <InfoTip label={t('co.note.taxTipLabel')}>{t('co.note.taxTip')}</InfoTip>
        </p>
        <p className={styles.noteLine}>
          {t('co.note.help')} <Link href="/help">{t('footer.helpCenter')}</Link>.
        </p>
        <p className={styles.noteLine}>
          {t('co.returnsPrefix')} <Link href={legalHref('returns')}>{t('legal.doc.returns')}</Link>.
        </p>
        <Link href="/cart" className={styles.backToCart} data-testid="checkout-back-to-cart">
          <CaretLeft aria-hidden /> {t('co.backToCart')}
        </Link>
      </div>
    </section>
  );
}

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
    needReason = null,
    creditMinor = 0,
    addressOk,
    preLocked,
    preLockedText,
    preRun,
    undeliverable,
    taxBlocked = false,
    deliveryReason = null,
    paymentReason = null,
    busy,
    placing,
    error,
    errorCartLink,
    onPlace,
    placeDisabled,
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
  // The tax waits for the address (the step the shopper takes next): say so. A failure or a blocked item is never "later" (Retry / Remove).
  const awaitsAddress = !estimated && (s.taxEstimateStatus === 'NEEDS_ADDRESS' || reason === 'NEEDS_ZIP' || reason === 'ADDRESS_INCOMPLETE');

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
      {/* After the items in reading order; on wide screens it sits under them, left of the sticky summary. */}
      <CheckoutFinish total={total} beforeTax={beforeTax} estimated={estimated} placeDisabled={placeDisabled} busy={busy} preLocked={preLocked} preRun={preRun} onPlace={onPlace} />
      <aside className={styles.rail} aria-label={t('co.orderSummary')}>
        <div className={styles.railBox}>
          <div className={styles.railPlace}>
            <Button size="lg" block onClick={onPlace} loading={busy} disabled={placeDisabled} data-place-order="1">
              {placeLabel}
            </Button>
            <PlaceTerms testId="checkout-terms" preRun={preRun} />
          </div>
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

          <h2 className="at-visually-hidden">{t('co.orderSummary')}</h2>
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
            <div className={styles.row}>
              <dt>{t('co.tax')}</dt>
              {/* Only the computed amount; until then the plain words, never a guess. */}
              <dd data-testid="checkout-tax-amount">{estimated ? fmt.money(s.taxMinor) : awaitsAddress ? t('co.tax.later') : '—'}</dd>
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
            {creditMinor > 0 && (
              <div className={styles.row} data-testid="checkout-credit-row">
                <dt>{t('co.credit.row')}</dt>
                <dd className={styles.creditRow}>−{fmt.money(creditMinor)}</dd>
              </div>
            )}
            <div className={`${styles.row} ${styles.rowTotal}`} data-testid="checkout-before-tax" data-final={estimated ? 'true' : 'false'}>
              <dt>{estimated ? t('co.total') : t('co.totalBeforeTax')}</dt>
              <dd data-testid={estimated ? 'checkout-total' : 'checkout-total-before-tax'}>{fmt.money(estimated ? total : beforeTax)}</dd>
            </div>
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
