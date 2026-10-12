'use client';

import { type CheckoutPackage, type DeliveryMethod, type DeliveryPreferences } from '@afrotransact/api-client';
import { useFormatters } from '@afrotransact/i18n/react';
import { Button, ImagePlaceholder, Key, MapPin, Storefront, Truck, Warning } from '@afrotransact/ui';

import { useT } from '@/i18n/t';

import styles from './Checkout.module.css';

/** How one package reaches the shopper: a delivery option, or pickup at one of its pickup points. */
export type Choice = { option: DeliveryMethod; pickupPointId?: string; preferences: DeliveryPreferences };

/** Default choice per package: its first delivery option (pickup only when that is all it has). */
export function initialChoice(p: CheckoutPackage): Choice {
  const first = p.deliveryOptions.find((o) => o.key !== 'pickup') ?? p.deliveryOptions[0];
  return { option: first?.key ?? 'carrier', pickupPointId: p.pickupPoints?.[0]?.id, preferences: { handover: 'door', contact: 'text' } };
}

/** The package's fee for a choice (pickup is free). */
export function feeOf(p: CheckoutPackage, c: Choice | undefined): number {
  const o = p.deliveryOptions.find((x) => x.key === (c?.option ?? initialChoice(p).option));
  return o ? o.feeMinor : 0;
}

/** AfroTransact Delivery from the seller's store (S255): the quoted charge, or why this address can't be served. */
function LocalDeliveryQuote({ pkg, onRemove, onChangeAddress, removing }: { pkg: CheckoutPackage; onRemove: () => void; onChangeAddress: () => void; removing: boolean }) {
  const fmt = useFormatters();
  const t = useT();
  const ld = pkg.localDelivery;
  if (!ld) return null;
  const city = ld.fromCity || pkg.seller.city;
  if (ld.available) {
    return (
      <div className={styles.localQuote} data-testid="local-delivery-quote">
        <strong>{t('ld.title', { city })}</strong>
        {ld.chargeMinor !== null && ld.roadMiles !== null ? (
          <span>
            {t('ld.quote', { miles: fmt.number(ld.roadMiles, { maximumFractionDigits: 1 }), city, minutes: Math.round(ld.durationMinutes ?? 0) })} · {t('ld.charge', { amount: fmt.money(ld.chargeMinor) })}
          </span>
        ) : (
          <span className={styles.muted}>{ld.message ?? t('ld.pricedLater')}</span>
        )}
      </div>
    );
  }
  return (
    <div className={styles.localProblem} role="group" aria-labelledby={`ld-${pkg.packageId}`} data-testid="local-delivery-problem">
      <strong id={`ld-${pkg.packageId}`}>
        <Warning weight="fill" aria-hidden /> {ld.unavailableReason === 'OUT_OF_RANGE' ? t('ld.outOfRangeTitle') : t('ld.unavailableTitle')}
      </strong>
      {ld.message && <p className={styles.localMessage}>{ld.message}</p>}
      <div className={styles.localActions}>
        <Button size="sm" variant="secondary" loading={removing} onClick={onRemove} aria-label={t('ld.removeAria', { seller: pkg.seller.name })}>
          {t('ld.remove')}
        </Button>
        <Button size="sm" variant="neutral" onClick={onChangeAddress}>
          {t('ld.changeAddress')}
        </Button>
      </div>
    </div>
  );
}

/**
 * One seller group: Delivery or Pickup (pickup lists the group's pickup points with address, hours, ready time and
 * distance), the delivery options or preferences, and every item with image, title, unit, quantity and price.
 */
export function PackageCard({
  pkg,
  n,
  total,
  choice,
  onChoice,
  onRemove,
  onChangeAddress,
  removing
}: {
  pkg: CheckoutPackage;
  n: number;
  total: number;
  choice: Choice;
  onChoice: (c: Choice) => void;
  onRemove: () => void;
  onChangeAddress: () => void;
  removing: boolean;
}) {
  const fmt = useFormatters();
  const t = useT();
  const points = pkg.pickupPoints ?? [];
  const deliveryOptions = pkg.deliveryOptions.filter((o) => o.key !== 'pickup');
  const hasPickupOption = pkg.deliveryOptions.some((o) => o.key === 'pickup');
  const canPickup = hasPickupOption && (points.length > 0 || deliveryOptions.length > 0);
  const pickup = choice.option === 'pickup';
  const undeliverable = pkg.localDelivery?.available === false;
  const perishable = pkg.lines.some((l) => l.perishable);
  const atd = choice.option === 'afrotransact_delivery';
  const handover = perishable && atd ? 'hand' : (choice.preferences.handover ?? 'door');
  const needsCode = (atd || pickup) && (perishable || handover === 'hand');
  const prefs = (p: Partial<DeliveryPreferences>) => onChoice({ ...choice, preferences: { ...choice.preferences, ...p } });
  const selected = pkg.deliveryOptions.find((o) => o.key === choice.option);
  const point = points.find((p) => p.id === choice.pickupPointId) ?? points[0];
  const setMode = (mode: 'delivery' | 'pickup') =>
    mode === 'pickup'
      ? onChoice({ ...choice, option: 'pickup', pickupPointId: choice.pickupPointId ?? points[0]?.id })
      : onChoice({ ...choice, option: deliveryOptions[0]?.key ?? 'carrier' });
  const promise = pickup ? (point?.ready ?? selected?.eta ?? '') : (selected?.eta ?? pkg.delivery.label);
  const group = `pkg-${pkg.packageId}`;

  return (
    <article className={styles.package} data-testid="checkout-package" aria-labelledby={`${group}-title`}>
      <header className={styles.packageHead}>
        <div className={styles.packageHeadMain}>
          <div className={styles.packageTitle}>
            <strong id={`${group}-title`}>{pkg.seller.name}</strong>
            <span className={styles.muted}>
              {pkg.heading} · {t('cart.package', { n, total })}
            </span>
          </div>
          <ul className={styles.itemStrip} aria-label={t('co.itemsOf', { seller: pkg.seller.name })}>
            {pkg.lines.map((l) => (
              <li key={l.lineId} className={styles.itemStripCell} data-testid="checkout-item-thumb">
                <ImagePlaceholder src={l.imageUrl} alt="" className={styles.stripThumb} />
                {l.quantity > 1 ? <span className={styles.stripQty}>{l.quantity}</span> : null}
              </li>
            ))}
          </ul>
        </div>
        <strong className={styles.arriving} data-testid="package-promise">
          {pickup ? t('co.pickupReady', { ready: promise }) : t('cart.arriving', { eta: promise })}
        </strong>
      </header>

      <div className={styles.packageBody}>
        {canPickup && (
          <div className={styles.segmented} role="radiogroup" aria-label={t('co.fulfilAria', { seller: pkg.seller.name })}>
            {(['delivery', 'pickup'] as const).map((m) => {
              const on = (m === 'pickup') === pickup;
              return (
                <button key={m} type="button" role="radio" aria-checked={on} className={on ? styles.segmentOn : styles.segment} onClick={() => setMode(m)} data-testid={`fulfil-${m}`}>
                  {m === 'pickup' ? <Storefront aria-hidden /> : <Truck aria-hidden />}
                  <span>{m === 'pickup' ? t('co.fulfil.pickup') : t('co.fulfil.delivery')}</span>
                  <small>{m === 'pickup' ? t('co.free') : deliveryOptions.some((o) => o.free || o.feeMinor === 0) ? t('co.free') : t('co.fromFee', { amount: fmt.money(Math.min(...deliveryOptions.map((o) => o.feeMinor))) })}</small>
                </button>
              );
            })}
          </div>
        )}

        {!pickup && <LocalDeliveryQuote pkg={pkg} onRemove={onRemove} onChangeAddress={onChangeAddress} removing={removing} />}

        {pickup ? (
          <fieldset className={`${styles.options} ${styles.optionsCompact}`} data-testid="pickup-points">
            <legend className={styles.legend}>{t('co.pickupChoose')}</legend>
            {points.length === 0 ? (
              <p className={styles.muted} role="status">
                {t('co.pickupNoneNear')}
              </p>
            ) : null}
            {points.map((pt) => (
              <label key={pt.id} className={`${styles.point} ${styles.pointCompact}`} data-testid="pickup-point">
                <input type="radio" name={`pt-${pkg.packageId}`} checked={point?.id === pt.id} onChange={() => onChoice({ ...choice, option: 'pickup', pickupPointId: pt.id })} />
                <span className={styles.pointText}>
                  <span className={styles.pointName}>
                    <strong>{pt.name}</strong>
                    <span className={styles.tag}>{pt.setBy === 'seller' ? t('co.setBySeller') : t('co.setByPlatform')}</span>
                  </span>
                  <span className={styles.pointLine}>
                    <MapPin aria-hidden /> {pt.address}
                    {pt.distanceMiles != null && <span className={styles.muted}> · {t('co.distance', { miles: fmt.number(pt.distanceMiles, { maximumFractionDigits: 1 }) })}</span>}
                    <span className={styles.muted}> · {pt.ready}</span>
                  </span>
                </span>
                <span className={styles.optionFee}>{t('co.free')}</span>
              </label>
            ))}
          </fieldset>
        ) : (
          <fieldset className={`${styles.options} ${styles.optionsCompact}`} disabled={undeliverable}>
            <legend className={styles.legend}>{t('co.deliveryOption')}</legend>
            <div className={styles.optionsRow}>
              {deliveryOptions.map((o) => (
                <label key={o.key} className={`${styles.option} ${styles.optionCompact}`}>
                  <input type="radio" name={`opt-${pkg.packageId}`} checked={choice.option === o.key} onChange={() => onChoice({ ...choice, option: o.key })} />
                  <span className={styles.optionText}>
                    <strong className={styles.eta}>{o.eta}</strong>
                    <span className={styles.muted}>
                      {undeliverable ? t('ld.notAvailableOption') : o.label}
                      {o.cutoffLabel && choice.option === o.key ? ` · ${o.cutoffLabel}` : ''}
                    </span>
                  </span>
                  <span className={styles.optionFee}>{o.free || o.feeMinor === 0 ? t('co.free') : fmt.money(o.feeMinor)}</span>
                </label>
              ))}
            </div>
          </fieldset>
        )}

        {atd && !undeliverable && (
          <fieldset className={styles.prefs}>
            <legend className={styles.legend}>{t('co.prefs')}</legend>
            <div className={styles.prefChoices}>
              {(['hand', 'door', 'desk'] as const).map((h) => (
                <label key={h} className={styles.option} aria-label={t(`co.handover.${h}`)}>
                  <input type="radio" name={`ho-${pkg.packageId}`} checked={handover === h} disabled={perishable && h !== 'hand'} onChange={() => prefs({ handover: h })} />
                  <span className={styles.optionText}>
                    <strong>{t(`co.handover.${h}`)}</strong>
                    <span className={styles.muted}>{t(`co.handover.${h}.hint`)}</span>
                  </span>
                </label>
              ))}
            </div>
            {handover === 'door' && (
              <select aria-label={t('co.safePlace')} value={choice.preferences.safePlace ?? ''} onChange={(e) => prefs({ safePlace: e.target.value })}>
                <option value="">{t('co.safePlace')}</option>
                {['Front door', 'Back porch', 'Garage', 'Mailroom'].map((p) => (
                  <option key={p} value={p}>
                    {p}
                  </option>
                ))}
              </select>
            )}
            <label className={styles.field}>
              {t('co.instructions')}
              <textarea rows={2} maxLength={240} placeholder={t('co.instructionsPlaceholder')} value={choice.preferences.instructions ?? ''} onChange={(e) => prefs({ instructions: e.target.value })} />
            </label>
            <label className={styles.field}>
              {t('co.contact')}
              <select value={choice.preferences.contact ?? 'text'} onChange={(e) => prefs({ contact: e.target.value as DeliveryPreferences['contact'] })}>
                <option value="text">{t('co.contact.text')}</option>
                <option value="call">{t('co.contact.call')}</option>
                <option value="none">{t('co.contact.none')}</option>
              </select>
            </label>
          </fieldset>
        )}
        {needsCode && (
          <p className={styles.codeNote}>
            <Key aria-hidden /> {perishable ? t('co.codePerishable') : t('co.codeHand')}
          </p>
        )}

      </div>
    </article>
  );
}
