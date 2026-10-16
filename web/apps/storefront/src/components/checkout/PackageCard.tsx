'use client';

import { type CheckoutPackage, type DeliveryMethod, type DeliveryPreferences } from '@afrotransact/api-client';
import { useFormatters } from '@afrotransact/i18n/react';
import { AirplaneTilt, Button, ImagePlaceholder, Key, Lightning, MapPin, Storefront, Truck, Warning } from '@afrotransact/ui';

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
        <div className={styles.localQuoteHead}>
          <span className={styles.localQuoteBadge}>
            <Lightning weight="fill" aria-hidden /> AfroTransact Local Delivery
          </span>
          {ld.chargeMinor !== null && (
            <strong className={styles.localQuoteCharge}>
              {fmt.money(ld.chargeMinor)}
            </strong>
          )}
        </div>
        <div className={styles.localQuoteDetails}>
          <strong>{t('ld.title', { city })}</strong>
          {ld.chargeMinor !== null && ld.roadMiles !== null ? (
            <span>
              {t('ld.quote', { miles: fmt.number(ld.roadMiles, { maximumFractionDigits: 1 }), city, minutes: Math.round(ld.durationMinutes ?? 0) })} · {t('ld.charge', { amount: fmt.money(ld.chargeMinor) })}
            </span>
          ) : (
            <span className={styles.muted}>{ld.message ?? t('ld.pricedLater')}</span>
          )}
        </div>
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
 * Redesigned PackageCard:
 * - Package items with data-testid="checkout-item", title, thumbnail, quantity, price, and fresh tag.
 * - Delivery | Pickup mode toggle (pill segmented controls).
 * - Proper delivery method cards displayed directly (AfroTransact Delivery, Carrier Standard, Express)
 *   with carrier badges, arrival promise, pricing badges, and seamless selection.
 * - AfroTransact Delivery preferences and safe-place choices when platform delivery is selected.
 * - Pickup points listed as selectable cards with distance, address, hours, and free badge.
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
            <strong id={`${group}-title`} className={styles.sellerTitle}>{pkg.seller.name}</strong>
            <span className={styles.muted}>
              {pkg.heading} · {t('cart.package', { n, total })}
            </span>
          </div>
        </div>
        <div className={styles.arrivingTag} data-testid="package-promise">
          <Truck aria-hidden className={styles.arrivingIcon} />
          <strong>{pickup ? t('co.pickupReady', { ready: promise }) : t('cart.arriving', { eta: promise })}</strong>
        </div>
      </header>

      {/* Package Items */}
      <div className={styles.packageItems}>
        <ul className={styles.itemsList} aria-label={t('co.itemsOf', { seller: pkg.seller.name })}>
          {pkg.lines.map((l) => (
            <li key={l.lineId} className={styles.itemRow} data-testid="checkout-item">
              <div className={styles.itemThumbWrap}>
                <ImagePlaceholder src={l.imageUrl} alt="" className={styles.itemThumb} />
                {l.quantity > 1 && <span className={styles.itemQtyBadge}>{l.quantity}</span>}
              </div>
              <div className={styles.itemInfo}>
                <span className={styles.itemTitle}>{l.title}</span>
                <span className={styles.itemMeta}>
                  <span>Qty {l.quantity}</span>
                  <span className={styles.itemDot}>·</span>
                  <span>{fmt.money(l.priceMinor)} each</span>
                  {l.perishable && (
                    <>
                      <span className={styles.itemDot}>·</span>
                      <span className={styles.perishableTag}>Fresh / Perishable</span>
                    </>
                  )}
                </span>
              </div>
              <div className={styles.itemPrice}>
                {fmt.money(l.lineTotalMinor)}
              </div>
            </li>
          ))}
        </ul>
      </div>

      <div className={styles.packageBody}>
        {canPickup && (
          <div className={styles.toggle} role="radiogroup" aria-label={t('co.fulfilAria', { seller: pkg.seller.name })}>
            <button
              type="button"
              role="radio"
              aria-checked={!pickup}
              className={!pickup ? styles.toggleOn : styles.toggleOff}
              onClick={() => setMode('delivery')}
              data-testid="fulfil-delivery"
            >
              <Truck aria-hidden />
              <span>{t('co.fulfil.delivery')}</span>
            </button>
            <button
              type="button"
              role="radio"
              aria-checked={pickup}
              className={pickup ? styles.toggleOn : styles.toggleOff}
              onClick={() => setMode('pickup')}
              data-testid="fulfil-pickup"
            >
              <Storefront aria-hidden />
              <span>{t('co.fulfil.pickup')}</span>
            </button>
          </div>
        )}

        {!pickup && <LocalDeliveryQuote pkg={pkg} onRemove={onRemove} onChangeAddress={onChangeAddress} removing={removing} />}

        {!pickup ? (
          <fieldset className={styles.deliveryOptionsSection} disabled={undeliverable}>
            <legend className={styles.optionsSectionTitle}>{t('co.deliveryOption')}</legend>
            <div className={styles.deliveryGrid}>
              {deliveryOptions.map((o) => {
                const isSelected = choice.option === o.key;
                const isATD = o.key === 'afrotransact_delivery';
                const isExpress = o.key === 'express';
                const isFree = o.free || o.feeMinor === 0;

                return (
                  <label
                    key={o.key}
                    className={`${styles.deliveryOptionCard} ${isSelected ? styles.deliveryOptionCardActive : ''}`}
                  >
                    <input
                      type="radio"
                      role="radio"
                      name={`opt-${pkg.packageId}`}
                      checked={isSelected}
                      onChange={() => onChoice({ ...choice, option: o.key })}
                      disabled={undeliverable}
                      aria-label={`${o.label} ${o.eta}`}
                    />
                    <div className={styles.optionCardIcon}>
                      {isATD ? (
                        <Lightning weight="fill" className={styles.atdIcon} aria-hidden />
                      ) : isExpress ? (
                        <AirplaneTilt weight="fill" className={styles.expressIcon} aria-hidden />
                      ) : (
                        <Truck weight="fill" className={styles.carrierIcon} aria-hidden />
                      )}
                    </div>
                    <div className={styles.optionCardContent}>
                      <div className={styles.optionCardHeader}>
                        <div className={styles.optionTitleRow}>
                          <strong className={styles.optionCardTitle}>{o.eta}</strong>
                          {isATD && <span className={styles.atdBadge}>⚡ AfroTransact Fast</span>}
                          {isExpress && <span className={styles.expressBadge}>Express Air</span>}
                        </div>
                        <span className={isFree ? styles.optionFeeFree : styles.optionFee}>
                          {isFree ? t('co.free') : fmt.money(o.feeMinor)}
                        </span>
                      </div>
                      <p className={styles.optionCardDesc}>
                        {undeliverable ? t('ld.notAvailableOption') : o.label}
                        {o.cutoffLabel ? ` · ${o.cutoffLabel}` : ''}
                      </p>
                    </div>
                  </label>
                );
              })}
            </div>
          </fieldset>
        ) : (
          <div className={styles.pickupSection}>
            {points.length === 0 ? (
              <p className={styles.muted} role="status">
                {t('co.pickupNoneNear')}
              </p>
            ) : (
              <fieldset className={styles.pickupGrid} data-testid="pickup-points">
                <legend className={styles.optionsSectionTitle}>{t('co.pickupChoose')}</legend>
                {points.map((pt) => {
                  const isPtSelected = point?.id === pt.id;
                  return (
                    <label
                      key={pt.id}
                      className={`${styles.pickupOptionCard} ${isPtSelected ? styles.pickupOptionCardActive : ''}`}
                      data-testid="pickup-point"
                    >
                      <input
                        type="radio"
                        role="radio"
                        name={`pt-${pkg.packageId}`}
                        checked={isPtSelected}
                        onChange={() => onChoice({ ...choice, option: 'pickup', pickupPointId: pt.id })}
                        aria-label={`${pt.name} ${pt.address}`}
                      />
                      <div className={styles.pickupCardIcon}>
                        <MapPin weight="fill" aria-hidden />
                      </div>
                      <div className={styles.pickupCardContent}>
                        <div className={styles.pickupCardHeader}>
                          <strong className={styles.pickupPointName}>{pt.name}</strong>
                          <span className={styles.optionFeeFree}>{t('co.free')}</span>
                        </div>
                        <p className={styles.pickupPointAddress}>{pt.address}</p>
                        <div className={styles.pickupPointMeta}>
                          {pt.distanceMiles != null && (
                            <span className={styles.pickupDistance}>
                              {t('co.distance', { miles: fmt.number(pt.distanceMiles, { maximumFractionDigits: 1 }) })}
                            </span>
                          )}
                          <span className={styles.pickupHours}>· Mon–Sat 8 am–8 pm · Ready {pt.ready}</span>
                          <span className={styles.pickupTag}>
                            {pt.setBy === 'seller' ? t('co.setBySeller') : t('co.setByPlatform')}
                          </span>
                        </div>
                      </div>
                    </label>
                  );
                })}
              </fieldset>
            )}
          </div>
        )}

        {atd && !undeliverable && (
          <fieldset className={styles.prefsBox}>
            <legend className={styles.prefsLegend}>
              <span className={styles.prefsTitle}>{t('co.prefs')}</span>
              <span className={styles.prefsSubtitle}>Safe place, handover instructions & driver contact</span>
            </legend>
            <div className={styles.prefGrid}>
              <label className={styles.prefField}>
                <span className={styles.prefFieldLabel}>{t('co.handoverLabel')}</span>
                <select
                  className={styles.prefSelect}
                  value={handover}
                  onChange={(e) => prefs({ handover: e.target.value as DeliveryPreferences['handover'] })}
                >
                  {(['door', 'hand', 'desk'] as const).map((h) => (
                    <option key={h} value={h} disabled={perishable && h !== 'hand'}>
                      {t(`co.handover.${h}`)}
                    </option>
                  ))}
                </select>
              </label>

              {handover === 'door' && (
                <label className={styles.prefField}>
                  <span className={styles.prefFieldLabel}>{t('co.safePlace')}</span>
                  <select
                    className={styles.prefSelect}
                    value={choice.preferences.safePlace ?? ''}
                    onChange={(e) => prefs({ safePlace: e.target.value })}
                  >
                    <option value="">{t('co.safePlace')}</option>
                    {['Front door', 'Back porch', 'Garage', 'Mailroom'].map((pl) => (
                      <option key={pl} value={pl}>
                        {pl}
                      </option>
                    ))}
                  </select>
                </label>
              )}

              <label className={styles.prefField}>
                <span className={styles.prefFieldLabel}>{t('co.contact')}</span>
                <select
                  className={styles.prefSelect}
                  value={choice.preferences.contact ?? 'text'}
                  onChange={(e) => prefs({ contact: e.target.value as DeliveryPreferences['contact'] })}
                >
                  <option value="text">{t('co.contact.text')}</option>
                  <option value="call">{t('co.contact.call')}</option>
                  <option value="none">{t('co.contact.none')}</option>
                </select>
              </label>
            </div>

            <label className={styles.prefFieldFull}>
              <span className={styles.prefFieldLabel}>{t('co.instructions')}</span>
              <textarea
                className={styles.prefTextarea}
                rows={2}
                maxLength={240}
                placeholder={t('co.instructionsPlaceholder')}
                value={choice.preferences.instructions ?? ''}
                onChange={(e) => prefs({ instructions: e.target.value })}
              />
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
