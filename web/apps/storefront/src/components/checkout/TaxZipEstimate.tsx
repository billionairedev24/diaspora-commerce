'use client';

import { type CartTaxEstimate, isApiError } from '@afrotransact/api-client';
import { useFormatters } from '@afrotransact/i18n/react';
import { Button } from '@afrotransact/ui';
import { type FormEvent, useCallback, useEffect, useState } from 'react';

import { useT } from '@/i18n/t';
import { browserApi } from '@/lib/api/browser';

import styles from './Checkout.module.css';

const ZIP = /^\d{5}(-\d{4})?$/;

type Props = {
  /** Read the estimate again when this changes (the cart's contents); the shown amount follows the cart. */
  refreshKey?: string | number;
  /** On mount, ask for the estimate with no ZIP code: a signed-in shopper's saved default address is used when it has one. */
  useSavedAddress?: boolean;
};

/**
 * "Enter your ZIP code to see tax": the estimate before an address exists (cart page, a new shopper). It asks the BFF for the
 * same tax-returns quote the checkout uses, for the selected cart items in the place the ZIP code is in (or, signed in, in the
 * saved default address). Never an amount without a place, never $0.00 for "don't know yet". The checkout shows the locked
 * estimate once an address is chosen.
 */
export function TaxZipEstimate({ refreshKey, useSavedAddress = false }: Props) {
  const t = useT();
  const fmt = useFormatters();
  const api = browserApi();
  const [zip, setZip] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [result, setResult] = useState<CartTaxEstimate | null>(null);
  /** The ZIP code the shown estimate is for ('' = the saved address): read again when the cart changes. */
  const [asked, setAsked] = useState<string | null>(useSavedAddress ? '' : null);

  const run = useCallback(
    async (value: string) => {
      setBusy(true);
      setError(null);
      try {
        const est = await api.getTaxEstimate(value);
        if (est.taxEstimateStatus === 'ESTIMATED') {
          setResult(est);
          setAsked(value);
        } else if (value) {
          setError(est.taxUnavailableReason === 'BLOCKED_ITEMS' ? t('co.taxBlocked') : t('co.taxRetry.message'));
        } else {
          setResult(null); // no saved address to use: the input stays
        }
      } catch (err) {
        if (value) setError(isApiError(err) && err.status === 422 ? t('co.taxZip.invalid') : t('co.taxRetry.message'));
      } finally {
        setBusy(false);
      }
    },
    [api, t]
  );

  useEffect(() => {
    if (asked !== null) void run(asked);
    // eslint-disable-next-line react-hooks/exhaustive-deps -- read again only when the cart changes
  }, [refreshKey]);

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    const value = zip.trim();
    if (!ZIP.test(value)) return setError(t('co.taxZip.invalid'));
    await run(value);
  };

  if (result) {
    return (
      <div className={styles.taxState} data-testid="tax-zip-result">
        <span>
          <strong data-testid="tax-zip-amount">{fmt.money(result.taxMinor)}</strong> · {t('co.taxFor', { place: result.taxJurisdictionLabel || result.taxZip || '' })}
        </span>
        <button
          type="button"
          className="link-button"
          onClick={() => {
            setResult(null);
            setAsked(null);
          }}
        >
          {t('co.taxZip.change')}
        </button>
      </div>
    );
  }
  return (
    <form className={styles.taxState} onSubmit={(e) => void submit(e)} data-testid="tax-zip-form">
      <label className={styles.couponLabel} htmlFor="tax-zip">
        {t('co.taxZip.prompt')}
      </label>
      <div className={styles.couponRow}>
        <input
          id="tax-zip"
          className={styles.couponInput}
          value={zip}
          inputMode="numeric"
          autoComplete="postal-code"
          maxLength={10}
          placeholder={t('co.taxZip.placeholder')}
          aria-invalid={error ? true : undefined}
          onChange={(e) => setZip(e.target.value.replace(/[^\d-]/g, ''))}
        />
        <Button type="submit" size="sm" variant="secondary" loading={busy} disabled={!zip.trim()}>
          {t('co.taxZip.apply')}
        </Button>
      </div>
      {error && (
        <p className={styles.couponError} role="alert">
          {error}
        </p>
      )}
    </form>
  );
}
