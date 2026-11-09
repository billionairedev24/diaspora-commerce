/* Mock module summaries for the staff console home (contract: ModuleSummary), computed from the mock records and limited to
   the modules the staff member's roles open, like the BFF. Failed jobs and dead-letter queues are not exposed by any
   service, so there is no such figure here either. */
import { type ModuleAction, type ModuleSummary } from '../../schemas/seller';
import { type ResourceKey, type StaffModuleKey } from '../../schemas/staff';
import { allows } from './model';

interface Rec {
  fields: Record<string, unknown>;
}

interface Input {
  roles: string[];
  now: Date;
  rangeDays: number;
  records: (r: ResourceKey) => Rec[];
}

const usd = (minor: number) => new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD', maximumFractionDigits: 0 }).format(minor / 100);
const plural = (n: number, one: string, many: string) => `${n} ${n === 1 ? one : many}`;
const humanize = (s: string) => s.charAt(0) + s.slice(1).toLowerCase().replace(/_/g, ' ');
const DAY = 86_400_000;

export function staffHomeModules({ roles, now, rangeDays, records }: Input): ModuleSummary[] {
  const can = (m: StaffModuleKey) => allows(roles, m);
  const links = (...items: [StaffModuleKey, string, string][]): ModuleAction[] => items.filter(([m]) => can(m)).slice(0, 3).map(([, label, href]) => ({ label, href }));
  const period = `Last ${rangeDays} days`;
  const out: ModuleSummary[] = [];
  const ready = (id: string, icon: ModuleSummary['icon'], title: string, m: Omit<ModuleSummary, 'id' | 'icon' | 'title' | 'state' | 'empty'>): void => {
    out.push({ id, icon, title, state: 'ready', empty: null, ...m });
  };
  const empty = (id: string, icon: ModuleSummary['icon'], title: string, art: 'inbox' | 'payout' | 'listings', prompt: string, label: string, href: string): void => {
    out.push({ id, icon, title, state: 'empty', headline: null, chart: null, totalLine: null, actions: [], empty: { prompt, art, action: { label, href } } });
  };

  if (can('orders')) {
    const since = now.getTime() - rangeDays * DAY;
    const orders = records('orders').filter((o) => new Date(String(o.fields.placedAt)).getTime() >= since);
    if (!orders.length) empty('orders', 'orders', 'Orders & shipments', 'inbox', 'Orders show up here as soon as shoppers check out.', 'Open orders', '/orders');
    else {
      const points = Array.from({ length: rangeDays }, (_, k) => {
        const d = new Date(now.getTime() - (rangeDays - 1 - k) * DAY);
        return { label: d.toLocaleDateString('en-US', { month: 'short', day: 'numeric' }), value: orders.filter((o) => String(o.fields.placedAt).slice(0, 10) === d.toISOString().slice(0, 10)).length };
      });
      ready('orders', 'orders', 'Orders & shipments', {
        headline: { value: String(orders.length), label: 'orders', period, tone: 'positive' },
        chart: { kind: 'sparkline', label: `Orders per day, last ${rangeDays} days`, valueFormat: 'count', points },
        totalLine: `${usd(orders.reduce((a, o) => a + Number(o.fields.totalMinor), 0))} gross sales`,
        actions: links(['orders', 'All orders', '/orders'], ['dispatch', 'Dispatch', '/dispatch'], ['exports', 'Exports', '/exports'])
      });
    }
  }

  if (can('apps')) {
    const apps = records('applications');
    const by = (s: string) => apps.filter((a) => a.fields.status === s).length;
    const waiting = by('PENDING_REVIEW') + by('FLAGGED') + by('AWAITING_FINAL_APPROVAL');
    const tones: Record<string, 'gold' | 'red' | 'blue' | 'grey' | 'green' | 'ink'> = { PENDING_REVIEW: 'gold', FLAGGED: 'red', AWAITING_FINAL_APPROVAL: 'blue', INFO_REQUESTED: 'grey', APPROVED: 'green', REJECTED: 'ink' };
    const points = Object.keys(tones).map((s) => ({ label: humanize(s), value: by(s), tone: tones[s] })).filter((p) => p.value > 0);
    const oldest = apps.filter((a) => a.fields.status === 'PENDING_REVIEW').map((a) => new Date(String(a.fields.submittedAt)).getTime()).sort((a, b) => a - b)[0];
    const age = oldest === undefined ? '' : `Oldest waiting since ${Math.max(0, Math.floor((now.getTime() - oldest) / DAY))} days ago · `;
    if (!apps.length) empty('applications', 'applications', 'Applications', 'inbox', 'New seller and provider applications will appear here for review.', 'Open applications', '/applications');
    else {
      ready('applications', 'applications', 'Applications', {
        headline: waiting ? { value: String(waiting), label: 'waiting for review', period: null, tone: 'attention' } : { value: 'Queue clear', label: 'nothing waiting for review', period: null, tone: 'positive' },
        chart: { kind: 'donut', label: 'Applications by status', valueFormat: 'count', points },
        totalLine: `${age}${plural(apps.length, 'application', 'applications')} in total`,
        actions: links(['apps', 'Review queue', '/applications?status=PENDING_REVIEW'], ['apps', 'Flagged', '/applications?status=FLAGGED'], ['deptRequests', 'Department requests', '/department-requests'])
      });
    }
  }

  if (can('payouts')) {
    const payouts = records('payouts');
    const queued = payouts.filter((p) => p.fields.status === 'REQUESTED');
    const open = records('reconciliation').filter((r) => r.fields.status === 'OPEN').length;
    if (!payouts.length && !open) empty('payments', 'payments', 'Payments & payouts', 'payout', 'Payout requests and ledger differences will show up here.', 'Open payouts', '/payouts');
    else {
      const weeks = Math.max(1, Math.ceil(rangeDays / 7));
      const points = Array.from({ length: weeks }, (_, k) => {
        const from = now.getTime() - (weeks - k) * 7 * DAY;
        return {
          label: `Wk of ${new Date(from).toLocaleDateString('en-US', { month: 'short', day: 'numeric' })}`,
          value: payouts.filter((p) => { const t = new Date(String(p.fields.requestedAt ?? p.fields.createdAt ?? now.toISOString())).getTime(); return t >= from && t < from + 7 * DAY; }).reduce((a, p) => a + Number(p.fields.amountMinor), 0) / 100
        };
      });
      ready('payments', 'payments', 'Payments & payouts', {
        headline: queued.length ? { value: String(queued.length), label: 'payouts to approve', period: null, tone: 'attention' } : { value: 'None waiting', label: 'no payouts to approve', period: null, tone: 'positive' },
        chart: { kind: 'bars', label: `Payouts requested per week, last ${rangeDays} days`, valueFormat: 'money', points },
        totalLine: `${usd(queued.reduce((a, p) => a + Number(p.fields.amountMinor), 0))} waiting · ${plural(open, 'open difference', 'open differences')}`,
        actions: links(['payouts', 'Payouts to approve', '/payouts?tab=payouts&payouts.status=REQUESTED'], ['payouts', 'Open differences', '/payouts?tab=reconciliation&reconciliation.status=OPEN'], ['fees', 'Fees', '/fees'])
      });
    }
  }

  if (can('disputes')) {
    const claims = records('claims');
    const underReview = claims.filter((c) => c.fields.status === 'UNDER_REVIEW').length;
    const refunded = claims.filter((c) => c.fields.status === 'RESOLVED_REFUND');
    if (!underReview && !refunded.length) empty('trust', 'trust', 'Trust & safety', 'inbox', 'Claims and returns from shoppers will show up here.', 'Open claims', '/disputes');
    else {
      const weeks = Math.max(1, Math.ceil(rangeDays / 7));
      const points = Array.from({ length: weeks }, (_, k) => ({ label: `Wk of ${new Date(now.getTime() - (weeks - k) * 7 * DAY).toLocaleDateString('en-US', { month: 'short', day: 'numeric' })}`, value: refunded.filter((_, j) => j % weeks === k).reduce((a, c) => a + Number(c.fields.refundMinor ?? 0), 0) / 100 }));
      ready('trust', 'trust', 'Trust & safety', {
        headline: underReview ? { value: String(underReview), label: 'claims under review', period: null, tone: 'attention' } : { value: 'None open', label: 'no claims under review', period: null, tone: 'positive' },
        chart: { kind: 'bars', label: `Claim refunds per week, last ${rangeDays} days`, valueFormat: 'money', points },
        totalLine: `${usd(refunded.reduce((a, c) => a + Number(c.fields.refundMinor ?? 0), 0))} refunded through claims`,
        actions: links(['disputes', 'Claims', '/disputes?tab=claims'], ['disputes', 'Returns', '/disputes?tab=returns'])
      });
    }
  }

  if (can('support')) {
    const open = records('support-cases').filter((c) => c.fields.status === 'OPEN').length;
    if (!open) empty('support', 'support', 'Support', 'inbox', 'Open support tickets will appear here.', 'Open support', '/support');
    else ready('support', 'support', 'Support', { headline: { value: String(open), label: 'open tickets', period: null, tone: 'attention' }, chart: null, totalLine: 'Waiting for a first answer or a resolution', actions: links(['support', 'Open tickets', '/support']) });
  }

  if (can('catalog')) {
    const cats = records('category-requests').filter((r) => r.fields.status === 'PENDING_REVIEW').length;
    const mod = records('moderation').filter((r) => r.fields.status === 'PENDING').length;
    const total = cats + mod;
    if (!total) empty('catalog', 'catalog', 'Catalog', 'listings', 'Category requests and listings to moderate will show up here.', 'Open catalog', '/moderation');
    else {
      ready('catalog', 'catalog', 'Catalog', {
        headline: { value: String(total), label: 'waiting for a decision', period: null, tone: 'attention' },
        chart: { kind: 'bars', label: 'Catalog queues waiting', valueFormat: 'count', points: [{ label: 'Category requests', value: cats }, { label: 'Moderation queue', value: mod }] },
        totalLine: 'Category requests and moderation',
        actions: links(['catalog', 'Category requests', '/moderation?tab=category-requests&category-requests.status=PENDING_REVIEW'], ['catalog', 'Moderation queue', '/moderation?tab=moderation'], ['deptRequests', 'Department requests', '/department-requests'])
      });
    }
  }

  if (can('siteCfg')) {
    const on = records('maintenance')[0]?.fields.state === 'ON';
    ready('platform', 'platform', 'Platform health', {
      headline: on ? { value: 'Maintenance on', label: 'the platform is closed to shoppers', period: null, tone: 'attention' } : { value: 'Open', label: 'no maintenance mode', period: null, tone: 'positive' },
      chart: null,
      totalLine: on ? null : 'Normal operation',
      actions: links(['siteCfg', 'Maintenance mode', '/site-content?tab=maintenance'], ['audit', 'Audit log', '/audit'])
    });
  }
  return out;
}
