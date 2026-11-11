/* The mock BFF's home composition: the page body in the order staff would set it, with every block type. Mirrors the
   gateway's rules (paid products claim first, a product appears once, short rails and empty modules are hidden, paid
   modules always carry `sponsored` and the advertiser). */
import type { ProductSummary } from '../schemas/catalog';
import type { Home, HomeBlock, Rail } from '../schemas/home';
import type { DeliveryLocation } from '../schemas/location';
import { mockProductImageUrl } from './service';

interface Ctx {
  rails: Rail[];
  personal: Rail | null;
  eligible: ProductSummary[];
  loc: DeliveryLocation;
  now: Date;
  userId: string | null;
  rank: (items: ProductSummary[]) => ProductSummary[];
  tiles: Home['tiles'];
}

const MIN = { rail: 3, deals: 3, sponsored: 2, community: 3, seller: 3 } as const;

const discount = (p: ProductSummary) => (p.listPriceMinor && p.listPriceMinor > p.priceMinor ? Math.floor(((p.listPriceMinor - p.priceMinor) * 100) / p.listPriceMinor) : 0);

/** Next UTC midnight: the deals of the day rotate with the day. */
const endOfDay = (now: Date) => new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), now.getUTCDate() + 1)).toISOString();

export function composeBody(ctx: Ctx): HomeBlock[] {
  const { eligible, loc, now, userId, rank } = ctx;
  const used = new Set(ctx.tiles.flatMap((t) => t.cells.map((c) => c.atin).filter((a): a is string => !!a)));
  const free = (items: ProductSummary[]) => items.filter((x) => !used.has(x.atin));
  const claim = (items: ProductSummary[]) => {
    const kept = free(items);
    for (const x of kept) used.add(x.atin);
    return kept;
  };
  const byRail = (id: string) => ctx.rails.find((r) => r.id === id);

  /* Paid products claim before anything organic. */
  const sellerPromoted = eligible.filter((x) => x.sellerKind === 'SELLER' && x.reviewCount > 0).slice(0, 3);
  const sponsoredItems = claim(
    eligible
      .filter((x) => ['AT000000P9', 'AT00000P15', 'AT00000P14', 'AT00000P22'].includes(x.atin) || x.sponsor)
      .slice(0, 6)
      .map((x) => ({ ...x, sponsor: { advertiser: x.sellerName, campaignId: 'cmp_mock', slot: 1, adToken: `tok-${x.atin}` } }))
  );
  const strip = free(sellerPromoted).slice(0, 3);
  for (const x of strip) used.add(x.atin);

  const asRail = (id: string, title: string, href: string, items: ProductSummary[]): Rail => ({ id, title, href, items });
  const block = (b: HomeBlock): HomeBlock => b;

  // Evaluated when the module's turn comes (what earlier modules claimed is gone).
  const dealItems = () =>
    free(eligible)
      .filter((x) => discount(x) >= 10)
      .sort((a, b) => discount(b) - discount(a))
      .slice(0, 12);
  const community = eligible.find((x) => x.community)?.community ?? '';
  const communityItems = () => (community ? free(eligible.filter((x) => x.community === community)).slice(0, 12) : []);
  const sellerName = sellerPromoted[0]?.sellerName ?? '';
  const sellerItems = () => (sellerName ? free(eligible.filter((x) => x.sellerName === sellerName && x.sellerKind === 'SELLER')).slice(0, 10) : []);
  const img = (n: number) => mockProductImageUrl(eligible[n % Math.max(1, eligible.length)]?.atin ?? 'AT00000000');

  const average = (items: ProductSummary[]) =>
    items.length ? items.reduce((a, x) => a + x.rating * Math.max(1, x.reviewCount), 0) / items.reduce((a, x) => a + Math.max(1, x.reviewCount), 0) : 0;

  const out: HomeBlock[] = [];
  const push = (b: HomeBlock | null) => {
    if (b) out.push(b);
  };
  const rail = (id: string) => {
    const r = byRail(id);
    if (!r) return null;
    const items = free(r.items);
    if (items.length < MIN.rail) return null;
    return block({ id: r.id, type: 'rail', sponsored: false, rail: { ...r, items: claim(items) } });
  };

  push(ctx.personal && free(ctx.personal.items).length >= 2 ? block({ id: 'based-on-orders', type: 'based-on-orders', sponsored: false, rail: { ...ctx.personal, items: claim(ctx.personal.items) } }) : null);
  push(userId ? null : block({ id: 'plc_signin', type: 'signin-promo', sponsored: false, title: 'Sign in for faster checkout and picks made for you', sub: 'Track orders, save your delivery address and see products based on what you buy.', cta: 'Sign in or create an account', href: '/signin', tone: 'sand' }));
  const deals = dealItems();
  push(deals.length >= MIN.deals ? block({ id: 'plc_deals', type: 'deals', sponsored: false, title: 'Deals of the day', href: '/s?sort=rating', endsAt: endOfDay(now), rail: asRail('plc_deals', 'Deals of the day', '/s?sort=rating', claim(deals)) }) : null);
  push(rail('r-near'));
  push(sponsoredItems.length >= MIN.sponsored ? block({ id: 'plc_sp_products', type: 'sponsored-products', sponsored: true, advertiser: 'Marketplace sellers', title: 'Sponsored products from sellers', href: '/s', searchId: 'srch_mock', rail: asRail('plc_sp_products', 'Sponsored products from sellers', '/s', sponsoredItems) }) : null);
  push(rail('r-fast'));
  push(block({ id: 'plc_brand', type: 'brand-banner', sponsored: true, advertiser: 'Kingston Kitchen', title: 'Jerk season is every season', sub: 'Blue Mountain jerk paste, wings and sides: everything for the grill in one order.', cta: 'Shop Kingston Kitchen', href: '/s?q=jerk', imageUrl: img(2), logoUrl: null, tone: 'gold', maxPerSession: 3, products: strip }));
  push(rail('r1'));
  const comm = communityItems();
  push(comm.length >= MIN.community ? block({ id: 'plc_community', type: 'community-spotlight', sponsored: false, title: `Spotlight: the ${community} kitchen`, sub: 'Pantry staples and festival favourites.', cta: `Explore ${community}`, href: `/s?community=${encodeURIComponent(community)}`, rail: asRail('plc_community', `Spotlight: the ${community} kitchen`, `/s?community=${encodeURIComponent(community)}`, claim(comm)) }) : null);
  push(block({ id: 'plc_adrow', type: 'ad-row', sponsored: true, advertiser: 'Partners', maxPerSession: 5, ads: [
    { imageUrl: img(3), alt: 'Shea butter and oils from Tamale Shea Co-op', href: '/s?q=shea', advertiser: 'Tamale Shea Co-op' },
    { imageUrl: img(4), alt: 'Hand-woven kente from Bonwire Weavers', href: '/s?q=kente', advertiser: 'Bonwire Weavers Collective' },
    { imageUrl: img(5), alt: 'Spices from Mama Put Pantry', href: '/s?q=spice', advertiser: 'Mama Put Pantry' }] }));
  push(rail('r2'));
  push(block({ id: 'plc_preorders', type: 'preorders', sponsored: false }));
  const sellers = sellerItems();
  push(sellers.length >= MIN.seller ? block({ id: 'plc_seller', type: 'seller-spotlight', sponsored: false, title: `Top-rated seller near ${loc.city}: ${sellerName}`, sub: `${average(sellers).toFixed(1)} average rating`, cta: 'See their shop', href: `/s?q=${encodeURIComponent(sellerName)}`, rail: asRail('plc_seller', `Top-rated seller near ${loc.city}: ${sellerName}`, `/s?q=${encodeURIComponent(sellerName)}`, claim(sellers)) }) : null);
  push(block({ id: 'plc_advertorial', type: 'advertorial', sponsored: true, advertiser: 'Mama Put Pantry', title: 'How a Houston kitchen packs a Sunday jollof for 40', body: 'Mama Put Pantry shares the pot size, the pepper base and the order list behind its party-sized boxes.', cta: 'Read the story', href: '/s?q=jollof', imageUrl: img(6), maxPerSession: 3 }));
  push(block({ id: 'plc_occasions', type: 'occasions', sponsored: false, title: 'Shop by occasion', cells: [
    { label: 'Party', href: '/s?q=party', imageUrl: img(7) }, { label: 'Wedding', href: '/s?q=wedding', imageUrl: img(8) },
    { label: 'Naming ceremony', href: '/s?q=naming', imageUrl: img(9) }, { label: 'Graduation', href: '/s?q=graduation', imageUrl: img(10) }] }));
  push(block({ id: 'plc_services', type: 'services-near-you', sponsored: false, title: `Services near ${loc.city}`, sub: 'Book braiders, caterers, photographers and more from providers in your area.', cta: 'Browse services', href: '/services' }));
  push(rail('r3'));
  push(block({ id: 'plc_membership', type: 'membership-promo', sponsored: false, title: 'AfroTransact Plus: free delivery and early access', sub: 'Members get free delivery on every order and first look at festival stock.', cta: 'See membership', href: '/membership', tone: 'ink' }));
  push(block({ id: 'recently-viewed', type: 'recently-viewed', sponsored: false }));
  void rank;
  return out;
}
