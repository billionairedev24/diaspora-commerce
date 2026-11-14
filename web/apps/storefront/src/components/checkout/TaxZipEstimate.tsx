'use client';

import { accountQueries, type CartTaxEstimate, type CheckoutBlockedLine, isApiError, shopperQueries, siteQueries } from '@afrotransact/api-client';
import { useFormatters } from '@afrotransact/i18n/react';
import { Button } from '@afrotransact/ui';
import { useQuery } from '@tanstack/react-query';
import { type FormEvent, useCallback, useEffect, useRef, useState } from 'react';

import { useT } from '@/i18n/t';
import { browserApi } from '@/lib/api/browser';

import styles from './Checkout.module.css';
import { TaxProblem } from './TaxProblem';

const ZIP = /^\d{5}(-\d{4})?$/;

type Props = {
  /** Read the estimate again when this changes (the cart's contents); the shown amount follows the cart. */
  refreshKey?: string | number;
  /**
   * Work the place out without asking. The BFF uses the place the site already has, in priority order: the signed-in
   * shopper's saved address, the delivery place they chose in the header, the place detected from the edge, the market's
   * default. The ZIP code input is then only for a visitor with none of them (rare).
   */
  useSavedAddress?: boolean;
  /** Takes a blocked line out of the cart (the cart page). Without it the blocked items are only named. */
  onRemoveLine?: (line: CheckoutBlockedLine) => void;
};

/**
 * What the tax row shows, from the BFF's own `taxEstimateStatus` / `taxUnavailableReason` and never from a guess:
 *  - `estimate`: the amount with the place it is for (a saved address, the chosen or detected delivery place, or a typed ZIP
 *    code) and, for a place we worked out ourselves, where it came from;
 *  - `blocked`: items we can't sell yet are named, with Remove (the cart page);
 *  - `incomplete`: the saved address lacks a state or ZIP code, with Edit address;
 *  - `retry`: the estimate could not be worked out right now, with Retry;
 *  - `zip`: nothing to work from (a guest, a new shopper): the one place the ZIP input is shown.
 */
type View =
  | { kind: 'loading' }
  | { kind: 'estimate'; est: CartTaxEstimate; auto: boolean }
  | { kind: 'blocked'; lines: CheckoutBlockedLine[] }
  | { kind: 'incomplete' }
  | { kind: 'retry' }
  | { kind: 'zip' };

/**
 * The cart page's estimated tax (and the checkout's input while no address is chosen). It asks the BFF for the same
 * tax-returns quote the checkout uses, for the selected cart items. Never an amount without a place, never $0.00 for
 * "don't know yet", never "calculated at checkout". The checkout shows the locked estimate once an address is chosen.
 */
export function TaxZipEstimate({ refreshKey, useSavedAddress = false, onRemoveLine }: Props) {
  const t = useT();
  const fmt = useFormatters();
  const api = browserApi();
  const [zip, setZip] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [view, setView] = useState<View>(useSavedAddress ? { kind: 'loading' } : { kind: 'zip' });
  /** A ZIP code the shopper typed (an estimate for another place); null = work the place out automatically. */
  const typed = useRef<string | null>(null);
  const latest = useRef(0);

  // The place changes when the shopper signs in or edits their address book somewhere else: read again then.
  const me = useQuery({ ...accountQueries.me(api), enabled: useSavedAddress });
  const signedIn = me.data?.authenticated === true;
  const book = useQuery({ ...shopperQueries.addresses(api), enabled: useSavedAddress && signedIn });
  // The header's "Deliver to" place is part of the place too: picking another one reads the estimate again.
  const where = useQuery({ ...siteQueries.deliveryLocation(api), enabled: useSavedAddress });
  const placeKey = `${signedIn}:${where.data?.zip ?? ''}/${where.data?.source ?? ''}:${(book.data ?? []).map((a) => `${a.id}/${a.isDefault}/${a.state ?? ''}/${a.zip}`).join(',')}`;

  const show = useCallback(
    (est: CartTaxEstimate, auto: boolean): View => {
      if (est.taxEstimateStatus === 'ESTIMATED') {
        // Never an amount without a place.
        return est.taxJurisdictionLabel || est.taxZip ? { kind: 'estimate', est, auto } : { kind: 'retry' };
      }
      switch (est.taxUnavailableReason) {
        case 'BLOCKED_ITEMS':
          return { kind: 'blocked', lines: est.blockedLines };
        case 'ADDRESS_INCOMPLETE':
          return { kind: 'incomplete' };
        case 'NEEDS_ZIP':
        case 'NO_ITEMS':
          return { kind: 'zip' };
        default:
          return est.taxEstimateStatus === 'NEEDS_ADDRESS' ? { kind: 'zip' } : { kind: 'retry' };
      }
    },
    []
  );

  const run = useCallback(async () => {
    const ticket = ++latest.current;
    const current = () => ticket === latest.current;
    setBusy(true);
    setError(null);
    try {
      if (typed.current) {
        const est = await api.getTaxEstimate(typed.current);
        if (current()) setView(show(est, false));
        return;
      }
      // The BFF works the place out (saved address, chosen place, detected place); nothing to resolve here.
      const est = await api.getTaxEstimate('');
      const auto = true;
      if (current()) setView(show(est, auto));
    } catch (err) {
      if (!current()) return;
      if (typed.current && isApiError(err) && err.status === 422) {
        typed.current = null;
        setView({ kind: 'zip' });
        setError(t('co.taxZip.invalid'));
      } else setView({ kind: 'retry' });
    } finally {
      if (current()) setBusy(false);
    }
  }, [api, show, t]);

  useEffect(() => {
    if (useSavedAddress || typed.current) void run();
    // eslint-disable-next-line react-hooks/exhaustive-deps -- read again only when the cart or the place changes
  }, [refreshKey, placeKey]);

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    const value = zip.trim();
    if (!ZIP.test(value)) return setError(t('co.taxZip.invalid'));
    typed.current = value;
    await run();
  };

  if (view.kind === 'loading') {
    return (
      <div className={styles.taxState} aria-busy="true" data-testid="tax-zip-loading">
        <span className={styles.muted}>…</span>
      </div>
    );
  }

  if (view.kind === 'estimate') {
    const { est } = view;
    // A place we worked out ourselves says where it came from; a saved address or a ZIP code the shopper typed needs no note.
    const basis = est.taxPlaceSource === 'DETECTED_PLACE' ? t('co.taxBasedOnLocation') : est.taxPlaceSource === 'DEFAULT_PLACE' ? t('co.taxBasedOnArea') : '';
    return (
      <div className={styles.taxState} data-testid="tax-zip-result">
        <span>
          <strong data-testid="tax-zip-amount">{fmt.money(est.taxMinor)}</strong> · {t('co.taxFor', { place: est.taxJurisdictionLabel || est.taxZip || '' })}
          {basis && <span className={styles.muted} data-testid="tax-zip-basis">{` · ${basis}`}</span>}
        </span>
        <button
          type="button"
          className="link-button"
          style={{ alignSelf: 'flex-start', fontSize: 'var(--at-text-xs)' }}
          onClick={() => {
            typed.current = null;
            setZip('');
            setView({ kind: 'zip' });
          }}
        >
          {view.auto ? t('co.taxZip.changePlace') : t('co.taxZip.change')}
        </button>
      </div>
    );
  }

  if (view.kind === 'blocked' || view.kind === 'incomplete' || view.kind === 'retry') {
    return (
      <TaxProblem
        problem={view.kind === 'blocked' ? { kind: 'blocked', lines: view.lines } : { kind: view.kind }}
        onRemoveLine={onRemoveLine}
        onRetry={() => void run()}
        busy={busy}
      />
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
