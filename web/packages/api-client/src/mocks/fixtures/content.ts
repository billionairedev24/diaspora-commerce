/* Content placements, site configuration, careers and demo accounts from the prototype
   (DEFAULT_PLACEMENTS, at-site.js DEF, JOBS, sign-in demo). In production these come from the
   content service (placements, site config) and identity (accounts) through the gateway BFF. */
import type { Job } from '../../schemas/careers';
import type { Placements, Tile } from '../../schemas/home';
import type { SiteConfig } from '../../schemas/site';

/** A tile cell rule the mock BFF resolves to a product (the real gateway takes the same idea as a rail-shaped source). */
export interface TileRule {
  kind: 'top-rated' | 'new' | 'fulfilled-fastest' | 'near-you';
  department?: string;
}
/** A fixed image-and-link cell, or a rule cell that is filled from the catalogue. */
export type MockTileCell = Tile['cells'][number] | { rule: TileRule };
export type MockTile = Omit<Tile, 'cells'> & { cells: MockTileCell[] };

/** Placements without the product rails and rule tiles (resolved against the catalogue by the mock BFF). */
export const PLACEMENTS: Omit<Placements, 'rails' | 'tiles'> & { tiles: MockTile[]; rails: Array<{ id: string; title: string; href: string; source: RailSource }> } = {
  announcements: [
    { id: 'a1', tone: 'ink', icon: 'truck', text: 'One flat delivery fee on every order. Pickup is free where offered.', cta: 'Shop now', href: '/s' },
    { id: 'a2', tone: 'gold', icon: 'calendar-star', text: 'Diwali and Detty December stock is landing — order early', cta: 'See picks', href: '/s?community=Indian' },
    { id: 'a3', tone: 'green', icon: 'storefront', text: 'Sellers: 0% commission for your first 30 days', cta: 'Start selling', href: '/sell' }
  ],
  hero: [
    { id: 'h1', theme: 'gold', kicker: 'AfroTransact Brand', title: 'Party jollof, sorted.', sub: 'Our own seasoning, rice and pepper sauce — sold and fulfilled by AfroTransact.', cta: 'Shop AfroTransact Brand', href: '/s?soldBy=platform', label: 'AfroTransact', imageUrl: null },
    { id: 'h2', theme: 'green', kicker: 'Diwali', title: 'Light up the table.', sub: 'Aged basmati, diyas and spices from Edison to your door.', cta: 'Shop Indian kitchen', href: '/s?community=Indian', label: 'AfroTransact', imageUrl: null },
    { id: 'h3', theme: 'ink', kicker: 'Kingston Kitchen', title: 'Jerk season is every season.', sub: 'Blue Mountain jerk paste, fulfilled by AfroTransact.', cta: 'Shop now', href: '/p/AT000000P9', label: 'Sponsored', imageUrl: null },
    { id: 'h-tx', theme: 'green', kicker: 'Texas', title: 'Fresh from Texas kitchens.', sub: 'Houston and Dallas sellers ship same day across Texas.', cta: 'Shop Texas sellers', href: '/s?q=Houston', label: 'AfroTransact', imageUrl: null },
    { id: 'h-nj', theme: 'gold', kicker: 'New Jersey', title: 'Edison to your door tomorrow.', sub: 'Spices, rice and sweets from the Garden State, fulfilled from Edison.', cta: 'Shop Edison', href: '/s?q=Edison', label: 'AfroTransact', imageUrl: null },
    { id: 'h4', theme: 'sand', kicker: 'Services', title: 'Book a braider for Saturday.', sub: 'Licensed stylists near you. Deposits protected.', cta: 'Find a stylist', href: '/services', label: 'AfroTransact', imageUrl: null }
  ],
  tiles: [
    { id: 't1', title: 'Shop by community', cta: 'See all communities', href: '/s', cells: [
      { label: 'Nigerian', href: '/s?community=Nigerian', imageUrl: null }, { label: 'Indian', href: '/s?community=Indian', imageUrl: null },
      { label: 'Jamaican', href: '/s?community=Jamaican', imageUrl: null }, { label: 'Ethiopian', href: '/s?community=Ethiopian', imageUrl: null }] },
    { id: 't2', title: 'Top rated by department', cta: 'See top rated', href: '/s', cells: [
      { rule: { kind: 'top-rated', department: 'Groceries' } }, { rule: { kind: 'top-rated', department: 'Beauty' } },
      { rule: { kind: 'top-rated', department: 'Fashion' } }, { rule: { kind: 'top-rated', department: 'Books' } }] },
    { id: 't4', title: 'Book a service', cta: 'All services', href: '/services', cells: [
      { label: 'Hair & beauty', href: '/services', imageUrl: null }, { label: 'Catering', href: '/services', imageUrl: null },
      { label: 'Tailoring', href: '/services', imageUrl: null }, { label: 'Events & DJ', href: '/services', imageUrl: null }] }
  ],
  banners: [
    { id: 'b1', slot: 'home-mid', advertiser: 'Bonwire Weavers Collective', title: 'Hand-woven kente for graduation season', sub: 'Each stole is tagged with its weaver’s name.', cta: 'Shop kente', href: '/p/AT00000P14', imageUrl: null }
  ],
  rails: [
    { id: 'r-near', title: 'Shipping from near {city}', href: '/s', source: { kind: 'near-you', limit: 6 } },
    { id: 'r-fast', title: 'Get it by {day}', href: '/s?fulfilment=afrotransact', source: { kind: 'deliverable-fast', maxDays: 2, limit: 6 } },
    { id: 'r1', title: 'Fulfilled by AfroTransact — fast to {city}', href: '/s?fulfilment=afrotransact', source: { kind: 'fulfilled-fastest', limit: 6 } },
    { id: 'r2', title: 'Top rated in Groceries', href: '/s?dept=Groceries', source: { kind: 'department-bestsellers', department: 'Groceries', limit: 6 } },
    { id: 'r3', title: 'Discover other kitchens', href: '/s', source: { kind: 'curated', ids: ['p11', 'p9', 'p12', 'p22', 'p7', 'p10', 'p1', 'p2', 'p3', 'p4', 'p5', 'p6'] } }
  ]
};

/** ZIP prefixes a placement is targeted at (content's `targeting.zipPrefixes`); placements not listed show everywhere. */
export const ZIP_TARGETING: Readonly<Record<string, readonly string[]>> = {
  'h-tx': ['75', '76', '77', '78', '79'],
  'h-nj': ['07', '08']
};

export type RailSource =
  | { kind: 'fulfilled-fastest'; limit: number }
  | { kind: 'department-bestsellers'; department: string; limit: number }
  | { kind: 'near-you'; limit: number }
  | { kind: 'deliverable-fast'; maxDays: number; limit: number }
  | { kind: 'curated'; ids: string[] };

/** Sponsored search placements (ad slots on results pages). */
export const SPONSORED_SEARCH: ReadonlyArray<{ id: string; advertiser: string }> = [
  { id: 'p9', advertiser: 'Kingston Kitchen' },
  { id: 'p15', advertiser: 'Tamale Shea Co-op' }
];

export const SITE_CONFIG: SiteConfig = {
  revision: 1,
  socials: [
    { network: 'tiktok', url: 'https://www.tiktok.com/@afrotransact', label: 'TikTok' },
    { network: 'instagram', url: 'https://www.instagram.com/afrotransact', label: 'Instagram' },
    { network: 'facebook', url: 'https://www.facebook.com/afrotransact', label: 'Facebook' },
    { network: 'x', url: 'https://x.com/afrotransact', label: 'X' },
    { network: 'youtube', url: 'https://www.youtube.com/@afrotransact', label: 'YouTube' },
    { network: 'linkedin', url: 'https://www.linkedin.com/company/afrotransact', label: 'LinkedIn' }
  ],
  contacts: {
    phone: '+1 (833) 555-0199',
    phoneE164: '+18335550199',
    privacyEmail: 'privacy@afrotransact.com',
    accessibilityEmail: 'accessibility@afrotransact.com',
    legalEmail: 'legal@afrotransact.com',
    pressEmail: 'press@afrotransact.com',
    partnersEmail: 'partners@afrotransact.com',
    address: 'AfroTransact, Inc. · 1001 Texas Ave, Suite 1400 · Houston, TX 77002'
  },
  legal: [
    { key: 'conditions-of-use', title: 'Conditions of Use', version: '3.2', effective: '2026-09-01', href: '/legal/conditions-of-use' },
    { key: 'privacy', title: 'Privacy Notice', version: '4.0', effective: '2026-09-01', href: '/legal/privacy' },
    { key: 'cookies', title: 'Cookie Notice', version: '2.1', effective: '2026-09-01', href: '/legal/cookies' },
    { key: 'privacy-choices', title: 'Your Privacy Choices', version: '1.3', effective: '2026-09-01', href: '/legal/privacy-choices' },
    { key: 'returns', title: 'Returns & Refunds', version: '2.0', effective: '2026-06-01', href: '/legal/returns' },
    { key: 'seller-terms', title: 'Seller Terms', version: '2.4', effective: '2026-09-01', href: '/legal/seller-terms' },
    { key: 'prohibited-items', title: 'Prohibited & Restricted Items', version: '1.6', effective: '2026-07-15', href: '/legal/prohibited-items' },
    { key: 'accessibility', title: 'Accessibility Statement', version: '1.2', effective: '2026-08-20', href: '/legal/accessibility' },
    { key: 'sms-terms', title: 'SMS & Notification Terms', version: '1.0', effective: '2026-10-10', href: '/legal/sms-terms' },
    { key: 'gift-card-terms', title: 'Gift Card Terms', version: '1.0', effective: '2026-10-01', href: '/legal/gift-card-terms' },
    { key: 'store-credit-terms', title: 'Store Credit Terms', version: '1.0', effective: '2026-10-01', href: '/legal/store-credit-terms' },
    { key: 'membership-terms', title: 'Plus Membership Terms', version: '1.0', effective: '2026-10-01', href: '/legal/membership-terms' },
    { key: 'referral-terms', title: 'Referral Program Terms', version: '1.0', effective: '2026-10-01', href: '/legal/referral-terms' },
    { key: 'preorder-delivery-terms', title: 'Pre-order and Delivery Run Terms', version: '1.0', effective: '2026-10-01', href: '/legal/preorder-delivery-terms' },
    { key: 'community-guidelines', title: 'Community Guidelines', version: '1.0', effective: '2026-10-01', href: '/legal/community-guidelines' },
    { key: 'ip-policy', title: 'Intellectual Property and Copyright Policy', version: '1.0', effective: '2026-10-01', href: '/legal/ip-policy' },
    { key: 'provider-terms', title: 'Service Provider Terms', version: '1.0', effective: '2026-10-01', href: '/legal/provider-terms' },
    { key: 'payments-terms', title: 'Payment Terms for Sellers and Providers', version: '1.0', effective: '2026-10-01', href: '/legal/payments-terms' },
    { key: 'subprocessors', title: 'Sub-processors and Service Providers', version: '1.0', effective: '2026-10-01', href: '/legal/subprocessors' },
    { key: 'data-processing', title: 'Data Processing Terms', version: '1.0', effective: '2026-10-01', href: '/legal/data-processing' },
    { key: 'seller-fees', title: 'Seller Fees Summary', version: '1.0', effective: '2026-10-01', href: '/legal/seller-fees' }
  ],
  identityProviders: [
    { id: 'google', name: 'Google', enabled: true },
    { id: 'apple', name: 'Apple', enabled: false },
    { id: 'facebook', name: 'Facebook', enabled: false }
  ],
  locales: [
    { code: 'en', name: 'English', dir: 'ltr' },
    { code: 'fr', name: 'Français', dir: 'ltr' },
    { code: 'es', name: 'Español', dir: 'ltr' },
    { code: 'ar', name: 'العربية', dir: 'rtl' }
  ],
  market: { code: 'us', currency: 'USD', country: 'United States' },
  appLinks: { ios: null, android: null },
  paymentMethods: ['VISA', 'Mastercard', 'AMEX', 'Discover', 'Cash App', 'Apple Pay', 'Google Pay'],
  smsProgramName: 'AfroTransact Alerts'
};

const JOB_ROWS: Array<[title: string, team: string, location: string, type: string, pay: string]> = [
  ['Senior Backend Engineer (Spring Boot)', 'Engineering', 'Remote (US)', 'Full-time', '$165k–$205k'],
  ['Go Engineer, Search & Discovery', 'Engineering', 'Remote (US)', 'Full-time', '$160k–$200k'],
  ['Product Designer, Seller Experience', 'Design', 'Houston, TX', 'Full-time', '$130k–$160k'],
  ['Trust & Safety Analyst', 'Operations', 'Atlanta, GA', 'Full-time', '$62k–$78k'],
  ['Fulfilment Associate (days)', 'Warehouse', 'Houston, TX', 'Hourly', '$19–$22/h'],
  ['Inventory Control Lead', 'Warehouse', 'Edison, NJ', 'Full-time', '$58k–$70k'],
  ['Delivery Driver', 'Delivery', 'Houston, TX', 'Hourly', '$21–$25/h + tips'],
  ['Delivery Driver', 'Delivery', 'Atlanta, GA', 'Hourly', '$20–$24/h + tips'],
  ['Seller Success Manager', 'Sales', 'Remote (US)', 'Full-time', '$75k–$95k'],
  ['Customer Support Specialist (Yoruba/Twi)', 'Operations', 'Remote (US)', 'Full-time', '$48k–$56k']
];

const SUMMARY: Record<string, string> = {
  Engineering: 'Build the services behind checkout, search and payouts for a fast-growing marketplace.',
  Design: 'Design the tools thousands of small businesses use every day.',
  Operations: 'Keep the marketplace safe and customers happy.',
  Warehouse: 'Receive, pick and pack orders for families across the country.',
  Delivery: 'Pick up from our warehouse and local sellers and deliver with care — company van, fuel and phone provided.',
  Sales: 'Help new sellers launch and grow on AfroTransact.'
};
const DOES: Record<string, string[]> = {
  Delivery: ['Complete a vehicle inspection and load-out scan each shift', 'Deliver 40–70 stops with photo and code proof', 'Handle exceptions and returns professionally'],
  Warehouse: ['Receive and put away inventory with scanners', 'Pick and pack accurately', 'Keep the floor safe']
};
const NEEDS: Record<string, string[]> = {
  Delivery: ['Valid driver’s licence and clean record', 'Able to lift 50 lb', 'Smartphone comfort'],
  Warehouse: ['Able to stand and lift 50 lb', 'Attention to detail']
};

export const JOBS: Job[] = JOB_ROWS.map(([title, team, location, type, pay], i) => ({
  id: `J${i}`,
  title,
  team,
  location,
  type,
  pay,
  summary: SUMMARY[team] ?? '',
  responsibilities: DOES[team] ?? ['Own meaningful work end to end', 'Ship improvements every week', 'Work closely with sellers and customers'],
  requirements: NEEDS[team] ?? ['Relevant experience', 'Clear communication', 'Care for diaspora communities']
}));

export interface DemoAccount { id: string; firstName: string; lastName: string; email: string; password: string; phone: string; roles: string[] }

export const DEMO_ACCOUNTS: DemoAccount[] = [
  { id: 'u_adaeze', firstName: 'Adaeze', lastName: 'Nwosu', email: 'adaeze@example.com', password: 'shop2026', phone: '+17135550142', roles: ['customer'] }
];

/** One-time code accepted by the mock for MFA and email verification (the prototype's demo code). */
export const DEMO_CODE = '246810';
