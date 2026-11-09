import { type ReactNode } from 'react';

import { Bank, CalendarCheck, CaretRight, ChartLineUp, ClipboardText, CurrencyDollar, Headset, type Icon, ListChecks, Megaphone, Package, Plus, Pulse, Receipt, Scales, ShieldCheck, Stack, Star, Storefront, Tag, Wallet } from '../icons';
import { type ChartPoint, type ChartTone, Donut, MiniBars, Sparkline } from './charts';
import { EmptyPrompt, type LinkLike } from './EmptyPrompt';
import { ModuleGrid, ModuleSection, type ModuleTone } from './ModuleSection';

/** The BFF's ModuleSummary (contract gateway-bff-seller.yaml / gateway-bff-staff.yaml), typed structurally so the kit needs no API package. */
export interface ModuleSummaryData {
  id: string;
  icon: string;
  title: string;
  state: 'ready' | 'empty' | 'unavailable';
  headline?: { value: string; label: string; period?: string | null; tone: ModuleTone } | null;
  chart?: { kind: 'sparkline' | 'bars' | 'donut'; label: string; valueFormat: 'money' | 'count'; points: readonly { label: string; value: number; tone?: ChartTone }[] } | null;
  totalLine?: string | null;
  actions: readonly { label: string; href: string }[];
  empty?: { prompt: string; art: 'listings' | 'chart' | 'calendar' | 'inbox' | 'payout' | 'star'; action: { label: string; href: string } } | null;
}

const MODULE_ICONS: Record<string, Icon> = {
  sales: CurrencyDollar, orders: Package, listings: Storefront, payouts: Wallet, inventory: Stack, reviews: Star, health: ShieldCheck, promotions: Megaphone,
  bookings: CalendarCheck, revenue: ChartLineUp, calendar: CalendarCheck, applications: ClipboardText, payments: Bank, trust: Scales, support: Headset, catalog: Tag, platform: Pulse
};

/** Icon of a quick action, from where it goes: creating things get a plus, money a wallet, lists a checklist. */
export function actionIcon(href: string, label: string): Icon {
  if (/new|add|create|upload/i.test(`${href} ${label}`)) return Plus;
  if (/payout|earning|fee/i.test(href)) return Wallet;
  if (/order|queue|status=|claims|support|tab=/i.test(href)) return ListChecks;
  if (/review/i.test(href)) return Star;
  if (/audit|fees|report|exports/i.test(href)) return Receipt;
  return CaretRight;
}

const usd = (v: number) => (Math.abs(v) >= 10_000 ? `$${Math.round(v / 1000)}k` : `$${v.toLocaleString('en-US', { maximumFractionDigits: 0 })}`);

function chartOf(m: ModuleSummaryData): ReactNode {
  const c = m.chart;
  if (!c || c.points.length === 0) return null;
  const points: ChartPoint[] = c.points.map((p) => ({ ...p }));
  const format = c.valueFormat === 'money' ? usd : undefined;
  if (c.kind === 'sparkline') return <Sparkline label={c.label} points={points} format={format} tone="ink" />;
  if (c.kind === 'bars') return <MiniBars label={c.label} points={points} format={format} tone="ink" />;
  const total = points.reduce((a, p) => a + p.value, 0);
  return <Donut label={c.label} points={points} format={format} centre={total > 0 ? total.toLocaleString('en-US') : undefined} />;
}

export interface ModuleSummariesProps {
  modules: readonly ModuleSummaryData[];
  /** Loading: shows skeleton modules. */
  loading?: boolean;
  /** Called by a module that could not be loaded (state unavailable) to try again. */
  onRetry?: () => void;
  linkAs?: LinkLike;
  /** Translated strings for the kit's own text. */
  labels?: { retry?: string; error?: string };
}

const SKELETONS = ['a', 'b', 'c', 'd'];

/** The module grid of a home page, built from the BFF's module summaries. */
export function ModuleSummaries({ modules, loading = false, onRetry, linkAs, labels }: ModuleSummariesProps) {
  if (loading) {
    return (
      <ModuleGrid>
        {SKELETONS.map((k, i) => (
          <ModuleSection key={k} title={`Module ${i + 1}`} state="loading" />
        ))}
      </ModuleGrid>
    );
  }
  return (
    <ModuleGrid>
      {modules.map((m) => {
        const Glyph = MODULE_ICONS[m.icon] ?? Package;
        return (
          <ModuleSection
            key={m.id}
            title={m.title}
            icon={<Glyph size={18} aria-hidden />}
            state={m.state === 'unavailable' ? 'error' : m.state}
            headline={m.headline ? { value: m.headline.value, label: m.headline.label, period: m.headline.period ?? undefined, tone: m.headline.tone } : undefined}
            chart={chartOf(m)}
            totalLine={m.totalLine ?? undefined}
            actions={m.actions.map((a) => {
              const A = actionIcon(a.href, a.label);
              return { label: a.label, href: a.href, icon: <A size={16} aria-hidden /> };
            })}
            empty={m.empty ? <EmptyPrompt art={m.empty.art} prompt={m.empty.prompt} action={m.empty.action} linkAs={linkAs} /> : undefined}
            errorMessage={labels?.error}
            retryLabel={labels?.retry}
            onRetry={onRetry}
            linkAs={linkAs}
          />
        );
      })}
    </ModuleGrid>
  );
}
