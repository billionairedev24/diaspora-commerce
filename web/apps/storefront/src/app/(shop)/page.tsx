import { type Metadata } from 'next';
import { Suspense } from 'react';

import { Hero } from '@/components/home/Hero';
import { HomeBlocks } from '@/components/home/HomeBlocks';
import { Tiles } from '@/components/home/HomeSections';
import { HomeSkeleton } from '@/components/home/HomeSkeleton';
import { PreorderHome } from '@/components/preorder/PreorderHome';
import { RunAdvertorials } from '@/components/runs/RunAdvertorials';
import { JsonLd } from '@/components/seo/JsonLd';
import { siteQueries } from '@/lib/api/queries';
import { serverApi } from '@/lib/api/server';
import { getQueryClient } from '@/lib/query/getQueryClient';
import { homeJsonLd } from '@/lib/seo/pages';
import { getIndexingPolicy } from '@/lib/seo/server';

/** One canonical for the home page, whatever tracking parameters the visit carried. */
export function generateMetadata(): Metadata {
  return { alternates: { canonical: '/' } };
}

/** Home: the campaign carousel, tiles (fixed images or rules the gateway resolves to real products), then the body the
    gateway composed in the order staff set it: product carousels, sponsored modules (always labelled and counted), deals,
    spotlights, bands, and the personal rails ("Based on your orders", "Recently viewed"). The pre-order hero opens the
    page unless staff placed a `preorders` block in the body. Every product shown is in stock and has a real photo. */
export default async function HomePage() {
  const api = await serverApi();
  const home = await getQueryClient().fetchQuery(siteQueries.home(api));
  const policy = await getIndexingPolicy();
  const preorderPlaced = home.blocks.some((b) => b.type === 'preorders');
  return (
    <>
      <JsonLd data={homeJsonLd(policy.origin)} />
      {preorderPlaced ? null : <PreorderHome />}
      <RunAdvertorials />
      <Hero slides={home.hero} />
      <Tiles tiles={home.tiles} />
      <Suspense fallback={<HomeSkeleton />}>
        <HomeBlocks blocks={home.blocks} />
      </Suspense>
    </>
  );
}
