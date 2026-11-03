'use client';

import { type CartPreorder, type CheckoutPackage, type CheckoutSummary, legalHref } from '@afrotransact/api-client';
import { useFormatters } from '@afrotransact/i18n/react';
import { Button, CheckCircle, Circle, CircleNotch, ImagePlaceholder, LockSimple, PoweredByStripe, Tag } from '@afrotransact/ui';
import Link from 'next/link';
import { type FormEvent } from 'react';

import memberStyles from '@/components/membership/Membership.module.css';
import { MembershipUpsell } from '@/components/membership/MembershipUpsell';
import { benefitName } from '@/components/membership/memberText';
import { RunNote } from '@/components/preorder/CartPreorder';
import { useT } from '@/i18n/t';

import styles from './Checkout.module.css';
import { CheckoutCrossSell } from './CheckoutCrossSell';

const STEPS = ['co.placing.payment', 'co.placing.confirm', 'co.placing.stock', 'co.placing.split'] as const;

type Props = {
  s: CheckoutSummary;
  packages: CheckoutPackage[];
  pickups: number;
  /** Shipping before coupon (matches selected delivery options; may lead the BFF summary while refetching). */
  shippingMinor: number;
  total: number;
  itemCount: number;
  taxNote: string | null;
  paymentChosen: boolean;
  /** AfroTransact credit applied to the total and what the card pays then (S320); 0 without credit. */
  creditMinor?: number;
  /** The chosen method ("Visa ending in 4242"), shown under Place order. */
  pmLabel?: string | null;
  addressOk: boolean;
  blocked: boolean;
  preLocked: boolean;
  preLockedText?: string | null;
  /** The delivery run the order goes on (S261): its minimum progress and cutoff are shown next to the button. */
  preRun?: CartPreorder | null;
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

/** Sticky checkout rail, Amazon style: total and Place order first, then the items, promo code and the breakdown. */
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
    paymentChosen,
    creditMinor = 0,
    pmLabel,
    addressOk,
    blocked,
    preLocked,
    preLockedText,
    preRun,
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
  const waivedMinor = s.membershipWaivedMinor ?? 0;
  const netShipping = Math.max(0, shippingMinor - s.shippingDiscountMinor - waivedMinor);

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

        <div className={styles.placeBlock}>
          <Button size="lg" block onClick={onPlace} loading={busy} disabled={!paymentChosen || !addressOk || addressList || blocked} data-place-order="1">
            {busy ? t('co.placing') : preLocked ? t('preorder.locked.button') : t('co.place')}
          </Button>
          {error && (
            <p className={styles.error} role="alert">
              {error}
            </p>
          )}
          {preRun && <RunNote block={preRun} />}
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
          {creditMinor > 0 && total - creditMinor === 0 ? <p className={styles.payingWith}>{t('co.credit.payingAll')}</p> : null}
          {pmLabel && !(creditMinor > 0 && total - creditMinor === 0) ? <p className={styles.payingWith}>{t('co.payingWith', { method: pmLabel })}</p> : null}
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
            <dt>{t('co.taxShort')}</dt>
            <dd>{s.taxMinor ? fmt.money(s.taxMinor) : '—'}</dd>
            {taxNote && <dd className={styles.rowNote}>{taxNote}</dd>}
          </div>
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

        <div className={styles.legalBlock}>
          <p className={styles.fine}>
            {t('co.agreePrefix')} <Link href={legalHref('conditions-of-use')}>{t('co.conditions')}</Link> {t('co.and')} <Link href={legalHref('privacy')}>{t('co.privacy')}</Link>.{' '}
            {t('co.returnsPrefix')} <Link href={legalHref('returns')}>{t('legal.doc.returns')}</Link>.
          </p>
          {preRun && (
            <p className={styles.fine} data-testid="preorder-terms-note">
              {t('co.preorderPrefix')} <Link href={legalHref('preorder-delivery-terms')}>{t('legal.doc.preorder-delivery-terms')}</Link>.
            </p>
          )}
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
