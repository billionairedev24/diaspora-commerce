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
  'occasions',
  /* Dynamic home (docs/engineering/dynamic-home.md): modules the gateway decided are eligible. */
  'brand-hero',
  'sell-band',
  'department-tiles',
  'community-tiles',
  'service-cards'
] as const;
export type HomeBlockType = (typeof HOME_BLOCK_TYPES)[number];

/** Localisable module copy: the gateway sends a key and parameters, the storefront resolves them (S426). */
export const TitleParamsSchema = z.record(z.string(), z.union([z.string(), z.number()]));
export type TitleParams = z.infer<typeof TitleParamsSchema>;

/** Which analytics module a block belongs to, where it sits and which experiment variant built it (S427). Carries no shopper identity. */
export const HomeTrackingSchema = z.object({ module: z.string(), position: z.number(), variant: z.string().default('control') });
export type HomeTracking = z.infer<typeof HomeTrackingSchema>;

/** A service offering as a card on the home: provider, from price, city and an optional real photo. */
export const ServiceCardSchema = z.object({
  id: z.string(),
  name: z.string(),
  provider: z.string(),
  city: z.string().optional(),
  fromPriceMinor: z.number().optional(),
  currency: z.string().optional(),
  imageUrl: z.string().nullable(),
  href: z.string()
});
export type ServiceCard = z.infer<typeof ServiceCardSchema>;

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
  /** Department and community tiles carry `key` (taxonomy slug / community key: label and href come from the same key) and `count`. */
  cells: z.array(z.object({ label: z.string(), href: z.string(), imageUrl: z.string().nullable(), sub: z.string().optional(), key: z.string().optional(), count: z.number().optional() })).optional(),
  /** Deals: when the countdown ends (RFC 3339). */
  endsAt: z.string().optional(),
  /** Sponsored products: the search the products came from; clicks are reported with it and each card's ad token. */
  searchId: z.string().optional(),
  /** Paid modules: most times one browser session shows it (0 or absent = no cap). */
  maxPerSession: z.number().optional(),
  /** Copy as a key plus parameters (`home.deals.title`, `{city}`). When the storefront knows the key it wins over `title`; an unknown key falls back to `title`. */
  titleKey: z.string().optional(),
  titleParams: TitleParamsSchema.optional(),
  /** `shared` blocks are the same for every visitor of market + region + locale; `visitor` ones are personal and never cached together. */
  scope: z.enum(['shared', 'visitor']).optional(),
  tracking: HomeTrackingSchema.optional(),
  /** Service cards (S421). */
  services: z.array(ServiceCardSchema).optional()
});
export type HomeBlock = z.infer<typeof HomeBlockSchema>;

export const HomeSchema = z.object({
  announcements: z.array(AnnouncementSchema),
  hero: z.array(HeroSlideSchema),
  tiles: z.array(TileSchema),
  blocks: z.array(HomeBlockSchema),
  deliverTo: DeliveryLocationSchema,
  /** 2 when the gateway composed the page with the eligibility engine; absent from older gateways. */
  version: z.number().optional(),
  /** The visit seed the page was composed with (echoed so a view can be reproduced); `default` for crawlers. */
  seed: z.string().optional()
});
export type Home = z.infer<typeof HomeSchema>;

/** POST /v1/home/module-events (S427): one aggregate counter bump, sent only with analytics consent. */
export const HomeModuleEventSchema = z.object({
  event: z.enum(['impression', 'click']),
  module: z.string(),
  position: z.number(),
  variant: z.string(),
  target: z.enum(['product', 'department', 'community', 'service', 'link']).optional()
});
export type HomeModuleEvent = z.infer<typeof HomeModuleEventSchema>;
