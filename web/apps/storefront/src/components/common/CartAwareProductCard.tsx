'use client';

import { type ProductSummary } from '@afrotransact/api-client';
import { ProductCard } from '@afrotransact/ui';

import { t } from '@/i18n/t';
import { lineForOffer, useCart, useCartMutation } from '@/lib/cart/useCart';
import { productHref } from '@/lib/links';

import { ProductLink } from './ProductLink';

export const cardLabels = {
  addToCart: t('card.addToCart'),
  inCart: (n: number) => t('card.inCart', { n }),
  unavailable: t('card.unavailable'),
  sponsored: (advertiser: string) => t('card.sponsored', { advertiser }),
  whyAd: t('card.whyAd'),
  brand: t('card.brand'),
  onlyLeft: (n: number) => t('card.onlyLeft', { n }),
  offers: (count: number, from: string) => t('card.offers', { count, from }),
  soldBy: (seller: string) => t('card.soldBy', { seller })
};

/** Product card wired to the cart: "Add to cart" becomes a −/+ stepper capped by stock. */
export function CartAwareProductCard({ product, variant = 'grid' }: { product: ProductSummary; variant?: 'grid' | 'rail' }) {
  const { data: cart } = useCart();
  const m = useCartMutation();
  const line = lineForOffer(cart, product.offerId);
  return (
    <ProductCard
      product={product}
      href={productHref(product.atin)}
      variant={variant}
      linkAs={ProductLink}
      labels={cardLabels}
      quantityInCart={line?.quantity ?? 0}
      busy={m.isPending}
      onAdd={() => m.mutate({ kind: 'add', offerId: product.offerId, quantity: 1, title: product.title })}
      onQuantityChange={(q) => line && m.mutate({ kind: 'quantity', lineId: line.lineId, quantity: q, title: product.title })}
      onRemove={() => line && m.mutate({ kind: 'remove', lineId: line.lineId, title: product.title })}
    />
  );
}
