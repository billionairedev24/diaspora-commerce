'use client';

import {
  isApiError,
  type RecordTone,
  RUN_LIFECYCLE,
  type RunAdvanceStep,
  type RunDispatch,
  type RunDispatchRoute,
  type RunDispatchStop,
  staffKeys,
  staffQueries
} from '@afrotransact/api-client';
import { DataTable, useTableState } from '@afrotransact/data-table';
import { Button, useToast } from '@afrotransact/ui';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { type ColumnDef } from '@tanstack/react-table';
import { useSearchParams } from 'next/navigation';
import { useMemo, useState } from 'react';

import { Card } from '@/components/ui/Card';
import { Notice } from '@/components/ui/Notice';
import { ErrorState, problemMessage, Spinner } from '@/components/ui/States';
import { StatusPill } from '@/components/ui/StatusPill';
import { type MessageKey, type Translator, useT } from '@/i18n/t';
import { staffApi } from '@/lib/api';
import { dateTime, shortDate } from '@/lib/format';
import { driverName } from '@/lib/names';
import { exportDataset } from '@/lib/table-export';

/* Delivery runs in dispatch (S264): a closed pre-order window's paid orders delivered in one trip. fulfilment plans the
   routes (the sellers' pickups first, then the drops, split by capacity); delivery-runs moves the run itself from the
   drivers' events. Staff watch it here, plan routes again when planning could not finish, and can move a run by hand
   when the events did not arrive. */

const th = 'px-3 py-2 text-left text-xs font-semibold text-muted';
const td = 'px-3 py-2 align-top text-sm';

const LIFE_TONE: Record<string, RecordTone> = { CONFIRMED: 'info', COLLECTING: 'warn', OUT_FOR_DELIVERY: 'warn', COMPLETED: 'good', CANCELLED: 'bad' };
const STOP_TONE: Record<RunDispatchStop['status'], RecordTone> = {
  PENDING: 'info', ARRIVED: 'warn', DONE: 'good', FAILED: 'bad', RETRY: 'warn', SKIPPED: 'bad', REASSIGNED: 'info', CANCELLED: 'bad'
};

/** The step after the run's current one (null once completed or cancelled). */
export function nextStep(run: Pick<RunDispatch, 'runStatus'>): RunAdvanceStep | null {
  if (!run.runStatus || run.runStatus === 'CANCELLED') return null;
  const next = RUN_LIFECYCLE[RUN_LIFECYCLE.indexOf(run.runStatus) + 1];
  return next && next !== 'CONFIRMED' ? next : null;
}

const lifeLabel = (t: Translator<MessageKey>, s: RunDispatch['runStatus']) => (s ? t(`st.runs.life.${s}`) : t('st.runs.lifeUnknown'));

function Progress({ run }: { run: RunDispatch }) {
  const t = useT();
  const p = run.progress;
  const cells: [string, string][] = [
    [t('st.runs.pickups'), t('st.runs.of', { done: p.pickupsDone, total: p.pickupStops })],
    [t('st.runs.bagsCollected'), t('st.runs.of', { done: p.bagsCollected, total: p.bags })],
    [t('st.runs.drops'), t('st.runs.of', { done: p.dropsDone, total: p.dropStops })],
    [t('st.runs.bagsDelivered'), t('st.runs.of', { done: p.bagsDelivered, total: p.bags })],
    [t('st.runs.dropsFailed'), String(p.dropsFailed)],
    [t('st.runs.unrouted'), String(p.unrouted)]
  ];
  return (
    <dl className="m-0 grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-6" data-testid="run-progress">
      {cells.map(([label, value]) => (
        <div key={label} className="flex flex-col">
          <dt className="text-xs text-muted">{label}</dt>
          <dd className="m-0 text-md font-semibold">{value}</dd>
        </div>
      ))}
    </dl>
  );
}

function Stops({ route }: { route: RunDispatchRoute }) {
  const t = useT();
  return (
    <table className="w-full border-collapse" aria-label={t('st.runs.stopsTable', { route: route.name })}>
      <thead>
        <tr className="border-b border-border">
          <th className={th}>#</th>
          <th className={th}>{t('st.runs.kind')}</th>
          <th className={th}>{t('st.runs.stop')}</th>
          <th className={th}>{t('st.runs.order')}</th>
          <th className={th}>{t('st.runs.stopStatus')}</th>
        </tr>
      </thead>
      <tbody>
        {route.stops.map((s) => (
          <tr key={s.id} className="border-b border-border" data-testid="run-stop" data-kind={s.kind}>
            <td className={td}>{s.sequence}</td>
            <td className={td}>{s.kind === 'PICKUP' ? t('st.runs.kind.PICKUP') : t('st.runs.kind.DROPOFF')}</td>
            <td className={td}>
              {s.name}
              {s.addressLine ? <span className="block text-xs text-muted">{s.addressLine}</span> : null}
              {s.note ? <span className="block text-xs text-muted">{s.note}</span> : null}
            </td>
            <td className={td}>
              {s.orderNumber ?? '—'}
              {s.handoverRequired ? <span className="block text-xs text-muted">{t('st.runs.codeNeeded')}</span> : null}
            </td>
            <td className={td}>
              <StatusPill tone={STOP_TONE[s.status]}>{t(`st.runs.stopStatus.${s.status}`)}</StatusPill>
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

function RunDetail({ runId }: { runId: string }) {
  const t = useT();
  const qc = useQueryClient();
  const toast = useToast();
  const api = staffApi();
  const q = useQuery(staffQueries.run(api, runId));
  const [confirming, setConfirming] = useState<RunAdvanceStep | null>(null);
  const [problem, setProblem] = useState<string | null>(null);
  const done = (run: RunDispatch) => {
    qc.setQueryData(staffKeys.run(runId), run);
    void qc.invalidateQueries({ queryKey: staffKeys.runList('') });
    void qc.invalidateQueries({ queryKey: staffKeys.runs() });
  };
  const plan = useMutation({
    mutationFn: () => api.planRun(runId),
    onSuccess: (run) => {
      setProblem(null);
      toast.show(t('st.runs.planned'));
      done(run);
    },
    onError: (e) => setProblem(problemMessage(e))
  });
  const advance = useMutation({
    mutationFn: (to: RunAdvanceStep) => api.advanceRun(runId, { to }),
    onSuccess: (run) => {
      setProblem(null);
      setConfirming(null);
      toast.show(t('st.runs.moved', { status: lifeLabel(t, run.runStatus) }));
      done(run);
    },
    onError: (e) => {
      setConfirming(null);
      setProblem(isApiError(e) && e.status === 409 ? t('st.runs.err.order') : problemMessage(e));
    }
  });
  if (q.isPending) return <Spinner />;
  if (q.isError) return <ErrorState error={q.error} onRetry={() => void q.refetch()} />;
  const run = q.data;
  const next = nextStep(run);
  return (
    <div className="flex flex-col gap-4" data-testid="run-detail">
      <Card
        title={
          <>
            {t('st.runs.title', { town: run.town, state: run.state })} <StatusPill tone={run.runStatus ? (LIFE_TONE[run.runStatus] ?? 'info') : 'info'}>{lifeLabel(t, run.runStatus)}</StatusPill>
          </>
        }
        actions={
          run.canManage && run.status !== 'CANCELLED' ? (
            <Button variant="neutral" loading={plan.isPending} onClick={() => plan.mutate()}>
              {run.routes.length ? t('st.runs.planAgain') : t('st.runs.plan')}
            </Button>
          ) : null
        }
      >
        <p className="m-0 text-sm text-muted">
          {t('st.runs.window', { start: dateTime(run.windowStart), end: dateTime(run.windowEnd) })}
          {' · '}
          {run.sellerPickupAt ? t('st.runs.sellerPickup', { when: dateTime(run.sellerPickupAt) }) : t('st.runs.noSellerPickup')}
          {' · '}
          {run.dropMode === 'PICKUP_POINT' ? t('st.runs.drop.PICKUP_POINT') : t('st.runs.drop.DOORSTEP')}
        </p>
        {run.pickupPoint ? (
          <p className="m-0 text-sm" data-testid="run-pickup-point">
            <strong>{run.pickupPoint.name}</strong>, {run.pickupPoint.line1}, {run.pickupPoint.city}, {run.pickupPoint.state} {run.pickupPoint.postalCode}
            {run.pickupPoint.hours ? ` · ${run.pickupPoint.hours}` : ''}
          </p>
        ) : null}
        {run.planError ? (
          <Notice tone="warning" title={t('st.runs.planError')}>
            {run.planError}
          </Notice>
        ) : null}
        {problem ? (
          <Notice tone="danger" title={t('st.runs.notDone')} role="alert">
            {problem}
          </Notice>
        ) : null}
        <Progress run={run} />
        {run.orders ? (
          <p className="m-0 text-sm" data-testid="run-orders">
            {t('st.runs.orderStages', {
              orders: run.orders.orders,
              pending: run.orders.pending,
              collected: run.orders.collected,
              out: run.orders.outForDelivery,
              delivered: run.orders.delivered,
              failed: run.orders.failed,
              returned: run.orders.returned
            })}
            {run.orders.lastDeliveredAt ? <span className="text-muted"> · {t('st.runs.lastDelivered', { when: dateTime(run.orders.lastDeliveredAt) })}</span> : null}
          </p>
        ) : (
          <p className="m-0 text-sm text-muted">{t('st.runs.noStages')}</p>
        )}
      </Card>
      {run.routes.length ? (
        run.routes.map((r) => (
          <Card
            key={r.id}
            title={
              <>
                {r.name} <StatusPill tone={r.blocked ? 'bad' : 'info'}>{r.blocked ? t('st.runs.blocked') : r.status}</StatusPill>
              </>
            }
          >
            <p className="m-0 text-sm text-muted">
              {r.driverId ? t('st.runs.driver', { driver: driverName(t, r.driverName) }) : t('st.runs.noDriver')}
              {' · '}
              {t('st.runs.routeCounts', { pickups: `${r.pickupsDone}/${r.pickupStops}`, drops: `${r.dropsDone}/${r.dropStops}`, packages: r.packages })}
            </p>
            <Stops route={r} />
          </Card>
        ))
      ) : (
        <Notice tone="info" title={t('st.runs.noRoutes')}>
          {t('st.runs.noRoutesHelp')}
        </Notice>
      )}
      {run.canManage && next ? (
        <Card title={t('st.runs.override')}>
          <p className="m-0 text-sm text-muted">{t('st.runs.overrideHelp')}</p>
          {confirming ? (
            <Notice
              tone="warning"
              title={t('st.runs.confirmTitle', { status: lifeLabel(t, confirming) })}
              action={
                <div className="flex gap-2">
                  <Button loading={advance.isPending} onClick={() => advance.mutate(confirming)}>
                    {t('st.runs.confirm')}
                  </Button>
                  <Button variant="neutral" onClick={() => setConfirming(null)}>
                    {t('st.runs.cancel')}
                  </Button>
                </div>
              }
            >
              {t('st.runs.confirmHelp')}
            </Notice>
          ) : (
            <div>
              <Button variant="neutral" onClick={() => setConfirming(next)}>
                {t('st.runs.moveTo', { status: lifeLabel(t, next) })}
              </Button>
            </div>
          )}
        </Card>
      ) : null}
    </div>
  );
}

/** The delivery runs of closed pre-order windows with their routes, stops and progress. */
export function RunsPanel() {
  const t = useT();
  const qc = useQueryClient();
  const state = useTableState({ searchParams: useSearchParams(), prefix: 'runs' });
  const q = useQuery(staffQueries.runs(staffApi()));
  const [selected, setSelected] = useState<string | null>(null);
  const columns = useMemo<ColumnDef<RunDispatch>[]>(
    () => [
      {
        id: 'town',
        accessorFn: (r) => `${r.town}, ${r.state}`,
        header: t('st.runs.colRun'),
        size: 190,
        meta: { kind: 'strong' },
        cell: ({ row }) => (
          <span className="font-semibold">
            {row.original.town}, {row.original.state}
            <span className="block text-xs font-normal text-muted">{shortDate(row.original.serviceDate)}</span>
          </span>
        )
      },
      {
        id: 'lifecycle',
        accessorFn: (r) => r.runStatus ?? '',
        header: t('st.runs.colStatus'),
        size: 170,
        cell: ({ row }) => (
          <>
            <StatusPill tone={row.original.runStatus ? (LIFE_TONE[row.original.runStatus] ?? 'info') : 'info'}>{lifeLabel(t, row.original.runStatus)}</StatusPill>
            {row.original.status === 'PENDING' ? <span className="block text-xs text-warning">{t('st.runs.notPlanned')}</span> : null}
          </>
        )
      },
      { id: 'drop', accessorFn: (r) => r.dropMode, header: t('st.runs.colDrop'), size: 150, cell: ({ row }) => t(`st.runs.drop.${row.original.dropMode}`) },
      {
        id: 'orders',
        accessorFn: (r) => r.progress.orders,
        header: t('st.runs.colOrders'),
        size: 120,
        meta: { kind: 'number', align: 'end' },
        cell: ({ row }) => (
          <>
            {row.original.progress.orders}
            <span className="block text-xs text-muted">{t('st.runs.delivered', { done: row.original.progress.bagsDelivered, total: row.original.progress.bags })}</span>
          </>
        )
      },
      { id: 'routes', accessorFn: (r) => r.progress.pickupStops + r.progress.dropStops, header: t('st.runs.colStops'), size: 100, meta: { kind: 'number', align: 'end' } },
      { id: 'pickup', accessorFn: (r) => r.sellerPickupAt, header: t('st.runs.colPickup'), size: 170, meta: { kind: 'date' }, cell: ({ row }) => (row.original.sellerPickupAt ? dateTime(row.original.sellerPickupAt) : '—') },
      {
        id: 'actions',
        header: () => <span className="at-visually-hidden">{t('st.runs.actions')}</span>,
        size: 110,
        enableSorting: false,
        meta: { export: false, label: t('st.runs.actions') },
        cell: ({ row }) => (
          <Button size="sm" variant={selected === row.original.runId ? 'primary' : 'neutral'} onClick={() => setSelected(row.original.runId)} aria-label={t('st.runs.openRun', { town: row.original.town })}>
            {t('st.runs.open')}
          </Button>
        )
      }
    ],
    [t, selected]
  );
  return (
    <div className="flex flex-col gap-4">
      <p className="m-0 text-sm text-muted">{t('st.runs.intro')}</p>
      <Card title={t('st.runs.listTitle', { n: q.data?.items.length ?? 0 })}>
        <DataTable<RunDispatch>
          id="staff.runs"
          label={t('st.runs.listTable')}
          columns={columns}
          data={q.data?.items ?? []}
          getRowId={(r) => r.runId}
          rowLabel={(r) => `${r.town}, ${r.state}`}
          rowTestId="run-row"
          state={state}
          mode="client"
          loading={q.isPending}
          fetching={q.isFetching}
          error={q.isError ? <ErrorState error={q.error} onRetry={() => void q.refetch()} /> : undefined}
          onRowClick={(r) => setSelected(r.runId)}
          activeRowId={selected}
          empty={t('st.runs.none')}
          onExport={(req) => exportDataset(qc, req)}
          exportsHref="/exports"
        />
      </Card>
      {selected ? <RunDetail key={selected} runId={selected} /> : null}
    </div>
  );
}
