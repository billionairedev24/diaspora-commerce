'use client';

import { useEffect, useRef, useState } from 'react';

import { Price } from '../commerce/Price';
import { Stars } from '../commerce/Stars';
import { cx } from '../cx';
import styles from './Storefront.module.css';
import { type StorefrontCommerce, type StorefrontLabels, type StorefrontProductModel } from './types';

/** Price, deal, rating and stock lines: only what the data says (no rating row without reviews, no invented badges). */
export function ProductMeta({ product, labels, locale, large }: { product: StorefrontProductModel; labels: StorefrontLabels; locale: string; large?: boolean }) {
  const hasDeal = product.listPriceMinor !== null && product.listPriceMinor > product.priceMinor;
  const pct = hasDeal && product.listPriceMinor ? Math.round((1 - product.priceMinor / product.listPriceMinor) * 100) : 0;
  return (
    <div className={styles.meta}>
      {product.reviewCount > 0 && product.rating > 0 ? (
        <Stars rating={product.rating} reviewCount={product.reviewCount} size={large ? 16 : 13} locale={locale} ratingLabel={labels.rating} reviewsLabel={labels.reviewsCount} />
      ) : null}
      <div className={styles.priceRow}>
        <Price amountMinor={product.priceMinor} currency={product.currency} locale={locale} size={large ? 'xl' : 'lg'} />
        {hasDeal && product.listPriceMinor ? (
          <>
            <s className={styles.was}>{new Intl.NumberFormat(locale, { style: 'currency', currency: product.currency }).format(product.listPriceMinor / 100)}</s>
            <span className={styles.deal}>{labels.off(pct)}</span>
          </>
        ) : null}
      </div>
      {product.lowStock && product.availableQty > 0 ? <span className={styles.low}>{labels.onlyLeft(product.availableQty)}</span> : null}
    </div>
  );
}

export interface StoreProductTileProps {
  product: StorefrontProductModel;
  labels: StorefrontLabels;
  locale: string;
  commerce: StorefrontCommerce;
  /** Preview: the buttons are inert. */
  inert?: boolean;
  eager?: boolean;
  onQuickView: () => void;
}

/** One product in a storefront grid: a photograph, name, price, Add to cart, Buy now and Quick view. Flat: no card box. */
export function StoreProductTile({ product, labels, locale, commerce, inert, eager, onQuickView }: StoreProductTileProps) {
  const [loaded, setLoaded] = useState(false);
  const [broken, setBroken] = useState(false);
  const img = useRef<HTMLImageElement>(null);
  // An image that finished before hydration never fires onLoad again.
  useEffect(() => {
    if (img.current?.complete && img.current.naturalWidth > 0) setLoaded(true);
  }, []);
  const Link = commerce.linkAs;
  const href = commerce.productHref(product.atin);
  const inCart = commerce.quantityInCart(product.offerId);
  const disabled = inert || commerce.busy;
  const media = (
    <span className={cx(styles.tileMedia, loaded && styles.tileLoaded)}>
      {product.imageUrl && !broken ? (
        <img
          ref={img}
          src={product.imageUrl}
          alt=""
          className={styles.tileImg}
          loading={eager ? 'eager' : 'lazy'}
          decoding="async"
          onLoad={() => setLoaded(true)}
          onError={() => setBroken(true)}
          width={600}
          height={750}
        />
      ) : null}
    </span>
  );
  const title = <span className={styles.tileTitle}>{product.title}</span>;
  return (
    <article className={styles.tile} data-testid="store-product">
      <div className={styles.tileMediaWrap}>
        {Link ? (
          <Link href={href} className={styles.tileLink} aria-label={product.title} tabIndex={-1}>
            {media}
          </Link>
        ) : (
          <a href={href} className={styles.tileLink} aria-label={product.title} tabIndex={-1}>
            {media}
          </a>
        )}
        <button type="button" className={styles.quickBtn} onClick={onQuickView} aria-label={`${labels.quickView}: ${product.title}`}>
          {labels.quickView}
        </button>
      </div>
      <div className={styles.tileBody}>
        {Link ? (
          <Link href={href} className={styles.tileName}>
            {title}
          </Link>
        ) : (
          <a href={href} className={styles.tileName}>
            {title}
          </a>
        )}
        <ProductMeta product={product} labels={labels} locale={locale} />
        <div className={styles.tileActions}>
          <button type="button" className={styles.primary} disabled={disabled} onClick={() => commerce.onAdd(product)} aria-label={`${inCart > 0 ? labels.inCart(inCart) : labels.addToCart}: ${product.title}`}>
            {inCart > 0 ? labels.inCart(inCart) : labels.addToCart}
          </button>
          <button type="button" className={styles.secondary} disabled={disabled} onClick={() => commerce.onBuyNow(product)} aria-label={`${labels.buyNow}: ${product.title}`}>
            {labels.buyNow}
          </button>
        </div>
      </div>
    </article>
  );
}
