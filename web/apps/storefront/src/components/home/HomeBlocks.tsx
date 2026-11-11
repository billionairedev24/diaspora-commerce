import { type HomeBlock } from '@afrotransact/api-client';
import { type ReactNode } from 'react';

import { PreorderHome } from '@/components/preorder/PreorderHome';

import { Deals, Occasions, PromoBand, Spotlight } from './blocks/OrganicBlocks';
import { AdRow, Advertorial, BrandBanner, DisplayBanner, SponsoredProducts } from './blocks/PaidBlocks';
import { RailSection } from './HomeSections';
import { RecentlyViewed } from './RecentlyViewed';

/**
 * Renders the composed home body by block type, in the order the gateway sent. A block type this build does not know
 * is ignored (a newer gateway may send more), and a block with nothing to show renders nothing.
 */
export async function HomeBlocks({ blocks }: { blocks: HomeBlock[] }) {
  const out: ReactNode[] = [];
  for (const b of blocks) {
    const node = await renderBlock(b);
    if (node) out.push(<div key={b.id} data-block={b.type}>{node}</div>);
  }
  return <>{out}</>;
}

async function renderBlock(b: HomeBlock): Promise<ReactNode> {
  switch (b.type) {
    case 'rail':
    case 'based-on-orders':
      return b.rail && b.rail.items.length > 0 ? <RailSection rail={b.rail} /> : null;
    case 'banner':
      return <DisplayBanner block={b} />;
    case 'brand-banner':
      return <BrandBanner block={b} />;
    case 'sponsored-products':
      return <SponsoredProducts block={b} />;
    case 'ad-row':
      return <AdRow block={b} />;
    case 'advertorial':
      return <Advertorial block={b} />;
    case 'deals':
      return <Deals block={b} />;
    case 'community-spotlight':
    case 'seller-spotlight':
      return <Spotlight block={b} />;
    case 'occasions':
      return <Occasions block={b} />;
    case 'membership-promo':
    case 'signin-promo':
    case 'services-near-you':
      return <PromoBand block={b} />;
    case 'preorders':
      return <PreorderHome />;
    case 'recently-viewed':
      return <RecentlyViewed />;
    default:
      return null;
  }
}
