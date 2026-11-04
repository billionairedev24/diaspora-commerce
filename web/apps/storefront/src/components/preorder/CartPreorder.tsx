'use client';

import { type Cart, type CartPreorder, cartSplit, type PreorderFulfilment, preorderKeys, preorderOptions } from '@afrotransact/api-client';
import { useFormatters } from '@afrotransact/i18n/react';
import { ArrowsSplit, ArrowUUpLeft, CalendarCheck, CreditCard, cx, HouseLine, type Icon, Package, ShieldCheck, Storefront } from '@afrotransact/ui';
import { useQueryClient } from '@tanstack/react-query';
import { type ReactNode } from 'react';

import { useT } from '@/i18n/t';
import { feeRuleNote } from '@/lib/fee-rule';
import { payNotes, preorderDates, stageCopy } from '@/lib/preorder/copy';
import { usePreorderResolution } from '@/lib/preorder/usePreorder';

import { Countdown } from './Countdown';
import styles from './Preorder.module.css';

const countdownTarget = (b: CartPreorder) => (b.stage === 'OPEN' ? b.closesAt : b.stage === 'UPCOMING' ? b.opensAt : `${b.deliversOn}T14:00:00Z`);

/** Dates and stage copy of the cart's pre-order block (locked text, countdown label). */
export function useCartPreorderCopy(block: CartPreorder | null | undefined) {
  const t = useT();
  const fmt = useFormatters();
  const { data: r } = usePreorderResolution();
  if (!block) return null;
  const dates = preorderDates({ ...block, opensAt: block.opensAt || block.closesAt }, fmt.tag);
  const city = r?.place.city ?? '';
  const copy = stageCopy(t, { stage: block.stage, city, ribbon: '', headline: '', phone: r?.supportPhone ?? '', dates, doorFee: fmt.money(block.doorFeeMinor, block.currency) });
  return { dates, copy, phone: r?.supportPhone ?? '', metro: r?.metro ?? false };
}

/** "Pre-order · delivered Sun, Oct 25" group header with the live countdown (placement `cart`). */
export function CartPreorderHeader({ block, id }: { block: CartPreorder; id: string }) {
  const t = useT();
  const qc = useQueryClient();
  const c = useCartPreorderCopy(block);
  if (!c) return null;
  return (
    <div className={styles.cartHead} data-testid="cart-preorder-head">
      <CalendarCheck weight="fill" className={styles.cartHeadIcon} aria-hidden />
      <h2 id={id} className={styles.cartHeadTitle}>
        {t(block.kind === 'RUN' ? 'preorder.run.head' : 'preorder.cart.head', { date: c.dates.deliversT })}
      </h2>
      <Countdown variant="inline" className={styles.cartHeadCd} to={countdownTarget(block)} label={c.copy.cdLabel} onElapsed={() => void qc.invalidateQueries({ queryKey: preorderKeys.all })} />
    </div>
  );
}

/**
 * The honest state of a delivery run (S261): the orders so far against the minimum, the cutoff and what happens after
 * it. Renders nothing for a window.
 */
export function RunNote({ block, className }: { block: CartPreorder; className?: string }) {
  const t = useT();
  const c = useCartPreorderCopy(block);
  if (block.kind !== 'RUN' || !c) return null;
  const progress =
    block.minOrders <= 0
      ? t('preorder.run.progressNoMin', { so: block.ordersSoFar })
      : t(block.minimumMet ? 'preorder.run.progressMet' : 'preorder.run.progressNeeds', { so: block.ordersSoFar, min: block.minOrders });
  return (
    <div className={cx(styles.runNote, className)} data-testid="run-note">
      <p data-testid="run-progress">{progress}</p>
      <p data-testid="run-cutoff">{t('preorder.run.cutoff', { cutoff: c.dates.closesT })}</p>
      <p>{t('preorder.run.after')}</p>
    </div>
  );
}

/** "Ships now · UPS Ground, 3–5 days" header above the packages that are not part of the pre-order. */
export function CartShipsNowHeader({ waitlist, metro }: { waitlist?: boolean; metro?: boolean }) {
  const t = useT();
  return (
    <h2 className={styles.nowHead} data-testid="cart-ships-now-head">
      <Package size={18} aria-hidden />
      {metro ? t('preorder.cart.nowHeadMetro') : waitlist ? t('preorder.cart.nowHeadWaitlist') : t('preorder.cart.nowHead')}
    </h2>
  );
}

/** Door delivery / pickup point cards (fees from the window's payment setup). */
export function FulfilmentChoice({ block, busy, onChange }: { block: CartPreorder; busy: boolean; onChange: (f: PreorderFulfilment) => void }) {
  const t = useT();
  const fmt = useFormatters();
  const dates = preorderDates(block, fmt.tag);
  const options = preorderOptions(block);
  if (!options.length) return null;
  const price = (fee: number) => (fee === 0 ? t('preorder.cart.free') : fmt.money(fee, block.currency));
  return (
    <fieldset className={styles.fulfilGroup}>
      <legend className={styles.fulfilLegend}>{t('preorder.cart.fulfilment')}</legend>
      <div className={styles.fulfilGrid}>
        {options.map((o) => {
          const door = o.fulfilment === 'DOOR';
          const FIcon = door ? HouseLine : Storefront;
          const title = door ? t('preorder.cart.door') : t('preorder.cart.pickup');
          const sub = door ? dates.deliversT : t('preorder.cart.pickupSub', { date: dates.deliversT, window: block.pickupWindow });
          const checked = block.fulfilment === o.fulfilment;
          const rule = block.options.find((x) => x.fulfilment === o.fulfilment)?.rule;
          return (
            <button
              key={o.fulfilment}
              type="button"
              aria-pressed={checked}
              className={styles.fulfil}
              disabled={busy}
              onClick={() => !checked && onChange(o.fulfilment)}
              data-testid={`fulfil-${o.fulfilment.toLowerCase()}`}
            >
              <FIcon className={styles.fulfilIcon} aria-hidden />
              <span className={styles.fulfilText}>
                <strong className={styles.fulfilTitle}>{title}</strong>
                <span className={styles.fulfilSub}>{sub}</span>
                {rule ? (
                  <span className={styles.fulfilSub} data-testid={`fulfil-rule-${o.fulfilment.toLowerCase()}`}>
                    {feeRuleNote(t, rule)}
                  </span>
                ) : null}
              </span>
              <strong className={styles.fulfilPrice}>{price(o.feeMinor)}</strong>
            </button>
          );
        })}
      </div>
    </fieldset>
  );
}

const NOTE_ICONS: Icon[] = [CreditCard, ArrowsSplit, ArrowUUpLeft, ShieldCheck];

/** Cart summary rows, total, "How payment works" notes and the locked explanation (v2 cart summary). */
export function CartPreorderSummary({ cart, children }: { cart: Cart; children: (state: { locked: boolean; lockedLabel: string }) => ReactNode }) {
  const t = useT();
  const fmt = useFormatters();
  const pkg = cart.packages.find((p) => p.preorder);
  const block = pkg?.preorder ?? null;
  const c = useCartPreorderCopy(block);
  const { data: r } = usePreorderResolution();
  const split = cartSplit(cart.packages);
  if (!block || !c || !split.hasPreorder) return <>{children({ locked: false, lockedLabel: '' })}</>;
  const money = (m: number) => fmt.money(m, cart.currency);
  const rows: [string, string][] = [
    [t('preorder.cart.items', { count: split.itemCount }), money(split.itemsMinor)],
    [block.fulfilment === 'PICKUP' ? t('preorder.cart.rowPickup') : t('preorder.cart.rowDoor'), money(split.preorderDeliveryMinor)]
  ];
  if (split.hasOther) rows.push([c.metro ? t('preorder.cart.rowMetro') : t('preorder.cart.rowShip'), money(split.otherShippingMinor)]);
  const notes = payNotes(t, { total: money(split.totalMinor), methods: block.allowedMethods, dates: c.dates, split: split.hasOther });
  const showNotes = r?.placements.cart !== false;
  return (
    <>
      <dl className={styles.summaryRows} data-testid="cart-preorder-summary">
        {rows.map(([k, v]) => (
          <div key={k} className={styles.summaryRow}>
            <dt>{k}</dt>
            <dd>{v}</dd>
          </div>
        ))}
      </dl>
      <div className={styles.summaryTotal}>
        <span>{t('preorder.cart.total')}</span>
        <span data-testid="cart-preorder-total">{money(split.totalMinor)}</span>
      </div>
      {showNotes && <PayNotes notes={notes} />}
      {split.locked && c.copy.lockedText && (
        <p className={styles.locked} role="note" data-testid="cart-preorder-locked">
          {c.copy.lockedText}
        </p>
      )}
      {children({ locked: split.locked, lockedLabel: t('preorder.locked.button') })}
    </>
  );
}

export function PayNotes({ notes, className }: { notes: string[]; className?: string }) {
  const t = useT();
  return (
    <section className={cx(styles.payNotes, className)} aria-labelledby="preorder-pay-title" data-testid="preorder-pay-notes">
      <h3 id="preorder-pay-title" className={styles.payTitle}>
        {t('preorder.pay.title')}
      </h3>
      <ul className={styles.payList}>
        {notes.map((n, i) => {
          const NIcon = NOTE_ICONS[i] ?? CreditCard;
          return (
            <li key={n} className={styles.payNote}>
              <NIcon className={styles.payIcon} aria-hidden />
              {n}
            </li>
          );
        })}
      </ul>
    </section>
  );
}

