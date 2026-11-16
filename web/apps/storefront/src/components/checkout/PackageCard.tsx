'use client';

import { type CheckoutBlockedLine, checkoutKeys, type CheckoutPackage, type DeliveryOptionKey, type DeliveryPreferences, hasDeliveryWindow, type PlaceableOption } from '@afrotransact/api-client';
import { useFormatters } from '@afrotransact/i18n/react';
import { Button, ImagePlaceholder, Key, Lightning, QuantityStepper, Warning } from '@afrotransact/ui';
import { useQueryClient } from '@tanstack/react-query';

import { useT } from '@/i18n/t';
import { useCartMutation } from '@/lib/cart/useCart';
import { feeRuleNote } from '@/lib/fee-rule';

import styles from './Checkout.module.css';
import { pickupReasonKey } from './pickup-reason';
import { onRequestQuote } from './quote';

/** How one package reaches the shopper: a delivery option, or pickup at one of its pickup points. */
export type Choice = { option: DeliveryOptionKey; pickupPointId?: string; preferences: DeliveryPreferences };

/**
 * The option an order is placed with. quote_required is never payable on its own: with an agreed delivery quote
 * (`quoted`, sent as quoteId) the package is delivered and the quote's amount replaces the fee; without one the page blocks
 * the order, so the pickup fallback is never sent.
 */
export function placeable(option: DeliveryOptionKey, quoted = false): PlaceableOption {
  return option === 'quote_required' ? (quoted ? 'afrotransact_delivery' : 'pickup') : option;
}

/** Default choice per package: its first delivery option (pickup only when that is all it has). */
export function initialChoice(p: CheckoutPackage): Choice {
  const first = p.deliveryOptions.find((o) => o.key !== 'pickup') ?? p.deliveryOptions[0];
  return { option: first?.key ?? 'afrotransact_delivery', pickupPointId: p.pickupPoints?.[0]?.id, preferences: { handover: 'door', contact: 'text' } };
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

/** The arrival (or pickup-ready) text of a package for the chosen option: from a real window only, empty without one. */
export function promiseOf(pkg: CheckoutPackage, choice: Choice): { pickup: boolean; text: string } {
  const points = pkg.pickupPoints ?? [];
  const pickup = choice.option === 'pickup';
  const selected = pkg.deliveryOptions.find((o) => o.key === choice.option);
  const point = points.find((p) => p.id === choice.pickupPointId) ?? points[0];
  return { pickup, text: pickup ? (point?.ready ?? selected?.eta ?? '') : (selected?.eta ?? (hasDeliveryWindow(pkg.delivery) ? pkg.delivery.label : '')) };
}

/** What stops one item from being sold in the quantity chosen, in words, from the cart line's own stock (never an id). */
export type LineIssue = { kind: 'blocked'; line: CheckoutBlockedLine } | { kind: 'out' } | { kind: 'short'; available: number } | null;

export function lineIssue(l: CheckoutPackage['lines'][number], blocked: CheckoutBlockedLine[]): LineIssue {
  const b = blocked.find((x) => x.lineId === l.lineId);
  if (b) return { kind: 'blocked', line: b };
  if (l.stockStatus === 'OUT_OF_STOCK' || l.availableQty <= 0) return { kind: 'out' };
  if (l.quantity > l.availableQty) return { kind: 'short', available: l.availableQty };
  return null;
}

/** One order item: photo, title, quantity, price; a problem with it is said right here with the way out. */
function ItemRow({ line: l, issue, removing, onRemoveLine }: { line: CheckoutPackage['lines'][number]; issue: LineIssue; removing: boolean; onRemoveLine: (lineId: string) => void }) {
  const fmt = useFormatters();
  const t = useT();
  const qc = useQueryClient();
  const m = useCartMutation();
  return (
    <li className={styles.item} data-testid="checkout-item" data-issue={issue?.kind}>
      <ImagePlaceholder src={l.imageUrl} alt="" className={styles.itemThumb} />
      <div className={styles.itemInfo}>
        <span className={styles.itemTitle}>{l.title}</span>
        <span className={styles.itemMeta}>
          {fmt.money(l.priceMinor)}
          {l.perishable && <span className={styles.perishableTag}>{t('co.fresh')}</span>}
        </span>
        <div className={styles.itemQty}>
          <QuantityStepper
            value={l.quantity}
            max={Math.max(l.maxQuantity, l.quantity)}
            size="md"
            busy={m.isPending || removing}
            itemLabel={l.title}
            removeLabel={t('ld.remove')}
            onChange={(q) => m.mutate({ kind: 'quantity', lineId: l.lineId, quantity: q, title: l.title }, { onSuccess: () => void qc.invalidateQueries({ queryKey: checkoutKeys.all }) })}
            onRemove={() => onRemoveLine(l.lineId)}
          />
        </div>
        {issue && (
          <div className={styles.itemIssue} role="alert" data-testid={issue.kind === 'blocked' ? 'checkout-blocked-line' : 'checkout-line-issue'}>
            <strong>{issue.kind === 'blocked' ? t('co.blocked.item', { title: issue.line.title }) : issue.kind === 'out' ? t('co.line.out') : t('co.line.short', { n: issue.available })}</strong>
            {issue.kind === 'blocked' && <span>{t('co.blocked.next')}</span>}
            {issue.kind !== 'short' && (
              <Button size="sm" variant="secondary" loading={removing} onClick={() => onRemoveLine(l.lineId)} aria-label={t('co.blocked.removeAria', { title: l.title })}>
                {t('co.blocked.remove')}
              </Button>
            )}
          </div>
        )}
      </div>
      <strong className={styles.itemPrice}>{fmt.money(l.lineTotalMinor)}</strong>
    </li>
  );
}

/**
 * One package of the order: grouped by seller and delivery method, headed by the real arrival date (from the shared
 * ArrivalService; no date when there is no window), its items with a quantity selector, then how it reaches the shopper.
 * Delivery options are radios only where more than one is valid (AfroTransact delivery, pickup, seller ships); a single valid
 * option is plain text. No carrier is named and nothing says we collect or deliver unless the package is AfroTransact-delivered.
 */
export function PackageCard({
  pkg,
  n,
  total,
  choice,
  blocked = [],
  onChoice,
  onRemove,
  onRemoveLine = () => undefined,
  removingLine = null,
  onChangeAddress,
  removing
}: {
  pkg: CheckoutPackage;
  n: number;
  total: number;
  choice: Choice;
  blocked?: CheckoutBlockedLine[];
  onChoice: (c: Choice) => void;
  onRemove: () => void;
  onRemoveLine?: (lineId: string) => void;
  removingLine?: string | null;
  onChangeAddress: () => void;
  removing: boolean;
}) {
  const fmt = useFormatters();
  const t = useT();
  const points = pkg.pickupPoints ?? [];
  const deliveryOptions = pkg.deliveryOptions.filter((o) => o.key !== 'pickup');
  const hasPickupOption = pkg.deliveryOptions.some((o) => o.key === 'pickup');
  const canPickup = hasPickupOption && points.length > 0;
  const pickupOption = pkg.deliveryOptions.find((o) => o.key === 'pickup');
  const pickup = choice.option === 'pickup';
  const undeliverable = pkg.localDelivery?.available === false;
  const perishable = pkg.lines.some((l) => l.perishable);
  const atd = choice.option === 'afrotransact_delivery';
  const handover = perishable && atd ? 'hand' : (choice.preferences.handover ?? 'door');
  const needsCode = (atd || pickup) && (perishable || handover === 'hand');
  const prefs = (p: Partial<DeliveryPreferences>) => onChoice({ ...choice, preferences: { ...choice.preferences, ...p } });
  const point = points.find((p) => p.id === choice.pickupPointId) ?? points[0];
  // A date only from a real window: an option with no eta (and a package with no window) shows none.
  const promise = promiseOf(pkg, choice).text;
  const group = `pkg-${pkg.packageId}`;
  // Radios only when there is a real choice: more than one valid way to get this package.
  const validDelivery = deliveryOptions.filter((o) => o.key !== 'quote_required' && !undeliverable);
  const validCount = validDelivery.length + (canPickup ? 1 : 0);
  // A single valid option is plain text, unless the current choice is not it (a package that starts on "too far for delivery"
  // must be able to pick its one way forward, pickup).
  const choiceValid = pickup ? canPickup : validDelivery.some((o) => o.key === choice.option);
  const choose = validCount > 1 || (validCount === 1 && !choiceValid);

  const optionBody = (o: (typeof deliveryOptions)[number]) => {
    const isFree = !o.feeUnknown && (o.free || o.feeMinor === 0);
    return (
      <>
        <div className={styles.flatText}>
          <strong className={styles.optionTitle}>{undeliverable ? t('ld.notAvailableOption') : o.label}</strong>
          <span className={styles.optionDesc}>
            {o.eta}
            {o.cutoffLabel ? ` · ${o.cutoffLabel}` : ''}
          </span>
          {o.feeRule && !undeliverable ? (
            <span className={styles.optionDesc} data-testid={`fee-rule-${o.key}`}>
              {feeRuleNote(t, o.feeRule)}
            </span>
          ) : null}
        </div>
        <span className={o.feeUnknown ? styles.optionFee : isFree ? styles.optionFeeFree : styles.optionFee} data-testid={`option-fee-${o.key}`}>
          {o.feeUnknown ? t('co.fee.unknown') : isFree ? t('co.free') : fmt.money(o.feeMinor)}
        </span>
      </>
    );
  };

  return (
    <article className={styles.package} data-testid="checkout-package" aria-labelledby={`${group}-title`}>
      <header className={styles.packageHead}>
        {/* The date only from a real window; otherwise the method alone. */}
        <h3 id={`${group}-title`} className={styles.packageTitle} data-testid="package-promise">
          {promise ? (pickup ? t('co.pickupReady', { ready: promise }) : t('cart.arrives', { eta: promise })) : pickup ? t('co.fulfil.pickup') : t('cart.deliveryTitle')}
        </h3>
        <span className={styles.packageMeta} data-testid="package-meta">
          {total > 1 ? `${pkg.heading} · ${t('cart.package', { n, total })}` : pkg.heading}
        </span>
      </header>

      <ul className={styles.items} aria-label={t('co.itemsOf', { seller: pkg.seller.name })}>
        {pkg.lines.map((l) => (
          <ItemRow key={l.lineId} line={l} issue={lineIssue(l, blocked)} removing={removingLine === l.lineId} onRemoveLine={onRemoveLine} />
        ))}
      </ul>

      <div className={styles.packageBody}>
        {!pickup && <LocalDeliveryQuote pkg={pkg} onRemove={onRemove} onChangeAddress={onChangeAddress} removing={removing} />}

        <fieldset className={styles.optionList} disabled={undeliverable && !canPickup} aria-label={t('co.fulfilAria', { seller: pkg.seller.name })}>
          <legend className={styles.optionsLegend}>{t('co.deliveryOption')}</legend>
          {deliveryOptions.map((o) => {
            if (o.key === 'quote_required') {
              // Outside every delivery zone: not payable. The shopper asks for a quote, picks up, or changes the address.
              return (
                <div key={o.key} className={styles.optionRow} role="group" aria-label={o.label} data-testid="quote-required">
                  <Warning weight="fill" className={styles.flatIcon} aria-hidden />
                  <div className={styles.flatText}>
                    <strong className={styles.optionTitle}>{o.label}</strong>
                    <p className={styles.optionDesc}>{t('co.quote.help')}</p>
                    <div className={styles.optionActions}>
                      <Button size="sm" variant="secondary" onClick={() => onRequestQuote(pkg)} data-testid="request-quote">
                        {t('co.quote.cta')}
                      </Button>
                      <Button size="sm" variant="neutral" onClick={onChangeAddress}>
                        {t('ld.changeAddress')}
                      </Button>
                    </div>
                  </div>
                </div>
              );
            }
            const isSelected = choice.option === o.key;
            return choose ? (
              <label key={o.key} className={`${styles.optionRow} ${isSelected ? styles.optionRowActive : ''}`} data-testid={`delivery-option-${o.key}`}>
                <input
                  type="radio"
                  name={`opt-${pkg.packageId}`}
                  checked={isSelected}
                  onChange={() => onChoice({ ...choice, option: o.key })}
                  disabled={undeliverable}
                  aria-label={`${o.label} ${o.eta}`.trim()}
                />
                {optionBody(o)}
              </label>
            ) : (
              <div key={o.key} className={styles.optionRow} data-testid={`delivery-option-${o.key}`}>
                {optionBody(o)}
              </div>
            );
          })}

          {canPickup && point && (
            choose ? (
              <label className={`${styles.optionRow} ${pickup ? styles.optionRowActive : ''}`} data-testid="fulfil-pickup">
                <input
                  type="radio"
                  name={`opt-${pkg.packageId}`}
                  checked={pickup}
                  onChange={() => onChoice({ ...choice, option: 'pickup', pickupPointId: choice.pickupPointId ?? points[0]?.id })}
                  aria-label={`${t('co.fulfil.pickup')} ${point.name}`}
                />
                <div className={styles.flatText}>
                  <strong className={styles.optionTitle}>{t('co.fulfil.pickup')}</strong>
                  <span className={styles.optionDesc}>{t('co.pickupAt', { place: point.name, address: point.address })}</span>
                  <span className={styles.optionDesc}>{t('co.pickupReady', { ready: pickupOption?.eta || point.ready })}</span>
                </div>
                <span className={styles.optionFeeFree} data-testid="option-fee-pickup">{fmt.money(0)}</span>
              </label>
            ) : (
              <div className={styles.optionRow} data-testid="fulfil-pickup">
                <div className={styles.flatText}>
                  <strong className={styles.optionTitle}>{t('co.fulfil.pickup')}</strong>
                  <span className={styles.optionDesc}>{t('co.pickupAt', { place: point.name, address: point.address })}</span>
                  <span className={styles.optionDesc}>{t('co.pickupReady', { ready: pickupOption?.eta || point.ready })}</span>
                </div>
                <span className={styles.optionFeeFree} data-testid="option-fee-pickup">{fmt.money(0)}</span>
              </div>
            )
          )}
          {!canPickup && pickupReasonKey(pkg.pickupUnavailableReason) ? (
            <p className={styles.optionDesc} data-testid="pickup-unavailable">
              {t(pickupReasonKey(pkg.pickupUnavailableReason) ?? 'co.pickupNone.seller')}
            </p>
          ) : null}
        </fieldset>

        {pickup && points.length > 0 && (
          <fieldset className={styles.pointList} data-testid="pickup-points">
            <legend className={styles.optionsLegend}>{t('co.pickupChoose')}</legend>
            {points.map((pt) => (
              <label key={pt.id} className={styles.pointRow} data-testid="pickup-point">
                {points.length > 1 && (
                  <input
                    type="radio"
                    name={`pt-${pkg.packageId}`}
                    checked={point?.id === pt.id}
                    onChange={() => onChoice({ ...choice, option: 'pickup', pickupPointId: pt.id })}
                    aria-label={`${pt.name} ${pt.address}`}
                  />
                )}
                <span className={styles.flatText}>
                  <strong className={styles.optionTitle}>{pt.name}</strong>
                  <span className={styles.optionDesc}>{pt.address}</span>
                  <span className={styles.optionDesc}>
                    {pt.distanceMiles != null ? `${t('co.distance', { miles: fmt.number(pt.distanceMiles, { maximumFractionDigits: 1 }) })} · ` : ''}
                    {pt.hours} · {pt.ready}
                  </span>
                </span>
              </label>
            ))}
          </fieldset>
        )}
        {atd && !undeliverable && (
          <fieldset className={styles.prefsBox}>
            <legend className={styles.prefsLegend}>
              <span className={styles.prefsTitle}>{t('co.prefs')}</span>
              <span className={styles.prefsSubtitle}>{t('co.prefsHint')}</span>
            </legend>
            <div className={styles.prefGrid}>
              <label className={styles.prefField}>
                <span className={styles.prefFieldLabel}>{t('co.handoverLabel')}</span>
                <select className={styles.prefSelect} value={handover} onChange={(e) => prefs({ handover: e.target.value as DeliveryPreferences['handover'] })}>
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
                  <select className={styles.prefSelect} value={choice.preferences.safePlace ?? ''} onChange={(e) => prefs({ safePlace: e.target.value })}>
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
                <select className={styles.prefSelect} value={choice.preferences.contact ?? 'text'} onChange={(e) => prefs({ contact: e.target.value as DeliveryPreferences['contact'] })}>
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
