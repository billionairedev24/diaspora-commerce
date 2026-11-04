import { Hero } from '@/components/home/Hero';
import { Banners, Rails, Tiles } from '@/components/home/HomeSections';
import { RecentlyViewed } from '@/components/home/RecentlyViewed';
import { PreorderHome } from '@/components/preorder/PreorderHome';
import { RunAdvertorials } from '@/components/runs/RunAdvertorials';
import { siteQueries } from '@/lib/api/queries';
import { serverApi } from '@/lib/api/server';
import { getQueryClient } from '@/lib/query/getQueryClient';

/** Home (Shop design order): the pre-order hero only where the shopper's city has a live pre-order, then the campaign
    carousel, tiles (fixed images or rules the gateway resolves to real products), sponsored banners and product rails
    from content placements, a personalised "Based on your orders" rail for signed-in shoppers with orders, and
    "Recently viewed" from this browser. Every product shown is in stock and has a real photo. */
export default async function HomePage() {
  const api = await serverApi();
  const home = await getQueryClient().fetchQuery(siteQueries.home(api));
  return (
    <>
      <PreorderHome />
      <RunAdvertorials />
      <Hero slides={home.hero} />
      <Tiles tiles={home.tiles} />
      <Banners banners={home.banners} />
      <Rails rails={home.rails} />
      <RecentlyViewed />
    </>
  );
}
