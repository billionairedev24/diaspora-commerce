'use client';

import { type CheckoutSummary } from '@afrotransact/api-client';
import { useFormatters } from '@afrotransact/i18n/react';
import { Button } from '@afrotransact/ui';
import { type FormEvent, useState } from 'react';

import { useT } from '@/i18n/t';

import styles from './Checkout.module.css';

/**
 * Promo code, Amazon style: a link that opens the field, so it never competes with Place your order. An applied code stays
 * visible with Remove; a code that does not apply says why right under the field.
 */
export function PromoCode({
  coupon,
  draft,
  setDraft,
  busy,
  onApply,
  onRemove
}: {
  coupon: CheckoutSummary['coupon'];
  draft: string;
  setDraft: (v: string) => void;
  busy: boolean;
  onApply: () => void;
  onRemove: () => void;
}) {
  const t = useT();
  const fmt = useFormatters();
  const [open, setOpen] = useState(false);
  const applied = coupon?.applicable ? coupon : null;
  const rejected = coupon && !coupon.applicable && coupon.message ? coupon.message : null;
  const submit = (e: FormEvent) => {
    e.preventDefault();
    onApply();
  };
  return (
    <div className={styles.promoCode} data-testid="checkout-coupon">
      {applied ? (
        <div className={styles.couponApplied}>
          <span>
            {t('co.couponApplied', { code: applied.code })}
            <button type="button" className={styles.couponRemove} onClick={onRemove} disabled={busy}>
              {t('co.couponRemove')}
            </button>
          </span>
          <p className={styles.rowNote}>
            {applied.funding === 'seller' ? t('co.couponFundedSeller', { seller: applied.sellerShares?.[0]?.sellerName ?? applied.fundedByOrg ?? '' }) : t('co.couponFundedPlatform')}
          </p>
          {(applied.sellerShares?.length ?? 0) > 1 && (
            <ul className={styles.couponShares}>
              {applied.sellerShares?.map((sh) => (
                <li key={sh.sellerOrgId}>
                  {sh.sellerName ?? sh.sellerOrgId}: −{fmt.money(sh.discountMinor)} ({sh.funding === 'platform' ? t('co.couponFundedPlatformShort') : t('co.couponFundedSellerShort')})
                </li>
              ))}
            </ul>
          )}
        </div>
      ) : !open && !rejected ? (
        <button type="button" className={`link-button ${styles.promoLink}`} aria-expanded={false} aria-controls="co-promo-form" onClick={() => setOpen(true)} data-testid="checkout-coupon-open">
          {t('co.promoLink')}
        </button>
      ) : (
        <form id="co-promo-form" className={styles.promoForm} onSubmit={submit}>
          <label className={styles.promoLabel} htmlFor="co-coupon">
            {t('co.coupon')}
          </label>
          <div className={styles.promoRow}>
            <input
              id="co-coupon"
              className={styles.promoInput}
              value={draft}
              maxLength={20}
              autoComplete="off"
              spellCheck={false}
              placeholder={t('co.couponPlaceholder')}
              onChange={(e) => setDraft(e.target.value.replace(/\s/g, '').toUpperCase())}
              disabled={busy}
            />
            <Button type="submit" variant="secondary" disabled={busy || !draft.trim()} className={styles.promoApply}>
              {t('co.couponApply')}
            </Button>
          </div>
          {rejected && (
            <p className={styles.couponError} role="alert">
              {rejected}
            </p>
          )}
        </form>
      )}
    </div>
  );
}
