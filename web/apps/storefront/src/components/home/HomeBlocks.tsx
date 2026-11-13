import { type HomeBlock } from '@afrotransact/api-client';
import { type ReactNode } from 'react';

import { PreorderHome } from '@/components/preorder/PreorderHome';
import { getLocale, getT } from '@/i18n/server';
import { localizeBlock } from '@/lib/home/copy';

import { Deals, Occasions, PromoBand, Spotlight } from './blocks/OrganicBlocks';
import { AdRow, Advertorial, BrandBanner, DisplayBanner, SponsoredProducts } from './blocks/PaidBlocks';
import { RailSection } from './HomeSections';
import { BrandHero, CellTiles, SellBand, ServiceCards } from './modules/DynamicModules';
import { ModuleFrame } from './modules/ModuleFrame';
import { RecentlyViewed } from './RecentlyViewed';

/** What a module renderer is handed: the block with its copy already resolved in the shopper's language. */
interface ModuleContext {
  block: HomeBlock;
  /** This module may hold the page's largest paint (no campaign photo above it): its first photo is fetched first. */
  lcp: boolean;
}
type ModuleRenderer = (ctx: ModuleContext) => ReactNode | Promise<ReactNode>;

/**
 * The module registry: block type to renderer. The gateway decides WHICH modules the page has and in which order
 * (docs/engineering/dynamic-home.md); the storefront only knows how to draw each kind. A type this build does not know is
 * ignored (a newer gateway may send more), and a module with nothing to show renders nothing: no heading, no empty state.
 */
export const MODULE_REGISTRY: Record<string, ModuleRenderer> = {
  /* Generated and personal */
  'brand-hero': ({ block }) => <BrandHero block={block} />,
  'department-tiles': ({ block, lcp }) => <CellTiles block={block} lcp={lcp} />,
  'community-tiles': ({ block }) => <CellTiles block={block} />,
  'service-cards': ({ block }) => <ServiceCards block={block} />,
  'sell-band': ({ block }) => <SellBand block={block} />,
  rail: ({ block }) => (block.rail && block.rail.items.length > 0 ? <RailSection rail={block.rail} /> : null),
  'based-on-orders': ({ block }) => (block.rail && block.rail.items.length > 0 ? <RailSection rail={block.rail} /> : null),
  deals: ({ block }) => <Deals block={block} />,
  'recently-viewed': () => <RecentlyViewed />,
  preorders: () => <PreorderHome />,
  'membership-promo': ({ block }) => <PromoBand block={block} />,
  'signin-promo': ({ block }) => <PromoBand block={block} />,
  'services-near-you': ({ block }) => <PromoBand block={block} />,
  'community-spotlight': ({ block }) => <Spotlight block={block} />,
  'seller-spotlight': ({ block }) => <Spotlight block={block} />,
  occasions: ({ block }) => <Occasions block={block} />,
  /* Paid: always labelled and counted by placement */
  banner: ({ block }) => <DisplayBanner block={block} />,
  'brand-banner': ({ block }) => <BrandBanner block={block} />,
  'sponsored-products': ({ block }) => <SponsoredProducts block={block} />,
  'ad-row': ({ block }) => <AdRow block={block} />,
  advertorial: ({ block }) => <Advertorial block={block} />
};

/**
 * Renders the composed home body by block type, in the order the gateway sent (DOM order is visual order: nothing is
 * reordered with CSS). Each organic module is wrapped for impression and click counting (Analytics consent only).
 */
export async function HomeBlocks({ blocks, lcpTiles = false }: { blocks: HomeBlock[]; lcpTiles?: boolean }) {
  const [t, locale] = await Promise.all([getT(), getLocale()]);
  const out: ReactNode[] = [];
  let lcpAvailable = lcpTiles;
  for (const raw of blocks) {
    const render = MODULE_REGISTRY[raw.type];
    if (!render) continue;
    const block = localizeBlock(raw, t, locale);
    const lcp = lcpAvailable && raw.type === 'department-tiles';
    const node = await render({ block, lcp });
    if (!node) continue;
    if (lcp) lcpAvailable = false;
    out.push(
      <ModuleFrame key={raw.id} type={raw.type} tracking={raw.sponsored ? undefined : raw.tracking}>
        {node}
      </ModuleFrame>
    );
  }
  return <>{out}</>;
}
