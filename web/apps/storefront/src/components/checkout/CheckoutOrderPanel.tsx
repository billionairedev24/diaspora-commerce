'use client';

import { type CheckoutPackage, type CheckoutSummary, type PaymentMethod } from '@afrotransact/api-client';
import { useFormatters } from '@afrotransact/i18n/react';
import { Button, CheckCircle, Circle, CircleNotch, ImagePlaceholder, LockSimple, PoweredByStripe, ShieldCheck, Tag } from '@afrotransact/ui';
import Link from 'next/link';
import { type FormEvent, type RefObject } from 'react';

import { AcceptedPaymentsHint } from '@/components/payments/AcceptedPaymentsHint';
import { PaymentChip } from '@/components/payments/PaymentChip';
import { type PaymentElementChange, PaymentElementField, type PaymentElementHandle } from '@/components/payments/PaymentElementField';
import { type MessageKey, type Translator, useT } from '@/i18n/t';

import styles from './Checkout.module.css';
import { CheckoutCrossSell } from './CheckoutCrossSell';

const STEPS = ['co.placing.payment', 'co.placing.confirm', 'co.placing.stock', 'co.placing.split'] as const;

const pmSub = (t: Translator<MessageKey>, pm: PaymentMethod) => (pm.expires ? t('pay.expires', { date: pm.expires }) : pm.type === 'us_bank_account' ? t('pay.bankAccount') : t('pay.wallet'));

type Props = {
  s: CheckoutSummary;
  packages: CheckoutPackage[];
  pickups: number;
  /** Shipping before coupon (matches selected delivery options; may lead the BFF summary while refetching). */
  shippingMinor: number;
  total: number;
  itemCount: number;
  taxNote: string | null;
  payWith: 'saved' | 'new' | null;
  pmId: string | null;
  pm: PaymentMethod | null;
  save: boolean;
  setSave: (v: boolean) => void;
  setPayWith: (v: 'saved' | 'new') => void;
  setPmId: (id: string) => void;
  onNewMethod: (c: PaymentElementChange) => void;
  payEl: RefObject<PaymentElementHandle | null>;
  methodTypes: string[] | undefined;
  paymentChosen: boolean;
  addressOk: boolean;
  blocked: boolean;
  preLocked: boolean;
  preLockedText?: string | null;
  undeliverable: boolean;
  addressList: boolean;
  busy: boolean;
  placing: number;
  error: string | null;
  onPlace: () => void;
  couponDraft: string;
  setCouponDraft: (v: string) => void;
  onApplyCoupon: () => void;
  onRemoveCoupon: () => void;
};

/** Sticky checkout rail: total, items, payment and place order — payment is never buried in a scroll trap. */
export function CheckoutOrderPanel(props: Props) {
  const fmt = useFormatters();
  const t = useT();
  const {
    s,
    packages,
    pickups,
    shippingMinor,
    total,
    itemCount,
    taxNote,
    payWith,
    pmId,
    pm: _pm,
    save,
    setSave,
    setPayWith,
    setPmId,
    onNewMethod,
    payEl,
    methodTypes,
    paymentChosen,
    addressOk,
    blocked,
    preLocked,
    preLockedText,
    undeliverable,
    addressList,
    busy,
    placing,
    error,
    onPlace,
    couponDraft,
    setCouponDraft,
    onApplyCoupon,
    onRemoveCoupon
  } = props;

  const coupon = s.coupon;
  const couponOk = coupon?.applicable;
  const netShipping = Math.max(0, shippingMinor - s.shippingDiscountMinor);

  const applyCoupon = (e: FormEvent) => {
    e.preventDefault();
    onApplyCoupon();
  };

  return (
    <aside className={styles.rail} aria-label={t('co.orderSummary')}>
      <div className={styles.panel}>
        <div className={styles.panelHero}>
          <p className={styles.panelKicker}>
            <LockSimple weight="fill" aria-hidden /> {t('co.secureCheckout')}
          </p>
          <div className={styles.panelTotalRow}>
            <span className={styles.panelTotalLabel}>{t('co.total')}</span>
            <span className={styles.panelTotalAmount} data-testid="checkout-total-hero">
              {fmt.money(total)}
            </span>
          </div>
          <p className={styles.panelMeta}>{t('co.items', { n: itemCount })} · {t('co.sellers', { n: s.sellerCount })}</p>
        </div>

        <details className={styles.itemsDetails}>
          <summary className={styles.itemsSummary}>{t('co.reviewItems', { n: itemCount })}</summary>
          <ul className={styles.compactItems} aria-label={t('co.items', { n: itemCount })}>
            {packages.flatMap((p) =>
              p.lines.map((l) => (
                <li key={l.lineId} className={styles.compactItem} data-testid="checkout-item-compact">
                  <ImagePlaceholder src={l.imageUrl} alt="" className={styles.compactThumb} />
                  <span className={styles.compactText}>
                    <span className={styles.compactTitle}>{l.title}</span>
                    <span className={styles.muted}>
                      {p.seller.name} · {t('co.qty', { qty: l.quantity })}
                    </span>
                  </span>
                  <span className={styles.compactPrice}>{fmt.money(l.lineTotalMinor)}</span>
                </li>
              ))
            )}
          </ul>
        </details>

        <form className={styles.couponBlock} onSubmit={applyCoupon} data-testid="checkout-coupon">
          <label className={styles.couponLabel} htmlFor="co-coupon">
            <Tag aria-hidden /> {t('co.coupon')}
          </label>
          <div className={styles.couponRow}>
            <input
              id="co-coupon"
              className={styles.couponInput}
              value={couponDraft}
              maxLength={20}
              autoComplete="off"
              spellCheck={false}
              placeholder={t('co.couponPlaceholder')}
              onChange={(e) => setCouponDraft(e.target.value.replace(/\s/g, '').toUpperCase())}
              disabled={busy}
            />
            <Button type="submit" size="sm" variant="secondary" disabled={busy || !couponDraft.trim()}>
              {t('co.couponApply')}
            </Button>
          </div>
          {coupon && !couponOk && coupon.message && (
            <p className={styles.couponError} role="alert">
              {coupon.message}
            </p>
          )}
          {couponOk && coupon && (
            <div className={styles.couponApplied}>
              <span>
                {t('co.couponApplied', { code: coupon.code })}
                <button type="button" className={styles.couponRemove} onClick={onRemoveCoupon} disabled={busy}>
                  {t('co.couponRemove')}
                </button>
              </span>
              <p className={styles.rowNote}>
                {coupon.funding === 'seller'
                  ? t('co.couponFundedSeller', { seller: coupon.sellerShares?.[0]?.sellerName ?? coupon.fundedByOrg ?? '' })
                  : t('co.couponFundedPlatform')}
              </p>
              {(coupon.sellerShares?.length ?? 0) > 1 && (
                <ul className={styles.couponShares}>
                  {coupon.sellerShares?.map((sh) => (
                    <li key={sh.sellerOrgId}>
                      {sh.sellerName ?? sh.sellerOrgId}: −{fmt.money(sh.discountMinor)} ({sh.funding === 'platform' ? t('co.couponFundedPlatformShort') : t('co.couponFundedSellerShort')})
                    </li>
                  ))}
                </ul>
              )}
            </div>
          )}
        </form>

        <dl className={styles.totals} data-testid="order-summary">
          <div className={styles.row}>
            <dt>{t('co.itemsTotal', { n: itemCount })}</dt>
            <dd>{fmt.money(s.subtotalMinor)}</dd>
          </div>
          {s.discountMinor > 0 && (
            <div className={styles.row}>
              <dt>{t('co.discount')}</dt>
              <dd className={styles.discount}>−{fmt.money(s.discountMinor)}</dd>
            </div>
          )}
          <div className={styles.row}>
            <dt>{t('co.shippingShort')}</dt>
            <dd>
              {netShipping === 0 ? t('co.free') : fmt.money(netShipping)}
              {s.shippingDiscountMinor > 0 && shippingMinor > netShipping && (
                <span className={styles.muted}> ({t('co.shippingWas', { amount: fmt.money(shippingMinor) })})</span>
              )}
            </dd>
            {pickups > 0 && <dd className={styles.rowNote}>{t('co.pickupNote', { n: pickups })}</dd>}
          </div>
          <div className={styles.row}>
            <dt>{t('co.taxShort')}</dt>
            <dd>{s.taxMinor ? fmt.money(s.taxMinor) : '—'}</dd>
            {taxNote && <dd className={styles.rowNote}>{taxNote}</dd>}
          </div>
        </dl>

        <div className={styles.panelDivider} role="presentation" />

        <section className={styles.paymentBlock} id="co-payment" aria-labelledby="co-payment-title">
          <div className={styles.paymentHead}>
            <h2 id="co-payment-title" className={styles.paymentTitle}>
              {t('co.step.payment')}
            </h2>
            <AcceptedPaymentsHint />
          </div>
          <div className={styles.payment}>
            {s.paymentMethods.length > 0 && (
              <fieldset className={styles.saved}>
                <legend className={styles.legend}>{t('co.savedMethods')}</legend>
                {s.paymentMethods.map((p) => (
                  <label key={p.id} className={styles.choiceCompact} data-testid="checkout-payment">
                    <input
                      type="radio"
                      name="paym"
                      checked={payWith === 'saved' && p.id === pmId}
                      onChange={() => {
                        setPayWith('saved');
                        setPmId(p.id);
                      }}
                    />
                    <PaymentChip method={p} />
                    <span className={styles.choiceText}>
                      <strong>{p.label}</strong> <span className={styles.muted}>{pmSub(t, p)}</span>
                    </span>
                    {p.isDefault && <span className={styles.tag}>{t('pay.default')}</span>}
                  </label>
                ))}
              </fieldset>
            )}
            <div className={payWith === 'new' ? styles.newMethodOn : styles.newMethod} data-testid="checkout-new-method">
              <span className={styles.legend}>{s.paymentMethods.length > 0 ? t('co.orNew') : t('co.payWith')}</span>
              <PaymentElementField
                ref={payEl}
                publishableKey={s.publishableKey ?? ''}
                amountMinor={total}
                currency={s.currency}
                save={save}
                stripeCustomerId={s.stripeCustomerId}
                paymentMethodTypes={methodTypes}
                onChange={onNewMethod}
              />
              <label className={styles.save}>
                <input type="checkbox" checked={save} onChange={(e) => setSave(e.target.checked)} />
                <span>
                  {t('co.saveForLater')}
                  <small>{t('co.saveForLaterHint')}</small>
                </span>
              </label>
            </div>
            <p className={styles.secureText}>
              <ShieldCheck aria-hidden /> {t('pay.storedByStripe')}
            </p>
          </div>
        </section>

        <div className={styles.placeBlock}>
          <Button size="lg" block onClick={onPlace} loading={busy} disabled={!paymentChosen || !addressOk || addressList || blocked} data-place-order="1">
            {busy ? t('co.placing') : preLocked ? t('preorder.locked.button') : t('co.place')}
          </Button>
          {error && (
            <p className={styles.error} role="alert">
              {error}
            </p>
          )}
          {preLocked && preLockedText && (
            <p className={styles.fine} role="note" data-testid="preorder-checkout-locked">
              {preLockedText}
            </p>
          )}
          {undeliverable && !preLocked && (
            <p className={styles.fine} data-testid="local-delivery-blocked">
              {t('ld.blocked')}
            </p>
          )}
          {addressList && addressOk && <p className={styles.fine}>{t('co.confirmAddress')}</p>}
          {busy && (
            <ol className={styles.steps} aria-live="polite">
              {STEPS.map((k, i) => (
                <li key={k}>
                  {i < placing ? <CheckCircle weight="fill" aria-hidden /> : i === placing ? <CircleNotch className={styles.spin} aria-hidden /> : <Circle aria-hidden />} {t(k)}
                </li>
              ))}
            </ol>
          )}
          <p className={styles.fine}>
            {t('co.agreePrefix')} <Link href="/legal/conditions-of-use">{t('co.conditions')}</Link> {t('co.and')} <Link href="/legal/privacy">{t('co.privacy')}</Link>.
          </p>
          <div className={styles.stripeLine}>
            <PoweredByStripe height={22} />
            <span>{t('co.chargeNote')}</span>
          </div>
        </div>

        {s.crossSell && (
          <div className={styles.promoWrap}>
            <CheckoutCrossSell rail={s.crossSell} />
          </div>
        )}
      </div>
    </aside>
  );
}
