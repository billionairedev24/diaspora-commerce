'use client';

import { staffPreorderKeys, staffPreorderQueries } from '@afrotransact/api-client';
import { PREORDER_STATUS_LABEL, type PreorderExportKind, type PreorderSummary, type PreorderWindow } from '@afrotransact/api-client/staff-preorders';
import { Menu, MenuItem, useToast } from '@afrotransact/ui';
import { CaretDown, DownloadSimple, FileCsv, Headset, ListChecks, MapPin, Megaphone, Pause, PencilSimple, Play } from '@phosphor-icons/react/ssr';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import Link from 'next/link';
import { usePathname, useRouter, useSearchParams } from 'next/navigation';
import { useState } from 'react';

import { ErrorState, problemMessage, Spinner } from '@/components/ui/States';

import { CreativesPanel } from './CreativesPanel';
import { LateAddDialog } from './LateAddDialog';
import { fD, fDT, fS, href, money2, num, STATUS_PILL, wholeMoney } from './model';
import { ByDayChart, channelRows, HBars } from './PreorderCharts';
import { PreorderOrders } from './PreorderOrders';
import { outlineButton, Pill, smallButton, usePreorderAccess,usePreordersApi } from './primitives';
import { switchText, WindowSwitchToggle } from './WindowSwitch';

type DetailTab = 'overview' | 'orders' | 'creatives' | 'analytics';
const TABS: DetailTab[] = ['overview', 'orders', 'creatives', 'analytics'];
const DAY = 86_400_000;

function Kpis({ s, w }: { s: PreorderSummary; w: PreorderWindow }) {
  const k = s.kpis;
  const tiles = [
    { label: 'Pre-orders', value: num(k.preorders), sub: `${k.cancelled} cancelled` },
    { label: 'Customers', value: num(k.customers), sub: 'unique' },
    { label: 'Units', value: num(k.units), sub: 'to pick' },
    { label: 'Revenue', value: wholeMoney(k.revenue, w.currency), sub: 'incl. delivery' },
    { label: 'Avg order', value: k.avgOrder === null ? '–' : money2(k.avgOrder, w.currency), sub: '' },
    { label: 'Late adds', value: num(k.lateAdds), sub: 'by customer service' }
  ];
  return (
    <div className="grid grid-cols-[repeat(auto-fit,minmax(140px,1fr))] gap-3">
      {tiles.map((t) => (
        <div key={t.label} className="flex flex-col gap-0.5 rounded-md border border-border px-4 py-3" data-testid={`kpi-${t.label}`}>
          <span className="text-xs text-subtle">{t.label}</span>
          <strong className="text-2xl font-black">{t.value}</strong>
          <span className="text-2xs text-muted">{t.sub}</span>
        </div>
      ))}
    </div>
  );
}

function TimeProgress({ w }: { w: PreorderWindow }) {
  const [now] = useState(() => Date.now());
  const opens = Date.parse(w.opensAt);
  const closes = Date.parse(w.closesAt);
  const pct = Math.max(0, Math.min(100, ((now - opens) / (closes - opens)) * 100));
  const text = w.status === 'OPEN' ? `${Math.ceil((closes - now) / DAY)} days left` : PREORDER_STATUS_LABEL[w.status];
  return (
    <div className="flex flex-col gap-2">
      <div className="flex justify-between text-xs text-muted">
        <span>Opens {fS(w.opensAt)}</span>
        <span data-testid="progress-text">{text}</span>
        <span>Closes {fS(w.closesAt)}</span>
      </div>
      <span role="progressbar" aria-label="Time through the order window" aria-valuenow={Math.round(pct)} aria-valuemin={0} aria-valuemax={100} className="block h-2.5 overflow-hidden rounded-pill bg-ink-100">
        <span className="block h-full bg-ink-900" style={{ width: `${pct}%` }} />
      </span>
    </div>
  );
}

function Analytics({ s, w, canExport, onExport }: { s: PreorderSummary; w: PreorderWindow; canExport: boolean; onExport: () => void }) {
  const paid = s.fulfilmentSplit.door + s.fulfilmentSplit.pickup;
  const pct = (n: number) => (paid ? Math.round((n / paid) * 100) : 0);
  return (
    <>
      <div className="grid grid-cols-[repeat(auto-fit,minmax(min(100%,320px),1fr))] gap-5">
        <HBars title="Top products (units)" color="gold" labelWidth={150} rows={s.topProducts.map((p) => ({ k: p.name, n: p.units }))} />
        <HBars
          title="Fulfilment split"
          color="ink"
          labelWidth={150}
          rows={paid ? [{ k: 'Door', n: pct(s.fulfilmentSplit.door) }, { k: 'Pickup', n: pct(s.fulfilmentSplit.pickup) }] : []}
          valueText={(n) => `${n}%`}
        />
      </div>
      <div className="flex flex-col gap-2">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <strong className="text-sm">Advertorial performance</strong>
          {canExport ? (
            <button type="button" onClick={onExport} className={smallButton}>
              Export report (.csv)
            </button>
          ) : null}
        </div>
        <div className="overflow-auto rounded-md border border-border">
          <table className="w-full min-w-[640px] border-collapse text-sm" aria-label="Advertorial performance">
            <thead className="bg-surface-muted text-left">
              <tr className="text-2xs font-bold tracking-[0.06em] text-muted uppercase">
                <th scope="col" className="px-3 py-2">Placement</th>
                <th scope="col" className="px-3 py-2">Creative</th>
                <th scope="col" className="px-3 py-2 text-right">Impressions</th>
                <th scope="col" className="px-3 py-2 text-right">Clicks</th>
                <th scope="col" className="px-3 py-2 text-right">CTR</th>
                <th scope="col" className="px-3 py-2 text-right">Pre-orders</th>
              </tr>
            </thead>
            <tbody>
              {s.placements.map((p) => (
                <tr key={p.key} className="border-t border-border">
                  <td className="px-3 py-2">{p.name}</td>
                  <td className={`px-3 py-2 text-xs ${p.creative ? 'text-green-700' : 'text-red-600'}`}>{p.creative ? 'Uploaded' : 'Missing'}</td>
                  <td className="px-3 py-2 text-right">{num(p.impressions)}</td>
                  <td className="px-3 py-2 text-right">{num(p.clicks)}</td>
                  <td className="px-3 py-2 text-right">{p.ctr === null ? '–' : `${p.ctr.toFixed(1)}%`}</td>
                  <td className="px-3 py-2 text-right font-bold">{p.orders}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <span className="text-xs text-subtle">{`Every order is tagged with its channel (?src=…&po=${w.code}), so these update in real time.`}</span>
      </div>
    </>
  );
}

function SocialPreview({ w }: { w: PreorderWindow }) {
  const story = w.creatives.find((c) => c.slot === 'STORY');
  return (
    <div className="flex flex-col gap-2">
      <strong className="text-sm">Social preview</strong>
      <div className="relative aspect-[9/16] w-60 overflow-hidden rounded-lg bg-ink-1000 text-ink-0 shadow-lg">
        {story ? <div role="img" aria-label="Story creative" className="absolute inset-0 bg-cover bg-center bg-no-repeat" style={{ backgroundImage: `url("${story.url}")` }} /> : null}
        <div className="absolute inset-x-0 bottom-0 flex flex-col gap-2 bg-gradient-to-t from-ink-1000/85 to-transparent p-4">
          <span className="text-2xs text-ink-300">Sponsored · AfroTransact</span>
          <strong className="text-md leading-tight">{w.ribbon}</strong>
          <span className="text-xs text-ink-200">{`Order by ${fS(w.closesAt)} · delivered ${fS(w.deliversOn)}`}</span>
          <span className="grid h-8 place-items-center rounded-pill bg-gold-500 text-xs font-bold text-ink-1000">Pre-order now</span>
        </div>
      </div>
    </div>
  );
}

/** One pre-order: header (status, where, dates, Pause/Resume, Edit, Late add, Export) and the four tabs. */
export function PreorderDetail({ id }: { id: string }) {
  const api = usePreordersApi();
  const access = usePreorderAccess();
  const qc = useQueryClient();
  const toast = useToast();
  const router = useRouter();
  const pathname = usePathname();
  const params = useSearchParams();
  const raw = params.get('tab') ?? 'overview';
  const tab: DetailTab = (TABS as string[]).includes(raw) ? (raw as DetailTab) : 'overview';
  const [lateOpen, setLateOpen] = useState(false);
  const [ordersKey, setOrdersKey] = useState(0);
  const query = useQuery(staffPreorderQueries.window(api, id));
  const summary = useQuery(staffPreorderQueries.summary(api, id));
  /** Pause / Resume = this pre-order's own switch (PATCH {active}); the global switch is never touched. */
  const pause = useMutation({
    mutationFn: (w: PreorderWindow) => api.setActive(w.id, !w.active, w.version),
    onSuccess: (w) => {
      qc.setQueryData(staffPreorderKeys.window(w.id), w);
      void qc.invalidateQueries({ queryKey: staffPreorderKeys.lists() });
      toast.show(w.active ? `${w.name} resumed` : `${w.name} paused`);
    },
    onError: (e) => {
      void qc.invalidateQueries({ queryKey: staffPreorderKeys.window(id) });
      toast.show(problemMessage(e), 'error');
    }
  });
  const exportCsv = async (kind: PreorderExportKind) => {
    try {
      const r = await api.downloadCsv(id, kind);
      toast.show(`Exported ${r.rows} rows · ${r.fileName}`);
    } catch (e) {
      toast.show(problemMessage(e), 'error');
    }
  };
  if (query.isPending) return <Spinner />;
  if (query.isError) return <ErrorState error={query.error} onRetry={() => void query.refetch()} />;
  const w = query.data;
  const s = summary.data;
  const lateAddable = w.status === 'OPEN' || w.status === 'CLOSED';
  const tabHref = (t: DetailTab) => `${pathname}${t === 'overview' ? '' : `?tab=${t}`}`;
  const labels: Record<DetailTab, string> = { overview: 'Overview', orders: `Orders (${s ? s.ordersTotal : '…'})`, creatives: 'Creatives', analytics: 'Analytics' };
  return (
    <div className="flex flex-col">
      <div className="flex flex-wrap items-start gap-3 border-b border-border p-5">
        <div className="flex min-w-0 flex-col gap-1">
          <span className="flex flex-wrap items-center gap-2">
            <h2 className="m-0 text-2xl font-black">{w.name}</h2>
            <Pill className={STATUS_PILL[w.status]} testId="detail-status">
              {PREORDER_STATUS_LABEL[w.status]}
            </Pill>
            {w.switchState === 'GLOBAL_OFF' || w.switchState === 'AREA_OFF' ? (
              <Pill className="bg-red-50 text-red-700" testId="detail-switch-held">
                {switchText(w)}
              </Pill>
            ) : null}
            {w.switchable ? <WindowSwitchToggle w={w} size="md" className="ml-1" /> : null}
          </span>
          <span className="text-sm text-muted">
            <MapPin aria-hidden className="inline align-[-2px]" /> {`${w.where} · Opens ${fDT(w.opensAt)} · Closes ${fDT(w.closesAt)} · Delivers ${fD(w.deliversOn)}`}
          </span>
        </div>
        <div className="ml-auto flex flex-wrap gap-2">
          {access.canLate ? (
            <button
              type="button"
              onClick={() => setLateOpen(true)}
              disabled={!lateAddable}
              title={lateAddable ? 'For customers who call or chat after the closing date' : 'Late adds are possible while a pre-order is open or closed and delivering'}
              className={`${outlineButton} bg-ink-0`}
            >
              <Headset size={16} aria-hidden />
              Late add
            </button>
          ) : null}
          {access.canEdit ? (
            <>
              {!w.draft ? (
                <button type="button" onClick={() => pause.mutate(w)} disabled={pause.isPending} className={`${outlineButton} bg-ink-0`}>
                  {w.active ? <Pause size={16} aria-hidden /> : <Play size={16} aria-hidden />}
                  {w.active ? 'Pause' : 'Resume'}
                </button>
              ) : null}
              <Link href={href.edit(w.id)} className={`${outlineButton} bg-ink-0 no-underline hover:text-text hover:no-underline`}>
                <PencilSimple size={16} aria-hidden />
                Edit
              </Link>
            </>
          ) : null}
          {access.canExport ? (
            <Menu
              align="end"
              width={250}
              label="Export"
              trigger={
                <button type="button" className="flex h-10 cursor-pointer items-center gap-2 rounded-md border-0 bg-ink-1000 px-3 text-sm font-bold text-ink-0">
                  <DownloadSimple size={16} aria-hidden />
                  Export orders
                  <CaretDown size={12} aria-hidden />
                </button>
              }
            >
              <MenuItem icon={<FileCsv size={16} aria-hidden />} onSelect={() => void exportCsv('orders')}>
                All orders (.csv)
              </MenuItem>
              <MenuItem icon={<ListChecks size={16} aria-hidden />} onSelect={() => void exportCsv('picking-list')}>
                Picking list (.csv)
              </MenuItem>
              <MenuItem icon={<Megaphone size={16} aria-hidden />} onSelect={() => void exportCsv('advertorial-report')}>
                Advertorial report (.csv)
              </MenuItem>
            </Menu>
          ) : null}
        </div>
      </div>
      <div role="tablist" aria-label="Pre-order" className="flex flex-wrap gap-1 border-b border-border px-5">
        {TABS.map((t) => (
          <Link
            key={t}
            role="tab"
            aria-selected={tab === t}
            href={tabHref(t)}
            replace
            scroll={false}
            className={`border-b-2 px-3 py-3 text-sm font-bold no-underline hover:no-underline ${tab === t ? 'border-ink-900 text-ink-900 hover:text-ink-900' : 'border-transparent text-muted hover:text-ink-900'}`}
          >
            {labels[t]}
          </Link>
        ))}
      </div>
      <div role="tabpanel" aria-label={labels[tab]} className="flex flex-col gap-5 p-5">
        {tab === 'overview' || tab === 'analytics' ? (
          summary.isError ? (
            <ErrorState error={summary.error} onRetry={() => void summary.refetch()} />
          ) : !s ? (
            <Spinner />
          ) : (
            <>
              {tab === 'overview' ? (
                <>
                  <Kpis s={s} w={w} />
                  <TimeProgress w={w} />
                </>
              ) : null}
              <div className="grid grid-cols-[repeat(auto-fit,minmax(min(100%,320px),1fr))] gap-5">
                <ByDayChart days={s.byDay} />
                <HBars title="By channel" color="green" rows={channelRows(s)} />
              </div>
              {tab === 'analytics' ? <Analytics s={s} w={w} canExport={access.canExport} onExport={() => void exportCsv('advertorial-report')} /> : null}
            </>
          )
        ) : null}
        {tab === 'orders' ? <PreorderOrders key={ordersKey} w={w} canExport={access.canExport} /> : null}
        {tab === 'creatives' ? (
          <>
            <CreativesPanel w={w} canEdit={access.canEdit} />
            <div className="grid grid-cols-[repeat(auto-fit,minmax(min(100%,300px),1fr))] items-start gap-5">
              <SocialPreview w={w} />
            </div>
          </>
        ) : null}
      </div>
      {access.canLate ? (
        <LateAddDialog
          w={w}
          open={lateOpen}
          onOpenChange={setLateOpen}
          onAdded={() => {
            setOrdersKey((k) => k + 1);
            void qc.invalidateQueries({ queryKey: staffPreorderKeys.window(w.id) });
            router.replace(`${pathname}?tab=orders`, { scroll: false });
          }}
        />
      ) : null}
    </div>
  );
}
