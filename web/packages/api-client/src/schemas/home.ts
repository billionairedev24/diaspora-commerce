import { z } from 'zod';

import { ProductSummarySchema } from './catalog';
import { DeliveryLocationSchema } from './location';
import { LocaleLabelsSchema } from './site-nav';

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
export const TitleParamsSchema = z.record(z.string(), z.string());
export type TitleParams = z.infer<typeof TitleParamsSchema>;

/** Which analytics module a block belongs to, where it sits and which experiment variant built it (S427). Carries no shopper identity. */
export const HomeTrackingSchema = z.object({ module: z.string(), position: z.number(), variant: z.string().default('control') });
export type HomeTracking = z.infer<typeof HomeTrackingSchema>;

/** A service offering as a card on the home: provider, from price, city and an optional real photo. */
export const ServiceCardSchema = z.object({
  id: z.string(),
  title: z.string(),
  provider: z.string(),
  city: z.string().optional(),
  fromPriceMinor: z.number().nullable().optional(),
  currency: z.string().optional(),
  imageUrl: z.string().nullable(),
  href: z.string()
});
export type ServiceCard = z.infer<typeof ServiceCardSchema>;

/** A tile of a generated module. `key` is the department or community key that BOTH `label` and `href` were made from; `labels` is per locale (English always present). */
export const HomeCellSchema = z.object({
  label: z.string(),
  href: z.string(),
  imageUrl: z.string().nullable(),
  sub: z.string().optional(),
  key: z.string().optional(),
  labelKey: z.string().optional(),
  labels: LocaleLabelsSchema.optional(),
  count: z.number().optional()
});
export type HomeCell = z.infer<typeof HomeCellSchema>;

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
  cells: z.array(HomeCellSchema).optional(),
  /** Deals: when the countdown ends (RFC 3339). */
  endsAt: z.string().optional(),
  /** Sponsored products: the search the products came from; clicks are reported with it and each card's ad token. */
  searchId: z.string().optional(),
  /** Paid modules: most times one browser session shows it (0 or absent = no cap). */
  maxPerSession: z.number().optional(),
  /** Copy as a key plus parameters (`home.deals.title`, `{city}`). When the storefront knows the key it wins over `title`; an unknown key falls back to `title`. */
  titleKey: z.string().optional(),
  titleParams: TitleParamsSchema.optional(),
  subKey: z.string().optional(),
  ctaKey: z.string().optional(),
  bodyKey: z.string().optional(),
  /** The registry module that produced a generated block (`hero`, `deals`, `department-tiles`, `communities`, `groceries-rail`, ...). */
  module: z.string().optional(),
  /** True when the block has no real photo and must render as text (a brand hero, a hero slide without an image). */
  textOnly: z.boolean().optional(),
  /** `shared` blocks are the same for every visitor of market + region + locale; `visitor` ones are personal and never cached together. */
  scope: z.enum(['shared', 'visitor']).optional(),
  tracking: HomeTrackingSchema.optional(),
  /** Service cards (S421). */
  services: z.array(ServiceCardSchema).optional()
});
export type HomeBlock = z.infer<typeof HomeBlockSchema>;

/** Why a module is shown or hidden (the codes of the contract); the sentence is in staff words and names modules, never ids. */
export const HOME_DECISION_REASONS = [
  'shown',
  'disabled-by-staff',
  'not-enough-products',
  'not-enough-sellers',
  'not-fresh-enough',
  'not-enough-departments',
  'no-shopper-signal',
  'nothing-to-show',
  'sponsored-cap',
  'page-full'
] as const;
export type HomeDecisionReason = (typeof HOME_DECISION_REASONS)[number];

export const HomeModuleDecisionSchema = z.object({
  module: z.string(),
  label: z.string(),
  shown: z.boolean(),
  order: z.number().optional(),
  reason: z.string(),
  detail: z.string(),
  have: z.number().optional(),
  need: z.number().optional(),
  units: z.array(z.string()).optional(),
  pool: z.number().optional(),
  picked: z.number().optional(),
  band: z.string().optional(),
  /** Staff preview only: a staff placement won this slot over the automatic module. */
  pinned: z.boolean().optional(),
  /** Staff preview only: anchor of the rule row in Home settings. */
  editRule: z.string().optional()
});
export type HomeModuleDecision = z.infer<typeof HomeModuleDecisionSchema>;

/** What the storefront needs to continue the visit: the window the page was composed for and the short keys of what it shows (the `at_hs` cookie). */
export const HomeVisitSchema = z.object({ window: z.string().default(''), seen: z.array(z.string()).default([]), deterministic: z.boolean().optional() });
export type HomeVisit = z.infer<typeof HomeVisitSchema>;

/** Staff session with `debug=1` only. */
export const HomeDebugSchema = z.object({
  seed: z.string(),
  visitor: z.string().optional(),
  visitWindow: z.string().optional(),
  noveltyMemory: z.number().optional(),
  dynamic: z.enum(['off', 'shadow', 'on']).optional(),
  decisions: z.array(HomeModuleDecisionSchema)
});
export type HomeDebug = z.infer<typeof HomeDebugSchema>;

export const HomeSchema = z.object({
  announcements: z.array(AnnouncementSchema),
  hero: z.array(HeroSlideSchema),
  tiles: z.array(TileSchema),
  blocks: z.array(HomeBlockSchema),
  deliverTo: DeliveryLocationSchema,
  /** 2 when the gateway composed the page with the eligibility engine; 1 or absent from older gateways. */
  version: z.number().optional(),
  generatedAt: z.string().optional(),
  locale: z.string().optional(),
  variant: z.string().optional(),
  visit: HomeVisitSchema.optional(),
  debug: HomeDebugSchema.optional()
});
export type Home = z.infer<typeof HomeSchema>;

/** POST /v1/home/events (S427): one aggregate counter bump, sent only with analytics consent. */
export const HomeModuleEventSchema = z.object({
  event: z.enum(['impression', 'click']),
  module: z.string(),
  position: z.number(),
  variant: z.string(),
  target: z.enum(['product', 'department', 'community', 'service', 'other']).optional()
});
export type HomeModuleEvent = z.infer<typeof HomeModuleEventSchema>;
