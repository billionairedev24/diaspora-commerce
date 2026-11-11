import { Suspense } from 'react';

import { Hero } from '@/components/home/Hero';
import { HomeBlocks } from '@/components/home/HomeBlocks';
import { Tiles } from '@/components/home/HomeSections';
import { HomeSkeleton } from '@/components/home/HomeSkeleton';
import { PreorderHome } from '@/components/preorder/PreorderHome';
import { RunAdvertorials } from '@/components/runs/RunAdvertorials';
import { siteQueries } from '@/lib/api/queries';
import { serverApi } from '@/lib/api/server';
import { getQueryClient } from '@/lib/query/getQueryClient';

/** Home: the campaign carousel, tiles (fixed images or rules the gateway resolves to real products), then the body the
    gateway composed in the order staff set it: product carousels, sponsored modules (always labelled and counted), deals,
    spotlights, bands, and the personal rails ("Based on your orders", "Recently viewed"). The pre-order hero opens the
    page unless staff placed a `preorders` block in the body. Every product shown is in stock and has a real photo. */
export default async function HomePage() {
  const api = await serverApi();
  const home = await getQueryClient().fetchQuery(siteQueries.home(api));
  const preorderPlaced = home.blocks.some((b) => b.type === 'preorders');
  return (
    <>
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
