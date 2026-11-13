import { type Metadata } from 'next';

import { Hero } from '@/components/home/Hero';
import { HomeBlocks } from '@/components/home/HomeBlocks';
import { Tiles } from '@/components/home/HomeSections';
import { HomeVisitMemory } from '@/components/home/HomeVisitMemory';
import { PreorderHome } from '@/components/preorder/PreorderHome';
import { RunAdvertorials } from '@/components/runs/RunAdvertorials';
import { JsonLd } from '@/components/seo/JsonLd';
import { siteQueries } from '@/lib/api/queries';
import { serverApi } from '@/lib/api/server';
import { isRealPhoto } from '@/lib/home/photo';
import { getQueryClient } from '@/lib/query/getQueryClient';
import { homeJsonLd } from '@/lib/seo/pages';
import { getIndexingPolicy } from '@/lib/seo/server';

/** One canonical for the home page, whatever tracking parameters the visit carried. */
export function generateMetadata(): Metadata {
  return { alternates: { canonical: '/' } };
}

/** Home. The gateway decides which modules the page has and in what order (a dynamic page: version 2); the storefront
    draws each kind from the module registry. Version 1 (an older gateway) keeps the classic composition: campaign
    carousel, tiles, then the body in the order staff set it. Every product shown is in stock and has a real photo, every
    module with nothing to show is absent, and the HTML is composed per visit (never cached): the visit's cookies give one
    visit one stable page and the next visit a different one, while crawlers get the deterministic default. */
export default async function HomePage() {
  const api = await serverApi();
  const home = await getQueryClient().fetchQuery(siteQueries.home(api));
  const policy = await getIndexingPolicy();
  const dynamic = home.version === 2;
  const preorderPlaced = home.blocks.some((b) => b.type === 'preorders');
  const heroPhoto = home.hero.some((s) => isRealPhoto(s.imageUrl));
  return (
    <>
      <JsonLd data={homeJsonLd(policy.origin)} />
      {dynamic || preorderPlaced ? null : <PreorderHome />}
      <RunAdvertorials />
      <Hero slides={home.hero} flat={dynamic} />
      {dynamic ? null : <Tiles tiles={home.tiles} />}
      {/* No Suspense fallback: everything is already fetched, and a skeleton that is swapped for the real modules is a layout shift. */}
      <HomeBlocks blocks={home.blocks} lcpTiles={dynamic && !heroPhoto} />
      {home.visit ? <HomeVisitMemory window={home.visit.window} seen={home.visit.seen} deterministic={home.visit.deterministic ?? false} /> : null}
    </>
  );
}
