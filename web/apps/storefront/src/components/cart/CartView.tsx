'use client';

import { type CartLine, type CartPackage, SearchQuerySchema } from '@afrotransact/api-client';
import { useFormatters } from '@afrotransact/i18n/react';
import { Button, buttonClassName, CheckCircle, FulfilmentBadge, ImagePlaceholder, QuantityStepper, Skeleton, Stars } from '@afrotransact/ui';
import { useQuery } from '@tanstack/react-query';
import Link from 'next/link';
import { useState } from 'react';

import { useT } from '@/i18n/t';
import { browserApi } from '@/lib/api/browser';
import { searchQueries } from '@/lib/api/queries';
import { useCart, useCartMutation } from '@/lib/cart/useCart';
import { productHref, searchHref } from '@/lib/links-search';

import styles from './Cart.module.css';

function Line({ line, pkg }: { line: CartLine; pkg: CartPackage }) {
  const fmt = useFormatters();
  const t = useT();
  const m = useCartMutation();
  const low = line.stockStatus === 'LOW_STOCK';
  const out = line.stockStatus === 'OUT_OF_STOCK';
  return (
    <li className={styles.line} data-testid="cart-line">
      <input type="checkbox" className={styles.select} checked={line.selected} onChange={(e) => m.mutate({ kind: 'select', lineId: line.lineId, selected: e.target.checked })} aria-label={`${t('cart.include')}: ${line.title}`} />
      <Link href={productHref(line.atin)} className={styles.thumb} tabIndex={-1} aria-hidden>
        <ImagePlaceholder src={line.imageUrl} alt="" />
      </Link>
      <div className={styles.lineBody}>
        <Link href={productHref(line.atin)} className={styles.lineTitle}>
          {line.title}
        </Link>
        <span className={`${styles.stock} ${low || out ? styles.lowStock : styles.inStock}`}>
          {out ? t('cart.unavailableLine') : low ? t('cart.onlyLeft', { n: line.availableQty }) : t('cart.inStock')}
        </span>
        <FulfilmentBadge fulfilment={pkg.fulfilment} shipsFrom={pkg.seller.city} />
        <span className={styles.small}>
          {t('cart.soldBy')} <span style={{ color: 'var(--at-color-link)' }}>{pkg.seller.name}</span> · {line.unit}
        </span>
        <span className={styles.eta}>
          {pkg.delivery.prefix} <strong>{pkg.delivery.label}</strong>
        </span>
        <div className={styles.actions}>
          <QuantityStepper
            value={line.quantity}
            max={line.maxQuantity}
            busy={m.isPending}
            itemLabel={line.title}
            onChange={(q) => m.mutate({ kind: 'quantity', lineId: line.lineId, quantity: q, title: line.title })}
            onRemove={() => m.mutate({ kind: 'remove', lineId: line.lineId, title: line.title })}
          />
          <span className="sep" aria-hidden>
            |
          </span>
          <button type="button" className="link-button" onClick={() => m.mutate({ kind: 'remove', lineId: line.lineId, title: line.title })}>
            {t('cart.delete')}
          </button>
          <span className="sep" aria-hidden>
            |
          </span>
          <button type="button" className="link-button" onClick={() => m.mutate({ kind: 'save', lineId: line.lineId, title: line.title })}>
            {t('cart.saveForLater')}
          </button>
          <span className="sep" aria-hidden>
            |
          </span>
          <Link href={searchHref({ department: line.department })}>{t('cart.compare')}</Link>
        </div>
      </div>
      <div className={styles.linePrice}>
        <strong className={styles.linePriceMain}>{fmt.money(line.lineTotalMinor)}</strong>
        {line.quantity > 1 && <div className={styles.each}>{t('cart.each', { price: fmt.money(line.priceMinor) })}</div>}
      </div>
    </li>
  );
}

function Package({ pkg, n, total }: { pkg: CartPackage; n: number; total: number }) {
  const fmt = useFormatters();
  const t = useT();
  return (
    <section className={styles.package} aria-labelledby={`pkg-${pkg.packageId}`} data-testid="cart-package">
      <div className={styles.packageHead}>
        <h2 id={`pkg-${pkg.packageId}`} className={styles.packageTitle}>
          {t('cart.arriving', { eta: pkg.delivery.label })}
        </h2>
        <span className={styles.packageMeta}>
          {pkg.heading} · {t('cart.package', { n, total })}
        </span>
      </div>
      <ul className={styles.options} aria-label={t('cart.deliveryOptions')}>
        <li className={styles.optionsLabel}>{t('cart.deliveryOptions')}:</li>
        {pkg.deliveryOptions.map((o) => (
          <li key={o.key}>
            {o.label} — {o.eta}
          </li>
        ))}
        <li>({t('cart.chooseAtCheckout')})</li>
      </ul>
      {pkg.freeShippingNudge && (
        <p className={styles.nudge}>
          <CheckCircle weight="fill" aria-hidden /> {t('cart.nudge', { amount: fmt.money(pkg.freeShippingNudge.remainingMinor), seller: pkg.seller.name })}
        </p>
      )}
      <ul className={styles.lines}>
        {pkg.lines.map((l) => (
          <Line key={l.lineId} line={l} pkg={pkg} />
        ))}
      </ul>
    </section>
  );
}

function Recommendations({ exclude }: { exclude: Set<string> }) {
  const fmt = useFormatters();
  const t = useT();
  const { data } = useQuery(searchQueries.results(browserApi(), SearchQuerySchema.parse({ sort: 'rating', size: 12 })));
  const m = useCartMutation();
  const items = (data?.hits ?? []).filter((h) => !exclude.has(h.atin) && h.inStock).slice(0, 4);
  if (!items.length) return null;
  return (
    <section className={styles.recs} aria-labelledby="recs-title">
      <strong id="recs-title">{t('cart.alsoBought')}</strong>
      {items.map((p) => (
        <div key={p.atin} className={styles.rec}>
          <Link href={productHref(p.atin)} tabIndex={-1} aria-hidden>
            <ImagePlaceholder src={p.imageUrl} alt="" />
          </Link>
          <div className={styles.recBody}>
            <Link href={productHref(p.atin)} className={styles.recName}>
              {p.title}
            </Link>
            <Stars rating={p.rating} reviewCount={p.reviewCount} size={12} />
            <strong className={styles.recPrice}>{fmt.money(p.priceMinor)}</strong>
            <Button size="sm" onClick={() => m.mutate({ kind: 'add', offerId: p.offerId, quantity: 1, title: p.title })} aria-label={`${t('card.addToCart')}: ${p.title}`}>
              {t('card.addToCart')}
            </Button>
          </div>
        </div>
      ))}
    </section>
  );
}

/** Cart: one package per seller with fulfilment info, stock-capped steppers, save for later, subtotal. */
export function CartView() {
  const fmt = useFormatters();
  const t = useT();
  const { data: cart, isPending, isError, refetch } = useCart();
  const m = useCartMutation();
  const [gift, setGift] = useState(false);

  if (isPending) {
    return (
      <div className={styles.layout} aria-busy="true" aria-label={t('cart.loading')}>
        <div className={styles.card}>
          <Skeleton height={36} width={260} />
          <div style={{ display: 'grid', gap: 12, marginTop: 24 }}>
            <Skeleton height={120} />
            <Skeleton height={120} />
          </div>
        </div>
        <Skeleton height={160} />
      </div>
    );
  }
  if (isError || !cart) {
    return (
      <div className={styles.card} role="alert" style={{ marginTop: 20 }}>
        <p>{t('cart.error')}</p>
        <Button variant="neutral" size="sm" onClick={() => void refetch()}>
          {t('cart.retry')}
        </Button>
      </div>
    );
  }
  const lines = cart.packages.flatMap((p) => p.lines);
  const subtotal = t('cart.subtotal', { count: cart.selectedCount });
  return (
    <div className={styles.layout}>
      <div className={styles.col}>
        <div className={styles.card}>
          <div className={styles.head}>
            <div className={styles.headLeft}>
              <h1 className={styles.title}>{t('cart.title')}</h1>
              {lines.length > 0 && (
                <button type="button" className="link-button" style={{ fontSize: 'var(--at-text-sm)' }} onClick={() => m.mutate({ kind: 'selectAll', selected: !cart.allSelected })}>
                  {cart.allSelected ? t('cart.deselectAll') : t('cart.selectAll')}
                </button>
              )}
            </div>
            <span className={styles.priceHead}>{t('cart.price')}</span>
          </div>
          {lines.length === 0 ? (
            <div className={styles.empty}>
              <h2 className={styles.emptyTitle}>{t('cart.empty')}</h2>
              <p className={styles.muted}>{t('cart.emptyHint')}</p>
              <Link href="/s" className={buttonClassName({ size: 'sm' })}>
                {t('cart.continue')}
              </Link>
            </div>
          ) : (
            <>
              {cart.packages.map((p, i) => (
                <Package key={p.packageId} pkg={p} n={i + 1} total={cart.packages.length} />
              ))}
              <p className={styles.subtotalRow}>
                {subtotal} <strong>{fmt.money(cart.subtotalMinor, cart.currency)}</strong>
              </p>
            </>
          )}
        </div>
        {cart.saved.length > 0 && (
          <section className={styles.card} aria-labelledby="saved-title">
            <h2 id="saved-title" className={styles.h2}>
              {t('cart.saved', { count: cart.saved.length })}
            </h2>
            <ul className={styles.savedGrid}>
              {cart.saved.map((s) => (
                <li key={s.savedId} className={styles.savedItem} data-testid="saved-item">
                  <ImagePlaceholder src={s.imageUrl} alt="" />
                  <Link href={productHref(s.atin)} className={styles.savedName}>
                    {s.title}
                  </Link>
                  <strong>{fmt.money(s.priceMinor)}</strong>
                  <Button variant="neutral" size="sm" disabled={!s.available} onClick={() => m.mutate({ kind: 'moveToCart', savedId: s.savedId, title: s.title })}>
                    {s.available ? t('cart.moveToCart') : t('card.unavailable')}
                  </Button>
                  <button type="button" className="link-button" style={{ fontSize: 'var(--at-text-xs)' }} onClick={() => m.mutate({ kind: 'removeSaved', savedId: s.savedId })}>
                    {t('cart.delete')}
                  </button>
                </li>
              ))}
            </ul>
          </section>
        )}
      </div>
      <aside className={styles.aside} aria-label={t('co.orderSummary')}>
        <div className={styles.summary}>
          <span style={{ fontSize: 'var(--at-text-lg)' }}>
            {subtotal} <strong data-testid="cart-subtotal">{fmt.money(cart.subtotalMinor, cart.currency)}</strong>
          </span>
          <label className={styles.gift}>
            <input type="checkbox" checked={gift} onChange={(e) => setGift(e.target.checked)} />
            {t('cart.gift')}
          </label>
          {cart.selectedCount > 0 ? (
            <Link href="/checkout" className={buttonClassName({ block: true })}>
              {t('cart.checkout')}
            </Link>
          ) : (
            <Button block disabled>
              {t('cart.checkout')}
            </Button>
          )}
        </div>
        <Recommendations exclude={new Set(lines.map((l) => l.atin))} />
      </aside>
    </div>
  );
}
