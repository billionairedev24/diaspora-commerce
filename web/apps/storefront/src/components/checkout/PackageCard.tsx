'use client';

import { type CheckoutPackage, type DeliveryMethod, type DeliveryPreferences } from '@afrotransact/api-client';
import { useFormatters } from '@afrotransact/i18n/react';
import { Button, ImagePlaceholder, Key, MapPin, Storefront, Truck, Warning } from '@afrotransact/ui';
import { useState } from 'react';

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
 * One seller group, compact: the items as thumbnails, Delivery | Pickup, and the chosen option or pickup point on one
 * line with Change (the other options or nearby points as a short list). Delivery instructions stay behind a link.
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
  const [listOpen, setListOpen] = useState(false);
  const [prefsOpen, setPrefsOpen] = useState(!!(choice.preferences.instructions || choice.preferences.safePlace));

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
          <div className={styles.toggle} role="radiogroup" aria-label={t('co.fulfilAria', { seller: pkg.seller.name })}>
            {(['delivery', 'pickup'] as const).map((m) => {
              const on = (m === 'pickup') === pickup;
              return (
                <button key={m} type="button" role="radio" aria-checked={on} className={on ? styles.toggleOn : styles.toggleOff} onClick={() => { setMode(m); setListOpen(false); }} data-testid={`fulfil-${m}`}>
                  {m === 'pickup' ? <Storefront aria-hidden /> : <Truck aria-hidden />}
                  {m === 'pickup' ? t('co.fulfil.pickup') : t('co.fulfil.delivery')}
                </button>
              );
            })}
          </div>
        )}

        {!pickup && <LocalDeliveryQuote pkg={pkg} onRemove={onRemove} onChangeAddress={onChangeAddress} removing={removing} />}

        {pickup ? (
          points.length === 0 ? (
            <p className={styles.muted} role="status">
              {t('co.pickupNoneNear')}
            </p>
          ) : (
            <div className={styles.choiceBlock} data-testid="pickup-points">
              {point && (
                <div className={styles.choiceLine} data-testid="pickup-point">
                  <MapPin aria-hidden className={styles.choiceIcon} />
                  <span className={styles.choiceText}>
                    <strong>{point.name}</strong>
                    <span className={styles.muted}>
                      {point.address}
                      {point.distanceMiles != null ? ` · ${t('co.distance', { miles: fmt.number(point.distanceMiles, { maximumFractionDigits: 1 }) })}` : ''} · {point.ready}
                    </span>
                  </span>
                  <span className={styles.optionFee}>{t('co.free')}</span>
                  {points.length > 1 && (
                    <button type="button" className="link-button" aria-expanded={listOpen} onClick={() => setListOpen(!listOpen)}>
                      {listOpen ? t('common.cancel') : t('co.change')}
                    </button>
                  )}
                </div>
              )}
              {listOpen && (
                <fieldset className={styles.choiceList}>
                  <legend className="at-visually-hidden">{t('co.pickupChoose')}</legend>
                  {points.map((pt) => (
                    <label key={pt.id} className={styles.choiceRow}>
                      <input type="radio" name={`pt-${pkg.packageId}`} checked={point?.id === pt.id} onChange={() => { onChoice({ ...choice, option: 'pickup', pickupPointId: pt.id }); setListOpen(false); }} />
                      <span className={styles.choiceText}>
                        <strong>{pt.name}</strong>
                        <span className={styles.muted}>
                          {pt.address}
                          {pt.distanceMiles != null ? ` · ${t('co.distance', { miles: fmt.number(pt.distanceMiles, { maximumFractionDigits: 1 }) })}` : ''} · {pt.ready}
                        </span>
                      </span>
                      <span className={styles.tag}>{pt.setBy === 'seller' ? t('co.setBySeller') : t('co.setByPlatform')}</span>
                    </label>
                  ))}
                </fieldset>
              )}
            </div>
          )
        ) : (
          <div className={styles.choiceBlock}>
            {selected && (
              <div className={styles.choiceLine} data-testid="delivery-choice">
                <Truck aria-hidden className={styles.choiceIcon} />
                <span className={styles.choiceText}>
                  <strong>{selected.eta}</strong>
                  <span className={styles.muted}>
                    {undeliverable ? t('ld.notAvailableOption') : selected.label}
                    {selected.cutoffLabel ? ` · ${selected.cutoffLabel}` : ''}
                  </span>
                </span>
                <span className={styles.optionFee}>{selected.free || selected.feeMinor === 0 ? t('co.free') : fmt.money(selected.feeMinor)}</span>
                {deliveryOptions.length > 1 && !undeliverable && (
                  <button type="button" className="link-button" aria-expanded={listOpen} onClick={() => setListOpen(!listOpen)} aria-label={t('co.changeAria', { step: t('co.deliveryOption') })}>
                    {listOpen ? t('common.cancel') : t('co.change')}
                  </button>
                )}
              </div>
            )}
            {listOpen && (
              <fieldset className={styles.choiceList} disabled={undeliverable}>
                <legend className="at-visually-hidden">{t('co.deliveryOption')}</legend>
                {deliveryOptions.map((o) => (
                  <label key={o.key} className={styles.choiceRow}>
                    <input type="radio" name={`opt-${pkg.packageId}`} checked={choice.option === o.key} onChange={() => { onChoice({ ...choice, option: o.key }); setListOpen(false); }} />
                    <span className={styles.choiceText}>
                      <strong>{o.eta}</strong>
                      <span className={styles.muted}>{o.label}</span>
                    </span>
                    <span className={styles.optionFee}>{o.free || o.feeMinor === 0 ? t('co.free') : fmt.money(o.feeMinor)}</span>
                  </label>
                ))}
              </fieldset>
            )}
          </div>
        )}

        {atd && !undeliverable && (
          prefsOpen ? (
            <fieldset className={styles.prefs}>
              <legend className={styles.legend}>{t('co.prefs')}</legend>
              <label className={styles.field}>
                {t('co.handoverLabel')}
                <select value={handover} onChange={(e) => prefs({ handover: e.target.value as DeliveryPreferences['handover'] })}>
                  {(['hand', 'door', 'desk'] as const).map((h) => (
                    <option key={h} value={h} disabled={perishable && h !== 'hand'}>
                      {t(`co.handover.${h}`)}
                    </option>
                  ))}
                </select>
              </label>
              {handover === 'door' && (
                <label className={styles.field}>
                  {t('co.safePlace')}
                  <select value={choice.preferences.safePlace ?? ''} onChange={(e) => prefs({ safePlace: e.target.value })}>
                    <option value="">{t('co.safePlace')}</option>
                    {['Front door', 'Back porch', 'Garage', 'Mailroom'].map((pl) => (
                      <option key={pl} value={pl}>
                        {pl}
                      </option>
                    ))}
                  </select>
                </label>
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
          ) : (
            <button type="button" className={`link-button ${styles.prefsLink}`} onClick={() => setPrefsOpen(true)} data-testid="add-instructions">
              {t('co.addInstructions')}
            </button>
          )
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
