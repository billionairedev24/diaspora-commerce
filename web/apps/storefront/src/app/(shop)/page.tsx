import { Hero } from '@/components/home/Hero';
import { Banners, Rails, Tiles } from '@/components/home/HomeSections';
import { PreorderHome } from '@/components/preorder/PreorderHome';
import { siteQueries } from '@/lib/api/queries';
import { serverApi } from '@/lib/api/server';
import { getQueryClient } from '@/lib/query/getQueryClient';

/** Home (Shop design order): the pre-order hero only where the shopper's city has a live pre-order, then the campaign
    carousel, tiles, sponsored banners and product rails from content placements. */
export default async function HomePage() {
  const api = await serverApi();
  const home = await getQueryClient().fetchQuery(siteQueries.home(api));
  return (
    <>
      <PreorderHome />
      <Hero slides={home.hero} />
      <Tiles tiles={home.tiles} />
      <Banners banners={home.banners} />
      <Rails rails={home.rails} />
    </>
  );
}
