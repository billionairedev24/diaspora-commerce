'use client';

import { type Storefront } from '@afrotransact/api-client';
import { useFormatters } from '@afrotransact/i18n/react';
import { resolveTheme, type ThemePreset } from '@afrotransact/tokens';
import { EMPTY_STOREFRONT_QUERY, type StorefrontCommerce, type StorefrontQueryState,StorefrontView } from '@afrotransact/ui';
import { usePathname, useRouter } from 'next/navigation';
import { useCallback, useMemo } from 'react';

import { ProductLink } from '@/components/common/ProductLink';
import { browserApi } from '@/lib/api/browser';
import { lineForOffer, useCart, useCartMutation } from '@/lib/cart/useCart';
import { productHref } from '@/lib/links';

import { useStorefrontLabels } from './useStorefrontLabels';

const PAGE_SIZE = 24;

/**
 * The seller's storefront in the shopper's browser: the shared StorefrontView wired to the marketplace cart. "Add to cart"
 * and "Buy now" go to the same cart and checkout as everywhere else (/cart, /checkout): the store has its own look, not its own checkout.
 */
export function StorefrontClient({ store, initialQuery = EMPTY_STOREFRONT_QUERY }: { store: Storefront; initialQuery?: StorefrontQueryState }) {
  const labels = useStorefrontLabels();
  const fmt = useFormatters();
  const router = useRouter();
  const pathname = usePathname();
  const { data: cart } = useCart();
  const mutation = useCartMutation();
  const theme = useMemo(() => resolveTheme({ preset: store.config.theme.preset as ThemePreset, accent: store.config.theme.accent }), [store.config.theme]);

  const commerce: StorefrontCommerce = useMemo(
    () => ({
      homeHref: '/',
      cartHref: '/cart',
      accountHref: '/account',
      cartCount: cart?.itemCount ?? 0,
      productHref,
      linkAs: ProductLink,
      quantityInCart: (offerId) => lineForOffer(cart, offerId)?.quantity ?? 0,
      onAdd: (p) => mutation.mutate({ kind: 'add', offerId: p.offerId, quantity: 1, title: p.title }),
      onBuyNow: (p) =>
        mutation.mutate(
          { kind: 'add', offerId: p.offerId, quantity: 1, title: p.title },
          { onSuccess: () => router.push('/checkout') }
        ),
      busy: mutation.isPending
    }),
    [cart, mutation, router]
  );

  const loadProducts = useCallback(
    (q: StorefrontQueryState, page: number) =>
      browserApi().getStorefrontProducts(store.slug, { q: q.q, category: q.category, price: q.price, sort: q.sort, page, size: PAGE_SIZE }),
    [store.slug]
  );

  // Keep the search, category, price and sort in the address so a filtered view can be shared and reloaded.
  const onQueryChange = useCallback(
    (q: StorefrontQueryState) => {
      const sp = new URLSearchParams();
      if (q.q) sp.set('q', q.q);
      if (q.category) sp.set('category', q.category);
      if (q.price) sp.set('price', q.price);
      if (q.sort !== 'relevance') sp.set('sort', q.sort);
      const qs = sp.toString();
      window.history.replaceState(null, '', qs ? `${pathname}?${qs}` : pathname);
    },
    [pathname]
  );

  return (
    <StorefrontView
      model={store}
      theme={theme}
      commerce={commerce}
      labels={labels}
      locale={fmt.tag}
      loadProducts={loadProducts}
      initialQuery={initialQuery}
      onQueryChange={onQueryChange}
    />
  );
}
