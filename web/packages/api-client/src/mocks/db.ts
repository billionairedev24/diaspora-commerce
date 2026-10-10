/* In-memory state for the mock gateway BFF. Stored on globalThis so every module instance in one
   process (Next.js route handlers, server components, tests) shares the same carts and sessions. */
import { DEFAULT_ZIP } from './delivery';
import { atinOf, type FixtureProduct, PLATFORM_SELLER, PRODUCTS } from './fixtures/catalog';
import { DEMO_ACCOUNTS, type DemoAccount } from './fixtures/content';

export interface MockOffer {
  offerId: string;
  atin: string;
  productId: string;
  sellerId: string;
  sellerName: string;
  city: string;
  priceMinor: number;
  listPriceMinor: number | null;
  fulfilment: 'AFROTRANSACT' | 'SELLER' | 'AFROTRANSACT_DELIVERY';
  stock: number;
}

export interface MockCartLine { lineId: string; offerId: string; quantity: number; selected: boolean }
export interface MockSaved { savedId: string; offerId: string }

export interface MockSession {
  sid: string;
  userId: string | null;
  zip: string;
  lines: MockCartLine[];
  saved: MockSaved[];
}

export interface MockChallenge {
  id: string;
  kind: 'mfa' | 'email' | 'passkey' | 'oidc';
  userId?: string;
  pending?: DemoAccount;
  returnTo: string;
  expiresAt: number;
}

export interface MockDb {
  products: Map<string, FixtureProduct>;
  offers: Map<string, MockOffer>;
  offersByAtin: Map<string, MockOffer[]>;
  sessions: Map<string, MockSession>;
  users: Map<string, DemoAccount>;
  challenges: Map<string, MockChallenge>;
  stockAlerts: Set<string>;
  seq: number;
}

export const slugify = (s: string): string =>
  s.toLowerCase().normalize('NFD').replace(/[̀-ͯ]/g, '').replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '');

export const sellerIdOf = (name: string): string => (name === PLATFORM_SELLER ? 'afrotransact' : slugify(name));

function createDb(): MockDb {
  const products = new Map<string, FixtureProduct>();
  const offers = new Map<string, MockOffer>();
  const offersByAtin = new Map<string, MockOffer[]>();
  for (const p of PRODUCTS) {
    const atin = atinOf(p.id);
    products.set(atin, p);
    const list = p.offers.map((o) => {
      const sellerId = sellerIdOf(o.seller);
      const offer: MockOffer = {
        offerId: `${atin}-${sellerId}`,
        atin,
        productId: p.id,
        sellerId,
        sellerName: o.seller,
        city: o.city,
        priceMinor: Math.round(o.price * 100),
        listPriceMinor: o.list ? Math.round(o.list * 100) : null,
        fulfilment: o.ff === 'AT' ? 'AFROTRANSACT' : o.ff === 'ATD' ? 'AFROTRANSACT_DELIVERY' : 'SELLER',
        stock: o.stock
      };
      offers.set(offer.offerId, offer);
      return offer;
    });
    offersByAtin.set(atin, list);
  }
  const users = new Map<string, DemoAccount>();
  DEMO_ACCOUNTS.forEach((u) => users.set(u.email, { ...u }));
  return { products, offers, offersByAtin, sessions: new Map(), users, challenges: new Map(), stockAlerts: new Set(), seq: 1 };
}

const KEY = '__AFROTRANSACT_MOCK_DB__';
type Holder = { [KEY]?: MockDb };

export function getDb(): MockDb {
  const g = globalThis as Holder;
  return (g[KEY] ??= createDb());
}

/** Restores the fixtures (tests). */
export function resetDb(): MockDb {
  const g = globalThis as Holder;
  g[KEY] = createDb();
  return g[KEY];
}

export function nextId(prefix: string): string {
  const db = getDb();
  db.seq += 1;
  return `${prefix}_${db.seq.toString(36)}${Math.random().toString(36).slice(2, 7)}`;
}

export function getSession(sid: string): MockSession {
  const db = getDb();
  let s = db.sessions.get(sid);
  if (!s) {
    s = { sid, userId: null, zip: DEFAULT_ZIP, lines: [], saved: [] };
    db.sessions.set(sid, s);
  }
  return s;
}

export function userById(id: string | null): DemoAccount | null {
  if (!id) return null;
  for (const u of getDb().users.values()) if (u.id === id) return u;
  return null;
}
