/* Dev fixtures mirroring design/AfroTransact Mobile App.dc.html (products, addresses, orders, pros). */

export interface FixtureProduct {
  atin: string;
  title: string;
  priceMinor: number;
  sellerId: string;
  sellerName: string;
  sellerKind: 'PLATFORM' | 'SELLER';
  sellerCity: string;
  fulfilment: 'AFROTRANSACT' | 'SELLER';
  shipsFrom: string;
  department: string;
  community: string;
  rating: number;
  reviewCount: number;
  bestBefore: string;
  description: string;
  unit: string;
  sameDay: boolean;
  perishable: boolean;
  stock: number;
}

const p = (
  atin: string, title: string, priceMinor: number, sellerName: string, sellerKind: 'PLATFORM' | 'SELLER', sellerCity: string,
  fulfilment: 'AFROTRANSACT' | 'SELLER', shipsFrom: string, department: string, rating: number, reviewCount: number,
  bestBefore: string, description: string, unit: string, sameDay: boolean, perishable = false, stock = 40
): FixtureProduct => ({
  atin, title, priceMinor, sellerId: sellerName.toLowerCase().replace(/[^a-z]+/g, '-'), sellerName, sellerKind, sellerCity, fulfilment, shipsFrom,
  department, community: 'West African', rating, reviewCount, bestBefore, description, unit, sameDay, perishable, stock
});

export const PRODUCTS: FixtureProduct[] = [
  p('ATSHITO001', 'Shito Black Pepper Sauce, Hot, 12 oz', 899, 'AfroTransact Retail', 'PLATFORM', 'Houston, TX', 'AFROTRANSACT', 'Houston FC', 'Groceries', 4.8, 1240, 'Best before Aug 2027 (lot L2608-SHI-06)', 'Slow-cooked Ghanaian black pepper sauce with dried shrimp and herring. Hot.', 'per jar', true),
  p('ATJOLLOF02', 'AfroTransact Brand Jollof Seasoning, 6 oz', 799, 'AfroTransact Brand', 'PLATFORM', 'Houston, TX', 'AFROTRANSACT', 'Houston FC', 'Spices', 4.7, 860, 'Best before Oct 2027', 'Tomato, pepper, thyme and curry blend for party-style jollof.', 'per jar', true),
  p('ATPALMOI03', 'Red Palm Oil, 1 L', 1149, 'AfroTransact Brand', 'PLATFORM', 'Houston, TX', 'AFROTRANSACT', 'Houston FC', 'Groceries', 4.6, 410, 'Best before Jan 2028', 'Unrefined red palm oil from Ghana for stews and red red.', 'per bottle', true),
  p('ATGARI0004', 'Ijebu Gari, 5 lb', 1299, 'Lagos Pack Foods', 'SELLER', 'Edison, NJ', 'AFROTRANSACT', 'Edison FC', 'Groceries', 4.5, 530, 'Best before Nov 2026', 'Sour, crunchy Ijebu-style gari for soaking or eba.', 'per bag', false),
  p('ATBASMAT05', 'Aged Basmati Rice, 10 lb', 2199, 'AfroTransact Brand', 'PLATFORM', 'Houston, TX', 'AFROTRANSACT', 'Houston FC', 'Groceries', 4.8, 2210, 'Best before Mar 2028', 'Long-grain basmati aged 12 months; fluffy for jollof.', 'per bag', true),
  p('ATSUYA0006', 'Suya Spice (Yaji), 7 oz', 649, "Ade's Kitchen", 'SELLER', 'Houston, TX', 'SELLER', 'Houston, TX', 'Spices', 4.9, 320, 'Best before Jun 2027', 'Peanut, ginger and chilli blend, hand-ground in small batches.', 'per pouch', true),
  p('ATSOAP0007', 'African Black Soap, 8 oz', 949, 'AfroTransact Brand', 'PLATFORM', 'Atlanta, GA', 'AFROTRANSACT', 'Atlanta FC', 'Beauty', 4.7, 980, 'Use within 3 years of opening', 'Plantain-ash soap from Kumasi; gentle on skin and scalp.', 'per bar', false),
  p('ATKENTE008', 'Kente Print Headwrap', 1800, 'Lagos Textile Hub', 'SELLER', 'Atlanta, GA', 'SELLER', 'Atlanta, GA', 'Fashion', 4.6, 150, 'Wax cotton, 70 × 22 in', 'Bold kente-inspired print, pre-washed.', 'each', false, false, 3),
  p('ATJOLTRY09', 'Party Jollof Tray (serves 10)', 6500, "Mama Tee's Pantry", 'SELLER', 'Houston, TX', 'SELLER', 'Houston, TX', 'Groceries', 4.9, 88, 'Cooked to order · keep refrigerated', 'Smoky party jollof with fried plantain, cooked the morning of delivery.', 'per tray', true, true, 6)
];

export const USER = { id: 'usr_adaeze', firstName: 'Adaeze', lastName: 'Okafor', email: 'adaeze@example.com', phone: '(713) 555-0142' };

export const ADDRESSES = [
  { id: 'a1', name: 'Adaeze Okafor', phone: '+17135550142', line1: '1420 Aldine Bender Rd', line2: 'Apt 12', city: 'Houston', state: 'TX', zip: '77032', isDefault: true, verified: true },
  { id: 'a2', name: 'Adaeze Okafor', phone: '+17135550142', line1: '2700 Post Oak Blvd', line2: 'Suite 400', city: 'Houston', state: 'TX', zip: '77056', isDefault: false, verified: true }
];

export const PAYMENT_METHODS = [
  { id: 'pm_visa4242', type: 'card' as const, brand: 'visa', last4: '4242', label: 'Visa •• 4242', expires: '08/28', isDefault: true },
  { id: 'pm_cashapp', type: 'cashapp' as const, brand: null, last4: null, label: 'Cash App Pay · $adaezeo', expires: null, isDefault: false }
];

export interface FixtureProviderService {
  id: string;
  name: string;
  description: string;
  durationMinutes: number;
  priceMinor: number;
  depositMinor: number;
}

export interface FixtureProvider {
  orgId: string;
  displayName: string;
  category: string;
  location: string;
  timeZone: string;
  rating: number;
  ratingCount: number;
  instant: boolean;
  policy: { kind: string; label: string; text: string };
  services: FixtureProviderService[];
}

export const PROVIDERS: FixtureProvider[] = [
  {
    orgId: 'org_braidsbyama', displayName: 'Braids by Ama', category: 'Hair & beauty', location: 'Houston, TX', timeZone: 'America/Chicago', rating: 4.9, ratingCount: 212, instant: true,
    policy: { kind: 'MODERATE', label: 'Moderate', text: 'Full deposit refund up to 48 h before, half up to 24 h, none after.' },
    services: [
      { id: 'svc_knotless', name: 'Knotless braids, mid-back', description: 'Hair included', durationMinutes: 300, priceMinor: 22000, depositMinor: 5000 },
      { id: 'svc_cornrows', name: 'Cornrows, 8–10 rows', description: 'Wash not included', durationMinutes: 90, priceMinor: 6500, depositMinor: 1500 }
    ]
  },
  {
    orgId: 'org_mamatee', displayName: "Mama Tee's Catering", category: 'Catering', location: 'Houston, TX', timeZone: 'America/Chicago', rating: 4.8, ratingCount: 96, instant: false,
    policy: { kind: 'STRICT', label: 'Strict', text: 'Full deposit refund up to 7 days before, half up to 48 h, none after.' },
    services: [{ id: 'svc_partytray', name: 'Party jollof for 30', description: 'Jollof, chicken, plantain · delivered', durationMinutes: 120, priceMinor: 45000, depositMinor: 12000 }]
  },
  {
    orgId: 'org_lagostailor', displayName: 'Lagos Textile Hub Tailoring', category: 'Tailoring', location: 'Atlanta, GA', timeZone: 'America/New_York', rating: 4.7, ratingCount: 58, instant: true,
    policy: { kind: 'FLEXIBLE', label: 'Flexible', text: 'Full deposit refund up to 24 h before.' },
    services: [{ id: 'svc_agbada', name: 'Agbada fitting', description: 'Measurements and fabric consult', durationMinutes: 60, priceMinor: 4500, depositMinor: 0 }]
  }
];
