import { Hero } from '@/components/home/Hero';
import { Banners, Rails, Tiles } from '@/components/home/HomeSections';
import { PreorderHome } from '@/components/preorder/PreorderHome';
import { siteQueries } from '@/lib/api/queries';
import { serverApi } from '@/lib/api/server';
import { getQueryClient } from '@/lib/query/getQueryClient';

/** Home: the shopper's pre-order hero (or fallback card), then hero, tiles, banners and rails from content placements. */
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
