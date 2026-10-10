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
  imageUrl: z.string().nullable()
});
export type HeroSlide = z.infer<typeof HeroSlideSchema>;

export const TileSchema = z.object({
  id: z.string(),
  title: z.string(),
  cta: z.string(),
  href: z.string(),
  cells: z.array(z.object({ label: z.string(), href: z.string(), imageUrl: z.string().nullable() })).max(4)
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

export const PlacementsSchema = z.object({
  announcements: z.array(AnnouncementSchema),
  hero: z.array(HeroSlideSchema),
  tiles: z.array(TileSchema),
  banners: z.array(BannerSchema),
  rails: z.array(RailSchema)
});
export type Placements = z.infer<typeof PlacementsSchema>;

export const HomeSchema = PlacementsSchema.extend({ deliverTo: DeliveryLocationSchema });
export type Home = z.infer<typeof HomeSchema>;
