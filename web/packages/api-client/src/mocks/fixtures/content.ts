/* Content placements, site configuration, careers and demo accounts from the prototype
   (DEFAULT_PLACEMENTS, at-site.js DEF, JOBS, sign-in demo). In production these come from the
   content service (placements, site config) and identity (accounts) through the gateway BFF. */
import type { Job } from '../../schemas/careers';
import type { Placements } from '../../schemas/home';
import type { SiteConfig } from '../../schemas/site';

/** Placements without the product rails (rails are resolved against the catalogue by the mock BFF). */
export const PLACEMENTS: Omit<Placements, 'rails'> & { rails: Array<{ id: string; title: string; href: string; source: RailSource }> } = {
  announcements: [
    { id: 'a1', tone: 'ink', icon: 'truck', text: 'FREE delivery on Fulfilled by AfroTransact items', cta: 'Shop fast delivery', href: '/s?delivery=1' },
    { id: 'a2', tone: 'gold', icon: 'calendar-star', text: 'Diwali and Detty December stock is landing — order early', cta: 'See picks', href: '/s?community=Indian' },
    { id: 'a3', tone: 'green', icon: 'storefront', text: 'Sellers: 0% commission for your first 30 days', cta: 'Start selling', href: '/sell' }
  ],
  hero: [
    { id: 'h1', theme: 'gold', kicker: 'AfroTransact Brand', title: 'Party jollof, sorted.', sub: 'Our own seasoning, rice and pepper sauce — sold and fulfilled by AfroTransact.', cta: 'Shop AfroTransact Brand', href: '/s?soldBy=platform', label: 'AfroTransact', imageUrl: null },
    { id: 'h2', theme: 'green', kicker: 'Diwali', title: 'Light up the table.', sub: 'Aged basmati, diyas and spices from Edison to your door.', cta: 'Shop Indian kitchen', href: '/s?community=Indian', label: 'AfroTransact', imageUrl: null },
    { id: 'h3', theme: 'ink', kicker: 'Kingston Kitchen', title: 'Jerk season is every season.', sub: 'Blue Mountain jerk paste, fulfilled by AfroTransact.', cta: 'Shop now', href: '/p/AT000000P9', label: 'Sponsored', imageUrl: null },
    { id: 'h4', theme: 'sand', kicker: 'Services', title: 'Book a braider for Saturday.', sub: 'Licensed stylists near you. Deposits protected.', cta: 'Find a stylist', href: '/services', label: 'AfroTransact', imageUrl: null }
  ],
  tiles: [
    { id: 't1', title: 'Shop by community', cta: 'See all communities', href: '/s', cells: [
      { label: 'Nigerian', href: '/s?community=Nigerian', imageUrl: null }, { label: 'Indian', href: '/s?community=Indian', imageUrl: null },
      { label: 'Jamaican', href: '/s?community=Jamaican', imageUrl: null }, { label: 'Ethiopian', href: '/s?community=Ethiopian', imageUrl: null }] },
    { id: 't2', title: 'AfroTransact Brand staples', cta: 'Shop our brand', href: '/s?soldBy=platform', cells: [
      { label: 'Jollof Seasoning, 6 oz', href: '/p/AT000000P2', imageUrl: null }, { label: 'Aged Basmati Rice, 10 lb', href: '/p/AT00000P11', imageUrl: null },
      { label: 'African Black Soap, 8 oz', href: '/p/AT00000P16', imageUrl: null }, { label: 'Shito Black Pepper Sauce, Hot, 12 oz', href: '/p/AT000000P3', imageUrl: null }] },
    { id: 't3', title: 'Get it tomorrow', cta: 'See fast delivery', href: '/s?delivery=1', cells: [
      { label: 'Blue Mountain Jerk Paste', href: '/p/AT000000P9', imageUrl: null }, { label: 'Dried Hibiscus (Bissap)', href: '/p/AT000000P8', imageUrl: null },
      { label: 'Raw Unrefined Shea Butter', href: '/p/AT00000P15', imageUrl: null }, { label: 'Dried Guajillo Chiles', href: '/p/AT00000P22', imageUrl: null }] },
    { id: 't4', title: 'Book a service', cta: 'All services', href: '/services', cells: [
      { label: 'Hair & beauty', href: '/services', imageUrl: null }, { label: 'Catering', href: '/services', imageUrl: null },
      { label: 'Tailoring', href: '/services', imageUrl: null }, { label: 'Events & DJ', href: '/services', imageUrl: null }] }
  ],
  banners: [
    { id: 'b1', slot: 'home-mid', advertiser: 'Bonwire Weavers Collective', title: 'Hand-woven kente for graduation season', sub: 'Each stole is tagged with its weaver’s name.', cta: 'Shop kente', href: '/p/AT00000P14', imageUrl: null }
  ],
  rails: [
    { id: 'r1', title: 'Fulfilled by AfroTransact — fast to {city}', href: '/s?fulfilment=afrotransact', source: { kind: 'fulfilled-fastest', limit: 6 } },
    { id: 'r2', title: 'Best sellers in Groceries', href: '/s?dept=Groceries', source: { kind: 'department-bestsellers', department: 'Groceries', limit: 6 } },
    { id: 'r3', title: 'Discover other kitchens', href: '/s', source: { kind: 'curated', ids: ['p11', 'p9', 'p12', 'p22', 'p7', 'p10'] } }
  ]
};

export type RailSource =
  | { kind: 'fulfilled-fastest'; limit: number }
  | { kind: 'department-bestsellers'; department: string; limit: number }
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
    privacyEmail: 'privacy@afrotransact.com',
    accessibilityEmail: 'accessibility@afrotransact.com',
    legalEmail: 'legal@afrotransact.com',
    pressEmail: 'press@afrotransact.com',
    partnersEmail: 'partners@afrotransact.com',
    address: 'AfroTransact, Inc. · 1001 Texas Ave, Suite 1400 · Houston, TX 77002'
  },
  legal: [
    { key: 'terms', title: 'Conditions of Use', version: '3.2', effective: '2026-09-01', href: '/legal/terms' },
    { key: 'privacy', title: 'Privacy Notice', version: '4.0', effective: '2026-09-01', href: '/legal/privacy' },
    { key: 'cookies', title: 'Cookie preferences', version: '2.1', effective: '2026-09-01', href: '/legal/cookies' },
    { key: 'choices', title: 'Your Privacy Choices', version: '1.3', effective: '2026-09-01', href: '/legal/choices' },
    { key: 'sms', title: 'SMS & Notification Terms', version: '1.0', effective: '2026-10-10', href: '/legal/sms' },
    { key: 'returns', title: 'Returns & Refunds', version: '2.0', effective: '2026-06-01', href: '/legal/returns' },
    { key: 'accessibility', title: 'Accessibility Statement', version: '1.2', effective: '2026-08-20', href: '/legal/accessibility' }
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
