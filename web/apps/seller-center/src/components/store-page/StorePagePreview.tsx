'use client';

import { type ProductSummary, type StoreConfig,type Storefront } from '@afrotransact/api-client';
import { resolveTheme, type ThemePreset } from '@afrotransact/tokens';
import { type StorefrontCommerce, type StorefrontQueryState,StorefrontView } from '@afrotransact/ui';
import { useCallback, useMemo } from 'react';

import { filterPreviewProducts } from '@/lib/store-page';

const NO_OP = () => undefined;

/**
 * The live preview: the real StorefrontView (the same component the shopper gets at /b/{slug}), fed with the draft being
 * edited and the seller's own sellable products. Nothing here is a mock of the page. Buttons are inert.
 */
export function StorePagePreview({ preview, config, brandColour, width }: { preview: Storefront; config: StoreConfig; brandColour: string | null; width: 'desktop' | 'mobile' }) {
  const theme = useMemo(() => resolveTheme({ preset: config.theme.preset as ThemePreset, accent: config.theme.accent, brandColour }), [config.theme, brandColour]);
  const all = preview.products.hits;
  const byAtin = useMemo(() => new Map<string, ProductSummary>([...preview.featured, ...all].map((p) => [p.atin, p])), [preview.featured, all]);
  const model = useMemo(
    () => ({ ...preview, config, featured: config.featured.map((a) => byAtin.get(a)).filter((p): p is ProductSummary => !!p) }),
    [preview, config, byAtin]
  );
  const commerce: StorefrontCommerce = useMemo(
    () => ({ homeHref: '#', cartHref: '#', accountHref: '#', cartCount: 0, productHref: () => '#', quantityInCart: () => 0, onAdd: NO_OP, onBuyNow: NO_OP, busy: false }),
    []
  );
  const loadProducts = useCallback(
    async (q: StorefrontQueryState) => {
      const hits = filterPreviewProducts(all, q);
      return { hits, total: hits.length, page: 1, pageCount: 1, categories: preview.products.categories, prices: preview.products.prices };
    },
    [all, preview.products.categories, preview.products.prices]
  );
  return (
    <div
      className="mx-auto overflow-hidden rounded-md border border-border-strong bg-canvas"
      style={{ width: width === 'mobile' ? 390 : '100%', maxWidth: '100%' }}
      data-testid="store-preview-frame"
      data-width={width}
    >
      {/* A scrolling area must be reachable by keyboard (axe: scrollable-region-focusable). */}
      {/* eslint-disable-next-line jsx-a11y/no-noninteractive-tabindex */}
      <div className="max-h-[78vh] overflow-y-auto" tabIndex={0} role="region" aria-label="Store page preview">
        <StorefrontView model={model} theme={theme} commerce={commerce} loadProducts={loadProducts} preview />
      </div>
    </div>
  );
}
