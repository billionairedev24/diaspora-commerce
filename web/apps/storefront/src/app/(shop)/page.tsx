import { Hero } from '@/components/home/Hero';
import { Banners, Rails, Tiles } from '@/components/home/HomeSections';
import { siteQueries } from '@/lib/api/queries';
import { serverApi } from '@/lib/api/server';
import { getQueryClient } from '@/lib/query/getQueryClient';

/** Home: hero, tiles, banners and rails — all from content placements (nothing hard-coded). */
export default async function HomePage() {
  const api = await serverApi();
  const home = await getQueryClient().fetchQuery(siteQueries.home(api));
  return (
    <>
      <Hero slides={home.hero} />
      <Tiles tiles={home.tiles} />
      <Banners banners={home.banners} />
      <Rails rails={home.rails} />
    </>
  );
}
