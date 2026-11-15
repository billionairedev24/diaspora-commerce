/* MOCK DATA ONLY. A demo seller for the storefront (/b/laserfeet) so the page can be built, screenshotted and tested without
   a backend. Nothing here is real: names, prices, ratings and stock are invented fixtures, and they live in the mock BFF only.
   They are never part of a seed or a production bundle path (the mock runtime is loaded only when NEXT_PUBLIC_API_MOCKS=1). */

export type DemoShape = 'jar' | 'tube' | 'box' | 'socks' | 'bottle' | 'tool';

export interface DemoStoreProduct {
  id: string;
  name: string;
  brand: string;
  department: string;
  unit: string;
  priceMinor: number;
  listMinor?: number;
  stock: number;
  /** 0 reviews means no rating at all (never shown as "0 reviews"). */
  rating: number;
  reviews: number;
  shape: DemoShape;
  /** False: the product has no real photograph, so the storefront must not list it (kept to prove the rule). */
  photo: boolean;
}

const p = (id: string, name: string, department: string, unit: string, price: number, shape: DemoShape, extra: Partial<DemoStoreProduct> = {}): DemoStoreProduct => ({
  id, name, brand: 'Laserfeet', department, unit, priceMinor: Math.round(price * 100), stock: 40, rating: 0, reviews: 0, shape, photo: true, ...extra
});

export const LASERFEET_PRODUCTS: readonly DemoStoreProduct[] = [
  p('lf01', 'Shea Heel Balm', 'Foot care', '4 oz', 12.5, 'jar', { listMinor: 1500, rating: 4.8, reviews: 64 }),
  p('lf02', 'Peppermint Foot Soak', 'Foot care', '16 oz', 18, 'bottle', { rating: 4.7, reviews: 41 }),
  p('lf03', 'Callus Remover Gel', 'Foot care', '6 oz', 16.99, 'tube', { rating: 4.5, reviews: 18 }),
  p('lf04', 'Exfoliating Foot Mask, 3 pairs', 'Foot care', '3 pairs', 23.5, 'box', { rating: 4.6, reviews: 35 }),
  p('lf05', 'Overnight Cracked Heel Cream', 'Foot care', '3 oz', 14, 'jar'),
  p('lf06', 'Cushion Crew Socks', 'Socks and insoles', '3 pairs', 21, 'socks', { rating: 4.9, reviews: 112 }),
  p('lf07', 'Gel Heel Cups', 'Socks and insoles', '1 pair', 17.5, 'box', { stock: 3 }),
  p('lf08', 'Bamboo Comfort Socks', 'Socks and insoles', '2 pairs', 19, 'socks', { rating: 4.4, reviews: 9 }),
  p('lf09', 'Arch Support Insoles', 'Socks and insoles', '1 pair', 29.99, 'box', { listMinor: 3499 }),
  p('lf10', 'Aloe Moisturising Socks', 'Socks and insoles', '1 pair', 15, 'socks'),
  p('lf11', 'Foot Spa Massager', 'Spa and tools', '1 unit', 64, 'tool', { rating: 4.7, reviews: 82 }),
  p('lf12', 'Toe Separators', 'Spa and tools', '4 pack', 9.99, 'box'),
  p('lf13', 'Wooden Foot Roller', 'Spa and tools', '1 unit', 22, 'tool', { rating: 4.3, reviews: 6 }),
  p('lf14', 'Stainless Nail Clipper Set', 'Spa and tools', '5 piece', 27, 'tool'),
  /* Kept out of the page on purpose: sold out, and no photograph. */
  p('lf98', 'Limited Foot Scrub (sold out)', 'Foot care', '8 oz', 19, 'jar', { stock: 0 }),
  p('lf99', 'Pumice Stone (no photo yet)', 'Spa and tools', '1 unit', 6, 'tool', { photo: false })
];

export const LASERFEET_PROFILE = {
  slug: 'laserfeet',
  name: 'Laserfeet',
  description: 'Foot care, comfortable socks and spa tools.',
  sinceYear: 2025,
  contactEmail: 'hello@laserfeet.example.com'
} as const;
