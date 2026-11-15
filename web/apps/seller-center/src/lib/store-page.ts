import { type ProductSummary, type StoreConfig, type StoreSectionId } from '@afrotransact/api-client';

/* Pure helpers of Settings > Store page (the editor's state changes and its instant checks). The BFF validates again on save. */

export const MAX_FEATURED = 8;
export const MAX_SOCIAL = 6;

export const SECTION_COPY: Record<StoreSectionId, { title: string; hint: string }> = {
  announcement: { title: 'Announcement bar', hint: 'One line above the header, for a sale, a deadline or news.' },
  featured: { title: 'Featured products', hint: 'Up to eight products you pick, shown first.' },
  categories: { title: 'Shop by category', hint: 'Tiles for the categories you sell in.' },
  about: { title: 'Our story', hint: 'Your text and a photo.' },
  policies: { title: 'Shipping and returns', hint: 'Your own words about both, and pickup if you have it.' },
  reviews: { title: 'Customer reviews', hint: 'Appears only when there are real reviews.' },
  services: { title: 'Services', hint: 'Your services with durations and prices, from Provider Hub.' },
  book: { title: 'Book an appointment', hint: 'Customers pick a service and a free time, and book. Real availability only.' },
  schedule: { title: 'Opening hours and area', hint: 'Your weekly hours and where you work, from Provider Hub.' },
  team: { title: 'Team', hint: 'The team members customers can book.' }
};

export function moveSection(sections: StoreConfig['sections'], id: StoreSectionId, delta: -1 | 1): StoreConfig['sections'] {
  const i = sections.findIndex((s) => s.id === id);
  const j = i + delta;
  if (i < 0 || j < 0 || j >= sections.length) return sections;
  const next = [...sections];
  [next[i], next[j]] = [next[j]!, next[i]!];
  return next;
}

export const toggleSection = (sections: StoreConfig['sections'], id: StoreSectionId, enabled: boolean): StoreConfig['sections'] => sections.map((s) => (s.id === id ? { ...s, enabled } : s));

export function addFeatured(featured: readonly string[], atin: string): string[] {
  if (featured.includes(atin) || featured.length >= MAX_FEATURED) return [...featured];
  return [...featured, atin];
}

export const removeFeatured = (featured: readonly string[], atin: string): string[] => featured.filter((a) => a !== atin);

export function moveFeatured(featured: readonly string[], atin: string, delta: -1 | 1): string[] {
  const i = featured.indexOf(atin);
  const j = i + delta;
  if (i < 0 || j < 0 || j >= featured.length) return [...featured];
  const next = [...featured];
  [next[i], next[j]] = [next[j]!, next[i]!];
  return next;
}

export const sameConfig = (a: StoreConfig, b: StoreConfig): boolean => JSON.stringify(a) === JSON.stringify(b);

const HTMLISH = /<[a-zA-Z/!?]/;
const PHONE = /^[+0-9 ()\-.]{6,24}$/;

/** The instant version of the BFF's checks (lengths, plain text, phone, links); the BFF stays the judge. */
export function localProblems(c: StoreConfig): Record<string, string> {
  const e: Record<string, string> = {};
  const text = (path: string, v: string, max: number) => {
    if (v.length > max) e[path] = `At most ${max} characters`;
    else if (HTMLISH.test(v)) e[path] = 'Plain text only: remove the HTML';
  };
  text('hero.tagline', c.hero.tagline, 140);
  text('announcement.text', c.announcement.text, 140);
  text('about.heading', c.about.heading, 80);
  text('about.body', c.about.body, 2000);
  text('policies.shipping', c.policies.shipping, 600);
  text('policies.returns', c.policies.returns, 600);
  text('contact.hours', c.contact.hours, 200);
  if (c.contact.phone && !PHONE.test(c.contact.phone)) e['contact.phone'] = 'Use digits, spaces, + ( ) and -';
  if (c.announcement.href && !/^(\/(?!\/)\S*|https:\/\/\S+)$/.test(c.announcement.href)) e['announcement.href'] = 'Use a page on your store or a full https link';
  c.social.forEach((s, i) => {
    if (!/^https:\/\/\S+\.\S+/.test(s.url)) e[`social.${i}.url`] = 'Use the full https link of your profile';
  });
  return e;
}

export interface PreviewQuery {
  q: string;
  category: string;
  price: string;
  sort: 'relevance' | 'price_asc' | 'price_desc' | 'rating' | 'newest';
  /** Only products with a real was-price above the price. */
  onSale?: boolean;
}

const BUCKETS: Record<string, (m: number) => boolean> = {
  u10: (m) => m <= 999,
  '10-25': (m) => m >= 1000 && m <= 2500,
  '25-50': (m) => m >= 2501 && m <= 5000,
  o50: (m) => m >= 5001
};

/** The preview's grid: filters, sorts and searches the products the preview was given (no network). */
export function filterPreviewProducts(all: readonly ProductSummary[], q: PreviewQuery): ProductSummary[] {
  const needle = q.q.trim().toLowerCase();
  const bucket = BUCKETS[q.price];
  const list = all.filter((p) => (!needle || `${p.title} ${p.brand} ${p.department}`.toLowerCase().includes(needle)) && (!q.category || p.department === q.category) && (!bucket || bucket(p.priceMinor)) && (!q.onSale || (p.listPriceMinor !== null && p.listPriceMinor > p.priceMinor)));
  const by: Record<PreviewQuery['sort'], (a: ProductSummary, b: ProductSummary) => number> = {
    relevance: (a, b) => b.reviewCount - a.reviewCount,
    price_asc: (a, b) => a.priceMinor - b.priceMinor,
    price_desc: (a, b) => b.priceMinor - a.priceMinor,
    rating: (a, b) => b.rating - a.rating || b.reviewCount - a.reviewCount,
    newest: (a, b) => (a.atin < b.atin ? 1 : -1)
  };
  return [...list].sort(by[q.sort]);
}
