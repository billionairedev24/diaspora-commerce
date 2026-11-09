/* Mock module summaries for the Seller Center and Provider Hub home (contract: ModuleSummary). Figures come from the
   mock database where it holds them (listings, coupons, reviews, bookings, balance) and from the day series the
   dashboard mock already draws; modules the member's role cannot open are left out, as the BFF does. */
import { type ModuleAction, type ModuleSummary } from '../../schemas/seller';
import { type OrgKind } from '../../schemas/seller';
import { type MockOrg, type MockSellerDb } from './db';
import { allows } from './model';

interface Input {
  db: MockSellerDb;
  org: MockOrg;
  role: string;
  now: Date;
  rangeDays: number;
  /** Day series of the dashboard mock: sales and units (seller) or bookings and deposits (provider). */
  rows: { category: string; values: Record<string, number> }[];
  toShip: number;
  requests: number;
}

const usd = (minor: number) => new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD' }).format(minor / 100);
const sum = (rows: Input['rows'], key: string) => rows.reduce((a, r) => a + (r.values[key] ?? 0), 0);
const plural = (n: number, one: string, many: string) => `${n} ${n === 1 ? one : many}`;

export function homeModules(i: Input): ModuleSummary[] {
  const kind: OrgKind = i.org.kind;
  const can = (key: Parameters<typeof allows>[2]) => allows(kind, i.role, key, 'VIEW');
  const links = (...items: [Parameters<typeof allows>[2] | `+${Parameters<typeof allows>[2]}`, string, string][]): ModuleAction[] =>
    items
      .filter(([m]) => (m.startsWith('+') ? allows(kind, i.role, m.slice(1) as Parameters<typeof allows>[2], 'EDIT') : can(m as Parameters<typeof allows>[2])))
      .slice(0, 3)
      .map(([, label, href]) => ({ label, href }));
  const period = `Last ${i.rangeDays} days`;
  const out: ModuleSummary[] = [];
  const payouts = (): ModuleSummary => ({
    id: 'payouts', icon: 'payouts', title: 'Payouts', state: 'ready',
    headline: { value: usd(i.db.availableMinor), label: 'available to request', period: null, tone: 'positive' },
    totalLine: '$527.25 still settling · next release Nov 12',
    actions: links(['payouts', 'Payouts', '/payouts'], ['earnings', 'Earnings', '/earnings'], ['fees', 'Fees', '/fees']), empty: null, chart: null
  });
  const health = (): ModuleSummary => ({
    id: 'health', icon: 'health', title: 'Account health', state: 'ready',
    headline: kind === 'PROVIDER' ? { value: 'Good', label: 'standing', period: null, tone: 'positive' } : { value: 'Action needed', label: 'on your account', period: null, tone: 'attention' },
    totalLine: kind === 'PROVIDER' ? 'Nothing outstanding' : '2 items open · due Nov 20',
    actions: links(['account-health', 'Account health', '/account-health'], ['team', 'Team', '/team'], ['policy', 'Policies', '/policy']), chart: null, empty: null
  });

  if (kind === 'PROVIDER') {
    if (can('requests') || can('calendar')) {
      const total = sum(i.rows, 'bookings');
      out.push({
        id: 'bookings', icon: 'bookings', title: 'Bookings', state: 'ready',
        headline: { value: String(total), label: 'bookings', period, tone: 'positive' },
        chart: { kind: 'sparkline', label: `Bookings per day, last ${i.rangeDays} days`, valueFormat: 'count', points: i.rows.map((r) => ({ label: r.category, value: r.values.bookings ?? 0 })) },
        totalLine: `${i.requests > 0 ? `${plural(i.requests, 'request', 'requests')} to confirm · ` : ''}${usd(sum(i.rows, 'deposits') * 100)} in deposits`,
        actions: links(['requests', 'Requests', '/requests'], ['calendar', 'Calendar', '/calendar'], ['services', 'Services', '/services']), empty: null
      });
    }
    if (can('earnings') || can('payouts')) {
      const weeks = ['W1', 'W2', 'W3', 'W4', 'W5', 'W6'].map((w, k) => ({ label: w, paid: 2000 + ((k * 370) % 900), pending: 400 + ((k * 130) % 300) }));
      const paid = weeks.reduce((a, w) => a + w.paid, 0);
      const pending = weeks.reduce((a, w) => a + w.pending, 0);
      out.push({
        id: 'revenue', icon: 'revenue', title: 'Revenue', state: 'ready',
        headline: { value: usd((paid + pending) * 100), label: 'earned', period, tone: 'positive' },
        chart: { kind: 'bars', label: 'Earnings by week', valueFormat: 'money', points: weeks.map((w) => ({ label: `Wk ${w.label}`, value: w.paid + w.pending })) },
        totalLine: `${usd(paid * 100)} paid out · ${usd(pending * 100)} pending`,
        actions: links(['earnings', 'Earnings', '/earnings'], ['payouts', 'Payouts', '/payouts'], ['fees', 'Fees', '/fees']), empty: null
      });
    }
    if (can('calendar')) {
      const day = 86_400_000;
      const start = new Date(i.now.getFullYear(), i.now.getMonth(), i.now.getDate()).getTime();
      const confirmed = i.db.bookings.filter((b) => b.status === 'CONFIRMED');
      const perDay = Array.from({ length: 7 }, (_, k) => confirmed.filter((b) => { const t = new Date(b.startAt).getTime(); return t >= start + k * day && t < start + (k + 1) * day; }).length);
      const month = confirmed.filter((b) => new Date(b.startAt).getMonth() === i.now.getMonth()).length;
      out.push({
        id: 'calendar', icon: 'calendar', title: 'Availability', state: 'ready',
        headline: { value: String(perDay.reduce((a, n) => a + n, 0)), label: 'confirmed in the next 7 days', period: null, tone: 'neutral' },
        chart: { kind: 'bars', label: 'Confirmed bookings, next 7 days', valueFormat: 'count', points: perDay.map((n, k) => ({ label: new Date(start + k * day).toLocaleDateString('en-US', { month: 'short', day: 'numeric' }), value: n })) },
        totalLine: `${plural(month, 'confirmed booking', 'confirmed bookings')} this month`,
        actions: links(['calendar', 'Calendar', '/calendar'], ['hours', 'Working hours', '/hours']), empty: null
      });
    }
    if (can('payouts')) out.push(payouts());
    if (can('account-health')) out.push(health());
    return out;
  }

  if (can('orders')) {
    const sales = sum(i.rows, 'sales');
    const orders = Math.round(sum(i.rows, 'units') * 0.62);
    out.push({
      id: 'sales', icon: 'sales', title: 'Sales', state: 'ready',
      headline: { value: usd(sales * 100), label: 'in sales', period, tone: 'positive' },
      chart: { kind: 'sparkline', label: `Sales per day, last ${i.rangeDays} days`, valueFormat: 'money', points: i.rows.map((r) => ({ label: r.category, value: r.values.sales ?? 0 })) },
      totalLine: `${plural(orders, 'order', 'orders')} · ${plural(sum(i.rows, 'units'), 'unit', 'units')}`,
      actions: links(['orders', 'All orders', '/orders'], ['+listings', 'Add product', '/listings/new'], ['payouts', 'Payouts', '/payouts']), empty: null
    });
    out.push({
      id: 'orders', icon: 'orders', title: 'Orders', state: 'ready',
      headline: { value: String(i.toShip), label: 'to ship now', period: null, tone: i.toShip > 0 ? 'attention' : 'positive' },
      chart: { kind: 'donut', label: `Orders by fulfilment, last ${i.rangeDays} days`, valueFormat: 'count', points: [{ label: 'Fulfilled by AfroTransact', value: 412, tone: 'green' }, { label: 'Ship yourself', value: 268, tone: 'ink' }, { label: 'Local driver', value: 97, tone: 'gold' }] },
      totalLine: `${plural(777, 'order', 'orders')} in the last ${i.rangeDays} days`,
      actions: links(['orders', 'To ship', '/orders'], ['returns', 'Returns', '/returns'], ['orders', 'Ship-from address', '/orders/ship-from']), empty: null
    });
  }
  if (can('listings')) {
    const all = i.db.listings.length;
    const live = i.db.listings.filter((l) => l.status === 'LIVE').length;
    const review = i.db.listings.filter((l) => l.status === 'IN_REVIEW').length;
    out.push(
      all === 0
        ? { id: 'listings', icon: 'listings', title: 'Listings', state: 'empty', headline: null, chart: null, totalLine: null, actions: [], empty: { prompt: 'Add your first product to start selling.', art: 'listings', action: { label: 'Add your first product', href: '/listings/new' } } }
        : {
            id: 'listings', icon: 'listings', title: 'Listings', state: 'ready',
            headline: { value: String(live), label: 'live now', period: null, tone: 'neutral' },
            chart: { kind: 'donut', label: 'Listings by status', valueFormat: 'count', points: [{ label: 'Live', value: live, tone: 'green' }, { label: 'In review', value: review, tone: 'gold' }, { label: 'Paused or other', value: Math.max(all - live - review, 0), tone: 'grey' }] },
            totalLine: `${plural(all, 'listing', 'listings')} in total · ${review} in review`,
            actions: links(['listings', 'All listings', '/listings'], ['+listings', 'Add product', '/listings/new'], ['+listings', 'Bulk upload', '/listings/bulk']), empty: null
          }
    );
  }
  if (can('payouts')) out.push(payouts());
  if (can('inventory')) {
    out.push({
      id: 'inventory', icon: 'inventory', title: 'Inventory', state: 'ready',
      headline: { value: '1', label: 'SKUs low on stock', period: null, tone: 'attention' },
      chart: { kind: 'bars', label: 'Units available by warehouse', valueFormat: 'count', points: [{ label: 'ATL1', value: 540 }, { label: 'DFW1', value: 330 }, { label: 'HOU1', value: 820 }, { label: 'NJ1', value: 610 }] },
      totalLine: '2,300 units available across 41 SKUs',
      actions: links(['inventory', 'Inventory', '/inventory'], ['listings', 'Listings', '/listings']), empty: null
    });
  }
  if (can('reviews')) {
    const rs = i.db.reviews;
    out.push(
      rs.length === 0
        ? { id: 'reviews', icon: 'reviews', title: 'Reviews', state: 'empty', headline: null, chart: null, totalLine: null, actions: [], empty: { prompt: 'Verified buyers’ reviews will appear here after your first deliveries.', art: 'star', action: { label: 'Open reviews', href: '/reviews' } } }
        : {
            id: 'reviews', icon: 'reviews', title: 'Reviews', state: 'ready',
            headline: { value: (rs.reduce((a, r) => a + r.rating, 0) / rs.length).toFixed(1), label: 'average rating out of 5', period: null, tone: 'neutral' },
            chart: { kind: 'bars', label: 'Reviews by star rating', valueFormat: 'count', points: [5, 4, 3, 2, 1].map((s) => ({ label: plural(s, 'star', 'stars'), value: rs.filter((r) => r.rating === s).length })) },
            totalLine: `${plural(rs.length, 'review', 'reviews')}${rs.some((r) => !r.reply) ? ` · ${rs.filter((r) => !r.reply).length} waiting for a reply` : ''}`,
            actions: links(['reviews', 'Reply to reviews', '/reviews'], ['listings', 'Listings', '/listings']), empty: null
          }
    );
  }
  if (can('account-health')) out.push(health());
  if (can('coupons')) {
    const cs = i.db.coupons;
    out.push(
      cs.length === 0
        ? { id: 'promotions', icon: 'promotions', title: 'Promotions', state: 'empty', headline: null, chart: null, totalLine: null, actions: [], empty: { prompt: 'Run a promotion to bring shoppers back.', art: 'chart', action: { label: 'Create your first promotion', href: '/coupons' } } }
        : {
            id: 'promotions', icon: 'promotions', title: 'Promotions', state: 'ready',
            headline: { value: String(cs.filter((c) => c.status === 'ACTIVE').length), label: 'active now', period: null, tone: 'neutral' },
            chart: { kind: 'donut', label: 'Promotions by status', valueFormat: 'count', points: [{ label: 'Active', value: cs.filter((c) => c.status === 'ACTIVE').length, tone: 'green' }, { label: 'Paused', value: cs.filter((c) => c.status === 'PAUSED').length, tone: 'gold' }, { label: 'Ended or other', value: cs.filter((c) => c.status === 'EXPIRED').length, tone: 'grey' }] },
            totalLine: `${plural(cs.length, 'promotion', 'promotions')} in total`,
            actions: links(['coupons', 'All promotions', '/coupons']), empty: null
          }
    );
  }
  return out;
}
