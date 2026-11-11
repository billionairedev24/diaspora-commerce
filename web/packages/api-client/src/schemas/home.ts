import { z } from 'zod';

import { ProductSummarySchema } from './catalog';
import { DeliveryLocationSchema } from './location';

export const ToneSchema = z.enum(['ink', 'gold', 'green', 'sand']);
export type Tone = z.infer<typeof ToneSchema>;

export const AnnouncementSchema = z.object({ id: z.string(), tone: ToneSchema, icon: z.string(), text: z.string(), cta: z.string(), href: z.string() });
export type Announcement = z.infer<typeof AnnouncementSchema>;

export const HeroSlideSchema = z.object({
  id: z.string(),
  theme: ToneSchema,
  kicker: z.string(),
  title: z.string(),
  sub: z.string(),
  cta: z.string(),
  href: z.string(),
  /** "AfroTransact" for house promotions, "Sponsored" for paid placements. */
  label: z.string(),
  imageUrl: z.string().nullable(),
  /** Only on scheduled slides; a slide outside its window is never served. */
  startsAt: z.string().optional(),
  endsAt: z.string().optional()
});
export type HeroSlide = z.infer<typeof HeroSlideSchema>;

export const TileSchema = z.object({
  id: z.string(),
  title: z.string(),
  cta: z.string(),
  href: z.string(),
  /** A fixed image and link, or a product the gateway picked by a rule (then `atin` is set and the image is a real photo). */
  cells: z.array(z.object({ label: z.string(), href: z.string(), imageUrl: z.string().nullable(), atin: z.string().optional() })).max(4)
});
export type Tile = z.infer<typeof TileSchema>;

export const BannerSchema = z.object({
  id: z.string(),
  slot: z.string(),
  advertiser: z.string(),
  title: z.string(),
  sub: z.string(),
  cta: z.string(),
  href: z.string(),
  imageUrl: z.string().nullable()
});
export type Banner = z.infer<typeof BannerSchema>;

export const RailSchema = z.object({ id: z.string(), title: z.string(), href: z.string(), items: z.array(ProductSummarySchema) });
export type Rail = z.infer<typeof RailSchema>;

/** GET /v1/home/recently-viewed: cards for the ATINs the browser remembers, eligible ones only. */
export const RecentlyViewedSchema = z.object({ items: z.array(ProductSummarySchema) });
export type RecentlyViewed = z.infer<typeof RecentlyViewedSchema>;

export const PlacementsSchema = z.object({
  announcements: z.array(AnnouncementSchema),
  hero: z.array(HeroSlideSchema),
  tiles: z.array(TileSchema),
  banners: z.array(BannerSchema),
  rails: z.array(RailSchema)
});
export type Placements = z.infer<typeof PlacementsSchema>;

/** The home block types the storefront renders; a type it does not know is ignored. Paid ones are always `sponsored`. */
export const HOME_BLOCK_TYPES = [
  'rail',
  'banner',
  'brand-banner',
  'sponsored-products',
  'ad-row',
  'advertorial',
  'deals',
  'based-on-orders',
  'recently-viewed',
  'preorders',
  'services-near-you',
  'community-spotlight',
  'seller-spotlight',
  'membership-promo',
  'signin-promo',
  'occasions'
] as const;
export type HomeBlockType = (typeof HOME_BLOCK_TYPES)[number];

/** One module of the home page body, in the order staff set. `type` decides which fields are filled. */
export const HomeBlockSchema = z.object({
  id: z.string(),
  type: z.string(),
  /** Paid module: always shown with a visible "Sponsored" / "Ad" label and the advertiser. */
  sponsored: z.boolean(),
  advertiser: z.string().optional(),
  title: z.string().optional(),
  sub: z.string().optional(),
  body: z.string().optional(),
  cta: z.string().optional(),
  href: z.string().optional(),
  imageUrl: z.string().nullable().optional(),
  logoUrl: z.string().nullable().optional(),
  tone: z.string().optional(),
  rail: RailSchema.optional(),
  /** The optional product strip of a brand banner. */
  products: z.array(ProductSummarySchema).optional(),
  ads: z.array(z.object({ imageUrl: z.string(), alt: z.string(), href: z.string(), advertiser: z.string() })).optional(),
  cells: z.array(z.object({ label: z.string(), href: z.string(), imageUrl: z.string().nullable(), sub: z.string().optional() })).optional(),
  /** Deals: when the countdown ends (RFC 3339). */
  endsAt: z.string().optional(),
  /** Sponsored products: the search the products came from; clicks are reported with it and each card's ad token. */
  searchId: z.string().optional(),
  /** Paid modules: most times one browser session shows it (0 or absent = no cap). */
  maxPerSession: z.number().optional()
});
export type HomeBlock = z.infer<typeof HomeBlockSchema>;

export const HomeSchema = z.object({
  announcements: z.array(AnnouncementSchema),
  hero: z.array(HeroSlideSchema),
  tiles: z.array(TileSchema),
  blocks: z.array(HomeBlockSchema),
  deliverTo: DeliveryLocationSchema
});
export type Home = z.infer<typeof HomeSchema>;
