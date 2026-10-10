/* Sample catalogue ported from the design prototype (design/AfroTransact Shop.dc.html, const P).
   Prices are dollars here and converted to minor units by the mock database. */

export type FixtureFulfilment = 'AT' | 'S';

export interface FixtureOffer {
  seller: string;
  city: string;
  price: number;
  ff: FixtureFulfilment;
  stock: number;
  list?: number;
}

export interface FixtureProduct {
  id: string;
  name: string;
  brand: string;
  comm: string;
  cat: string;
  rating: number;
  reviews: number;
  unit: string;
  origin: string;
  bullets: string[];
  offers: FixtureOffer[];
  /** Id of the base product when this is a size/pack variant. */
  variantOf?: string;
  variantLabel?: string;
}

export const PLATFORM_SELLER = 'AfroTransact';

export const DEPARTMENTS = ['Groceries', 'Fashion', 'Beauty', 'Home & Art', 'Books', 'Health', 'Party'] as const;

const AT = PLATFORM_SELLER;

const BASE: FixtureProduct[] = [
  { id: 'p1', name: 'Ofada Rice, Destoned, 5 lb', brand: "Mama Tee's", comm: 'Nigerian', cat: 'Groceries', rating: 4.8, reviews: 1284, unit: '5 lb bag', origin: 'Ogun State, Nigeria', bullets: ['Unpolished local rice with its distinctive earthy aroma', 'Destoned twice, vacuum-sealed in Houston', 'Pairs with ayamase (designer stew)'], offers: [{ seller: "Mama Tee's Pantry", city: 'Houston, TX', price: 18.5, ff: 'AT', stock: 64 }, { seller: 'Lagos Foods NJ', city: 'Newark, NJ', price: 19.99, ff: 'S', stock: 20 }] },
  { id: 'p2', name: 'AfroTransact Brand Jollof Seasoning, 6 oz', brand: 'AfroTransact Brand', comm: 'West African', cat: 'Groceries', rating: 4.7, reviews: 3410, unit: '6 oz', origin: 'Blended in Houston, TX', bullets: ['Tomato, thyme, curry, bay and smoked pepper — balanced for party jollof', 'No MSG, no fillers', 'Sold and fulfilled by AfroTransact'], offers: [{ seller: AT, city: 'Houston, TX', price: 6.49, ff: 'AT', stock: 900, list: 7.99 }] },
  { id: 'p3', name: 'Shito Black Pepper Sauce, Hot, 12 oz', brand: 'Accra Kitchen Co.', comm: 'Ghanaian', cat: 'Groceries', rating: 4.9, reviews: 812, unit: '12 oz jar', origin: 'Made in the Bronx', bullets: ['Slow-fried with dried shrimp, herring and ginger', 'Small batches every Thursday'], offers: [{ seller: AT, city: 'Edison, NJ', price: 11.49, ff: 'AT', stock: 140 }, { seller: 'Accra Kitchen Co.', city: 'Bronx, NY', price: 12, ff: 'S', stock: 30 }] },
  { id: 'p4', name: 'Berbere Spice Blend, 8 oz', brand: 'Addis Spice House', comm: 'Ethiopian', cat: 'Groceries', rating: 4.9, reviews: 640, unit: '8 oz', origin: 'Blended in Maryland', bullets: ['Sun-dried chilies, korarima and fenugreek', 'Ground weekly for doro wat and misir'], offers: [{ seller: 'Addis Spice House', city: 'Silver Spring, MD', price: 9.75, ff: 'AT', stock: 75 }] },
  { id: 'p5', name: 'Ivory Teff Flour, Stone-Milled, 4 lb', brand: 'Addis Spice House', comm: 'Ethiopian', cat: 'Groceries', rating: 4.8, reviews: 402, unit: '4 lb', origin: 'Ethiopian highland teff', bullets: ['For injera that bubbles properly', 'Naturally gluten-free grain'], offers: [{ seller: 'Addis Spice House', city: 'Silver Spring, MD', price: 14, ff: 'S', stock: 40 }] },
  { id: 'p6', name: 'Kenyan AA Coffee, Whole Bean, 12 oz', brand: 'Nairobi Roast', comm: 'Kenyan', cat: 'Groceries', rating: 4.9, reviews: 527, unit: '12 oz', origin: 'Nyeri County, Kenya', bullets: ['Blackcurrant and grapefruit notes', 'Roasted to order, shipped same day'], offers: [{ seller: 'Nairobi Roast', city: 'Minneapolis, MN', price: 19, ff: 'S', stock: 55 }] },
  { id: 'p7', name: 'Xawaash Spice Mix, 4 oz', brand: 'Hamar Spice', comm: 'Somali', cat: 'Groceries', rating: 4.8, reviews: 233, unit: '4 oz', origin: 'Minneapolis, MN', bullets: ['Cumin, coriander, cardamom, cinnamon, clove', 'For bariis iskukaris and suqaar'], offers: [{ seller: 'Hamar Spice', city: 'Minneapolis, MN', price: 7.5, ff: 'S', stock: 80 }] },
  { id: 'p8', name: 'Dried Hibiscus (Bissap), 1 lb', brand: 'Dakar Market', comm: 'Senegalese', cat: 'Groceries', rating: 4.7, reviews: 391, unit: '1 lb', origin: 'Kaolack, Senegal', bullets: ['Whole calyces for bissap, zobo and sorrel', 'Resealable bag'], offers: [{ seller: 'Dakar Market', city: 'Bronx, NY', price: 10.99, ff: 'AT', stock: 120 }] },
  { id: 'p9', name: 'Blue Mountain Jerk Paste, Hot, 10 oz', brand: 'Kingston Kitchen', comm: 'Jamaican', cat: 'Groceries', rating: 4.8, reviews: 958, unit: '10 oz jar', origin: 'Miami, FL', bullets: ['Scotch bonnet, pimento and scallion', 'Marinade for chicken, pork or jackfruit'], offers: [{ seller: 'Kingston Kitchen', city: 'Miami, FL', price: 8.99, ff: 'AT', stock: 210 }] },
  { id: 'p10', name: 'Haitian Epis Seasoning Base, 16 oz', brand: 'Lakay Foods', comm: 'Haitian', cat: 'Groceries', rating: 4.7, reviews: 288, unit: '16 oz', origin: 'Miami, FL', bullets: ['Peppers, garlic, parsley, thyme and scallion', 'Refrigerate after opening'], offers: [{ seller: 'Lakay Foods', city: 'Miami, FL', price: 11, ff: 'S', stock: 45 }] },
  { id: 'p11', name: 'AfroTransact Brand Aged Basmati Rice, 10 lb', brand: 'AfroTransact Brand', comm: 'Indian', cat: 'Groceries', rating: 4.8, reviews: 2207, unit: '10 lb', origin: 'Punjab, India', bullets: ['Aged 18 months for long, separate grains', 'For biryani, pulao and jeera rice', 'Sold and fulfilled by AfroTransact'], offers: [{ seller: AT, city: 'Edison, NJ', price: 21.99, ff: 'AT', stock: 600, list: 25.99 }, { seller: 'Patel Grocers', city: 'Edison, NJ', price: 22.49, ff: 'S', stock: 60 }] },
  { id: 'p12', name: 'Ube Halaya (Purple Yam Jam), 12 oz', brand: 'Manila Pantry', comm: 'Filipino', cat: 'Groceries', rating: 4.6, reviews: 174, unit: '12 oz jar', origin: 'Daly City, CA', bullets: ['Made with real purple yam', 'For halo-halo, ensaymada and ube cake'], offers: [{ seller: 'Manila Pantry', city: 'Daly City, CA', price: 9.49, ff: 'S', stock: 35 }] },
  { id: 'p13', name: 'Ankara Wax Print Fabric, 6 Yards', brand: 'Lagos Textile Hub', comm: 'Nigerian', cat: 'Fashion', rating: 4.7, reviews: 366, unit: '6 yd', origin: 'Lagos, Nigeria', bullets: ['100% cotton, colour-fast', 'Enough for iro and buba'], offers: [{ seller: 'Lagos Textile Hub', city: 'Houston, TX', price: 48, ff: 'AT', stock: 22 }] },
  { id: 'p14', name: 'Hand-Woven Kente Stole, 72 in', brand: 'Bonwire Weavers', comm: 'Ghanaian', cat: 'Fashion', rating: 5.0, reviews: 118, unit: '72 in', origin: 'Bonwire, Ashanti Region', bullets: ['Strip-woven silk-cotton', 'Tagged with the weaver’s name'], offers: [{ seller: 'Bonwire Weavers Collective', city: 'Atlanta, GA', price: 64, ff: 'S', stock: 9 }] },
  { id: 'p15', name: 'Raw Unrefined Shea Butter, 16 oz', brand: 'Tamale Shea Co-op', comm: 'Ghanaian', cat: 'Beauty', rating: 4.8, reviews: 1902, unit: '16 oz', origin: 'Northern Region, Ghana', bullets: ['Grade A, from a women’s cooperative', 'Ingredients: Butyrospermum parkii (shea) butter'], offers: [{ seller: 'Tamale Shea Co-op', city: 'Columbus, OH', price: 16, ff: 'AT', stock: 150 }] },
  { id: 'p16', name: 'AfroTransact Brand African Black Soap, 8 oz', brand: 'AfroTransact Brand', comm: 'West African', cat: 'Beauty', rating: 4.6, reviews: 1544, unit: '8 oz', origin: 'Accra, Ghana', bullets: ['Plantain-skin ash, cocoa pod and shea', 'Ingredients listed in full (MoCRA)'], offers: [{ seller: AT, city: 'Atlanta, GA', price: 7.99, ff: 'AT', stock: 800 }] },
  { id: 'p17', name: 'Pepper & Palm: A West African Kitchen (Hardcover)', brand: 'Diaspora Books', comm: 'West African', cat: 'Books', rating: 4.9, reviews: 205, unit: 'Hardcover, 288 pp', origin: 'Atlanta, GA', bullets: ['120 recipes from Dakar to Lagos', 'Photographed in home kitchens'], offers: [{ seller: 'Diaspora Books', city: 'Atlanta, GA', price: 28, ff: 'S', stock: 50 }] },
  { id: 'p18', name: 'Ready-to-Wear Auto Gele Headwrap', brand: 'Owambe Party Co.', comm: 'Nigerian', cat: 'Party', rating: 4.6, reviews: 330, unit: 'One size', origin: 'Houston, TX', bullets: ['Pre-tied, pleated aso-oke', 'Adjustable band'], offers: [{ seller: 'Owambe Party Co.', city: 'Houston, TX', price: 35, ff: 'AT', stock: 4 }] },
  { id: 'p19', name: 'Moringa Leaf Powder, 8 oz', brand: 'Green Leaf Naturals', comm: 'West African', cat: 'Health', rating: 4.5, reviews: 612, unit: '8 oz', origin: 'Dallas, TX', bullets: ['Shade-dried leaf powder', 'Not intended to diagnose, treat, cure or prevent any disease'], offers: [{ seller: 'Green Leaf Naturals', city: 'Dallas, TX', price: 13, ff: 'S', stock: 90 }] },
  { id: 'p20', name: 'Bogolan Mudcloth Pillow Cover, 18 in', brand: 'Bamako Home', comm: 'Malian', cat: 'Home & Art', rating: 4.8, reviews: 144, unit: '18 × 18 in', origin: 'Ségou, Mali', bullets: ['Hand-dyed with fermented mud', 'Hidden zipper; insert not included'], offers: [{ seller: 'Bamako Home', city: 'Brooklyn, NY', price: 32, ff: 'S', stock: 18 }] },
  { id: 'p21', name: 'Clay Diya Lamps, Set of 12', brand: 'Patel Grocers', comm: 'Indian', cat: 'Party', rating: 4.7, reviews: 489, unit: '12 pcs', origin: 'Gujarat, India', bullets: ['Hand-painted terracotta', 'For Diwali and puja'], offers: [{ seller: 'Patel Grocers', city: 'Edison, NJ', price: 18, ff: 'S', stock: 70 }] },
  { id: 'p22', name: 'Dried Guajillo Chiles, 8 oz', brand: 'La Tiendita', comm: 'Mexican', cat: 'Groceries', rating: 4.8, reviews: 721, unit: '8 oz', origin: 'Zacatecas, Mexico', bullets: ['Whole, stemmed, pliable', 'For birria, pozole rojo and salsas'], offers: [{ seller: 'La Tiendita', city: 'Los Angeles, CA', price: 6.99, ff: 'AT', stock: 160 }] }
];

/** Size/pack variants generated exactly like the prototype (groceries and beauty only). */
const VARIANTS: Array<[label: string, mult: number, unit: string]> = [
  ['Value Size', 1.8, 'value size'],
  ['2-Pack', 1.9, '2-pack'],
  ['Family Pack', 3.4, 'family pack']
];

function expand(base: FixtureProduct[]): FixtureProduct[] {
  const out = base.map((p) => ({ ...p, offers: p.offers.map((o) => ({ ...o })) }));
  base.forEach((p) => {
    if (p.cat !== 'Groceries' && p.cat !== 'Beauty') return;
    VARIANTS.forEach(([label, mult, unit], vi) => {
      out.push({
        ...p,
        id: `${p.id}v${vi}`,
        name: `${p.name.replace(/, [^,]+$/, '')}, ${label}`,
        unit: `${unit} (${p.unit})`,
        reviews: Math.round(p.reviews * (0.18 + vi * 0.07)),
        rating: Math.max(4.2, Math.round((p.rating - 0.1 * vi) * 10) / 10),
        variantOf: p.id,
        variantLabel: label,
        offers: p.offers.map((o) => ({ seller: o.seller, city: o.city, ff: o.ff, price: Math.round(o.price * mult * 100) / 100 - 0.01, stock: Math.max(3, Math.round(o.stock / (vi + 2))) }))
      });
    });
  });
  // Demo stock states from the prototype: kente stole and ube jam sold out, auto gele nearly gone.
  for (const id of ['p14', 'p12']) out.find((x) => x.id === id)?.offers.forEach((o) => { o.stock = 0; });
  const gele = out.find((x) => x.id === 'p18')?.offers[0];
  if (gele) gele.stock = 3;
  return out;
}

export const PRODUCTS: readonly FixtureProduct[] = expand(BASE);

/** Product id (p1, p1v0 …) → ATIN, matching the search contract pattern ^AT[0-9A-Z]{8}$. */
export function atinOf(id: string): string {
  return 'AT' + id.toUpperCase().padStart(8, '0');
}

export const COMMUNITIES: readonly string[] = [...new Set(PRODUCTS.map((p) => p.comm))].sort();
