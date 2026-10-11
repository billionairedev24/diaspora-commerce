'use client';

import { type ComponentType, type ReactNode } from 'react';

import { Button } from '../button/Button';
import { cx } from '../cx';
import { Badge, FulfilmentBadge } from './Badge';
import { ImagePlaceholder } from './ImagePlaceholder';
import { Price } from './Price';
import styles from './ProductCard.module.css';
import { QuantityStepper } from './QuantityStepper';
import { Stars } from './Stars';

/** Structural product-card data (matches ProductSummary from @afrotransact/api-client). */
export interface ProductCardData {
  atin: string;
  title: string;
  community: string;
  unit: string;
  rating: number;
  reviewCount: number;
  imageUrl: string | null;
  priceMinor: number;
  listPriceMinor: number | null;
  sellerName: string;
  sellerKind: 'PLATFORM' | 'SELLER';
  sellerCity: string;
  fulfilment: 'AFROTRANSACT' | 'SELLER' | 'AFROTRANSACT_DELIVERY';
  availableQty: number;
  inStock: boolean;
  lowStock: boolean;
  delivery: { prefix: string; label: string };
  offerCount: number;
  minOfferPriceMinor: number;
  /** Sponsored products carry the advertiser and, for search ads, why the ad was shown. */
  sponsor: { advertiser: string; reason?: { summary: string } } | null;
}

export interface LinkLikeProps {
  href: string;
  className?: string;
  children: ReactNode;
  'aria-label'?: string;
  tabIndex?: number;
}

export interface ProductCardLabels {
  addToCart: string;
  inCart: (n: number) => string;
  unavailable: string;
  sponsored: (advertiser: string) => string;
  whyAd?: string;
  brand: string;
  onlyLeft: (n: number) => string;
  offers: (count: number, from: string) => string;
  soldBy: (seller: string) => string;
  reviews: (count: string) => string;
  /** Accessible name of the rating stars, e.g. "4.5 out of 5 stars". */
  rating?: (value: string) => string;
}

export const defaultProductCardLabels: ProductCardLabels = {
  addToCart: 'Add to cart',
  inCart: (n) => `${n} in cart`,
  unavailable: 'Currently unavailable',
  sponsored: (a) => `Sponsored · ${a}`,
  whyAd: 'Why this ad?',
  brand: 'AfroTransact Brand',
  onlyLeft: (n) => `Only ${n} left — order soon`,
  offers: (c, from) => `${c} offers from ${from}`,
  soldBy: (s) => `Sold by ${s}`,
  reviews: (c) => c
};

/** Pre-order / delivery availability for the shopper's place (storefront pre-orders, S273). */
export interface ProductCardAvailability {
  /** Badge on the image, e.g. "Pre-order" (omit when the item is not a pre-order or the badge placement is off). */
  badge?: string;
  /** Line under the price that replaces the delivery promise: "Pre-order · delivered Oct 25", "Ships now · UPS Ground 3–5 days". */
  tag: string;
  /** gold while a pre-order is open, muted otherwise. */
  tone?: 'gold' | 'muted';
  /** When set, adding is blocked and the add button shows this label ("Opens Oct 15", "Closed", "Unavailable"). */
  lockedLabel?: string | null;
}

export interface ProductCardProps {
  product: ProductCardData;
  /** Pre-order badge, tag line and add-to-cart lock. */
  availability?: ProductCardAvailability;
  href: string;
  variant?: 'grid' | 'rail';
  /** Units of the buy-box offer already in the cart (turns "Add to cart" into a stepper). */
  quantityInCart?: number;
  onAdd?: () => void;
  onQuantityChange?: (qty: number) => void;
  onRemove?: () => void;
  busy?: boolean;
  /** Link component (e.g. Next.js <Link>); defaults to <a>. */
  linkAs?: ComponentType<LinkLikeProps>;
  labels?: Partial<ProductCardLabels>;
  /** BCP 47 tag of the active language for prices and counts (default en-US). */
  locale?: string;
  className?: string;
}

function DefaultLink({ href, className, children, ...rest }: LinkLikeProps) {
  return (
    <a href={href} className={className} {...rest}>
      {children}
    </a>
  );
}

const moneyIn = (locale: string) => (m: number) => new Intl.NumberFormat(locale, { style: 'currency', currency: 'USD' }).format(m / 100);

/** Product card for search results (grid) and home rows (rail), with the fulfilment badge and cart controls. */
export function ProductCard({ product: p, href, variant = 'grid', availability: av, quantityInCart = 0, onAdd, onQuantityChange, onRemove, busy, linkAs: Link = DefaultLink, labels, locale = 'en-US', className }: ProductCardProps) {
  const money = moneyIn(locale);
  const L = { ...defaultProductCardLabels, ...labels };
  const isPlatform = p.sellerKind === 'PLATFORM';
  const delivery = av ? (
    <span className={cx(styles.availability, av.tone === 'gold' ? styles.availabilityGold : styles.availabilityMuted)} data-testid="card-availability">
      {av.tag}
    </span>
  ) : (
    <span className={styles.eta}>
      {p.delivery.prefix} <strong>{p.delivery.label}</strong>
    </span>
  );
  const badge = av?.badge ? (
    <span className={styles.preBadge} data-testid="preorder-badge">
      {av.badge}
    </span>
  ) : null;
  const locked = !!av?.lockedLabel;
  // The image link's name must contain its visible text (WCAG 2.5.3), so it includes the pre-order badge.
  const mediaLabel = av?.badge ? `${p.title}, ${av.badge}` : p.title;
  if (variant === 'rail') {
    return (
      <article className={cx(styles.rail, className)}>
        <Link href={href} className={cx(styles.imageLink, styles.withBadge)} tabIndex={-1} aria-label={mediaLabel}>
          <ImagePlaceholder src={p.imageUrl} alt={p.title} />
          {badge}
        </Link>
        <Link href={href} className={styles.title}>
          {p.title}
        </Link>
        <Stars rating={p.rating} reviewCount={p.reviewCount} size={12} reviewsLabel={L.reviews} ratingLabel={L.rating} locale={locale} />
        <Price amountMinor={p.priceMinor} size="md" locale={locale} />
        {p.fulfilment === 'AFROTRANSACT' && <FulfilmentBadge fulfilment="AFROTRANSACT" />}
        {delivery}
      </article>
    );
  }
  return (
    <article className={cx(styles.card, className)} aria-label={p.title}>
      <Link href={href} className={cx(styles.media, styles.withBadge)} tabIndex={-1} aria-label={mediaLabel}>
        <ImagePlaceholder src={p.imageUrl} alt={p.title} />
        {badge}
      </Link>
      <div className={styles.body}>
        {p.sponsor && <span className={styles.sponsored}>{L.sponsored(p.sponsor.advertiser)}</span>}
        {p.sponsor?.reason?.summary && (
          <details className={styles.adWhy}>
            <summary>{L.whyAd}</summary>
            {p.sponsor.reason.summary}
          </details>
        )}
        {isPlatform && <Badge tone="brand">{L.brand}</Badge>}
        <h3 style={{ margin: 0, font: 'inherit' }}>
          <Link href={href} className={styles.title}>
            {p.title}
          </Link>
        </h3>
        <span className={styles.meta}>
          {p.community} · {p.unit}
        </span>
        <Stars rating={p.rating} reviewCount={p.reviewCount} reviewsLabel={L.reviews} ratingLabel={L.rating} locale={locale} />
        <Price amountMinor={p.priceMinor} listPriceMinor={p.listPriceMinor} size="lg" locale={locale} />
        <FulfilmentBadge fulfilment={p.fulfilment} shipsFrom={p.sellerCity} />
        {p.inStock && delivery}
        {p.lowStock && <span className={styles.low}>{L.onlyLeft(p.availableQty)}</span>}
        <span className={styles.meta}>{p.offerCount > 1 ? L.offers(p.offerCount, money(p.minOfferPriceMinor)) : L.soldBy(p.sellerName)}</span>
        <div className={styles.actions}>
          {!p.inStock ? (
            <div className={styles.unavailable}>{L.unavailable}</div>
          ) : quantityInCart > 0 ? (
            <QuantityStepper
              block
              value={quantityInCart}
              max={locked ? quantityInCart : Math.min(p.availableQty, 99)}
              onChange={(q) => onQuantityChange?.(q)}
              onRemove={onRemove}
              busy={busy}
              format={L.inCart}
              itemLabel={p.title}
            />
          ) : locked ? (
            <Button size="sm" block disabled aria-label={`${av?.lockedLabel}: ${p.title}`}>
              {av?.lockedLabel}
            </Button>
          ) : (
            <Button size="sm" block onClick={onAdd} loading={busy} aria-label={`${L.addToCart}: ${p.title}`}>
              {L.addToCart}
            </Button>
          )}
        </div>
      </div>
    </article>
  );
}
