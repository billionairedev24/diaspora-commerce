'use client';

import { type DriverSuggestion, isApiError, type PickupAssignment, type PickupRow, staffKeys, staffQueries } from '@afrotransact/api-client';
import { DataTable, useTableState } from '@afrotransact/data-table';
import { Button, Field, fieldStyles, TextField, useToast } from '@afrotransact/ui';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { type ColumnDef } from '@tanstack/react-table';
import { useSearchParams } from 'next/navigation';
import { type FormEvent, useMemo, useState } from 'react';

import { Card } from '@/components/ui/Card';
import { Notice } from '@/components/ui/Notice';
import { ErrorState, problemMessage, Spinner } from '@/components/ui/States';
import { StatusPill } from '@/components/ui/StatusPill';
import { type MessageKey, type Translator, useT } from '@/i18n/t';
import { staffApi } from '@/lib/api';
import { dateTime, shortDate } from '@/lib/format';
import { exportDataset } from '@/lib/table-export';

const th = 'px-3 py-2 text-left text-xs font-semibold text-muted';
const td = 'px-3 py-2 align-top text-sm';

const miles = (n: number) => (Math.round(n * 10) / 10).toString();

/** Road distance when geo answered, else the straight line (geo down: fulfilment ranks by straight line). */
function distance(t: Translator<MessageKey>, d: { roadMiles: number | null; straightLineMiles: number; durationMinutes: number | null }) {
  return d.roadMiles !== null
    ? t('st.pickups.road', { miles: miles(d.roadMiles), minutes: Math.round(d.durationMinutes ?? 0) })
    : t('st.pickups.straight', { miles: miles(d.straightLineMiles) });
}

function Suggestions({ shipmentId, onAssigned }: { shipmentId: string; onAssigned: () => void }) {
  const t = useT();
  const qc = useQueryClient();
  const toast = useToast();
  const api = staffApi();
  const sug = useQuery(staffQueries.pickupSuggestions(api, shipmentId));
  const history = useQuery(staffQueries.pickupAssignments(api, shipmentId));
  const [routeId, setRouteId] = useState<string | null>(null);
  const [reason, setReason] = useState('');
  const [errors, setErrors] = useState<Record<string, string>>({});
  const assign = useMutation({
    mutationFn: (v: { routeId: string; reason: string }) => api.assignPickup(shipmentId, v),
    onSuccess: (r) => {
      toast.show(t('st.pickups.assigned', { route: r.routeName ?? r.routeId }));
      setReason('');
      setErrors({});
      void qc.invalidateQueries({ queryKey: staffKeys.pickups() });
      onAssigned();
    },
    onError: (e) => setErrors(isApiError(e) && Object.keys(e.fieldErrors).length ? e.fieldErrors : { form: problemMessage(e) })
  });
  const submit = (e: FormEvent) => {
    e.preventDefault();
    const errs: Record<string, string> = {};
    if (!routeId) errs.routeId = t('st.pickups.err.route');
    if (reason.trim().length < 5) errs.reason = t('st.pickups.err.reason');
    setErrors(errs);
    if (!Object.keys(errs).length && routeId) assign.mutate({ routeId, reason: reason.trim() });
  };
  if (sug.isPending) return <Spinner />;
  if (sug.isError) return <ErrorState error={sug.error} onRetry={() => void sug.refetch()} />;
  const drivers: DriverSuggestion[] = sug.data.drivers;
  return (
    <div className="flex flex-col gap-4">
      <Card title={t('st.pickups.suggestions', { id: shipmentId })}>
        {drivers.length ? (
          <form noValidate onSubmit={submit} className="flex flex-col gap-3" aria-label={t('st.pickups.assignForm')}>
            <table className="w-full border-collapse" aria-label={t('st.pickups.suggestionsTable')}>
              <thead>
                <tr className="border-b border-border">
                  <th className={th}>
                    <span className="at-visually-hidden">{t('st.pickups.choose')}</span>
                  </th>
                  <th className={th}>{t('st.pickups.rank')}</th>
                  <th className={th}>{t('st.pickups.driver')}</th>
                  <th className={th}>{t('st.pickups.route')}</th>
                  <th className={th}>{t('st.pickups.distance')}</th>
                  <th className={th}>{t('st.pickups.capacity')}</th>
                  <th className={th}>{t('st.pickups.position')}</th>
                </tr>
              </thead>
              <tbody>
                {drivers.map((d, i) => (
                  <tr key={d.driverId} className="border-b border-border" data-testid="driver-suggestion">
                    <td className={td}>
                      <input
                        type="radio"
                        name="pickup-route"
                        id={`route-${d.routeId}`}
                        checked={routeId === d.routeId}
                        disabled={!sug.data.canAssign}
                        onChange={() => setRouteId(d.routeId)}
                        aria-label={t('st.pickups.chooseRoute', { route: d.routeName, driver: d.driverId })}
                      />
                    </td>
                    <td className={`${td} font-semibold`}>{i + 1}</td>
                    <td className={td}>{d.driverId}</td>
                    <td className={td}>
                      {d.routeName}
                      <span className="block text-xs text-muted">
                        {d.zone} · {d.routeStatus}
                      </span>
                    </td>
                    <td className={td}>
                      {distance(t, d)}
                      <span className="block text-xs text-muted">{d.distanceMethod}</span>
                    </td>
                    <td className={td}>{t('st.pickups.capacityLeft', { left: d.capacityLeft, onRoute: d.packagesOnRoute })}</td>
                    <td className={td}>{dateTime(d.positionAt)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
            {errors.routeId ? (
              <p className="m-0 text-sm text-danger" role="alert">
                {errors.routeId}
              </p>
            ) : null}
            {sug.data.canAssign ? (
              <>
                <Field id="pickup-reason" label={t('st.pickups.reason')} error={errors.reason} help={t('st.pickups.reasonHelp')}>
                  {(a11y) => <textarea {...a11y} className={fieldStyles.control} rows={2} maxLength={500} value={reason} onChange={(e) => setReason(e.currentTarget.value)} />}
                </Field>
                {errors.form ? (
                  <Notice tone="danger" title={t('st.pickups.notAssigned')} role="alert">
                    {errors.form}
                  </Notice>
                ) : null}
                <div>
                  <Button type="submit" loading={assign.isPending}>
                    {t('st.pickups.assign')}
                  </Button>
                </div>
              </>
            ) : (
              <p className="m-0 text-xs text-muted">{t('st.pickups.viewOnly')}</p>
            )}
          </form>
        ) : (
          <Notice tone="info" title={t('st.pickups.noDrivers')}>
            {t('st.pickups.noDriversHelp')}
          </Notice>
        )}
      </Card>
      <Card title={t('st.pickups.history')}>
        {history.isPending ? <Spinner /> : null}
        {history.isError ? <p className="m-0 text-sm text-danger">{problemMessage(history.error)}</p> : null}
        {history.data ? (
          history.data.items.length ? (
            <ol aria-label={t('st.pickups.history')} className="m-0 flex list-none flex-col gap-2 p-0">
              {history.data.items.map((a: PickupAssignment) => (
                <li key={a.id} className="rounded-md border border-border p-3 text-sm" data-testid="pickup-assignment">
                  <StatusPill tone={a.mode === 'AUTO_NEAREST' ? 'info' : 'warn'}>{a.mode === 'AUTO_NEAREST' ? t('st.pickups.auto') : t('st.pickups.override')}</StatusPill>{' '}
                  <strong>{a.routeId}</strong> {a.driverId ? `· ${a.driverId}` : ''}
                  {a.roadMiles !== null || a.straightMiles !== null ? (
                    <span className="text-muted"> · {distance(t, { roadMiles: a.roadMiles, straightLineMiles: a.straightMiles ?? 0, durationMinutes: null })}</span>
                  ) : null}
                  {a.previousRouteId ? <span className="text-muted"> · {t('st.pickups.previous', { route: a.previousRouteId })}</span> : null}
                  {a.reason ? <span className="block">{t('st.pickups.because', { reason: a.reason })}</span> : null}
                  <span className="block text-xs text-muted">
                    {a.actor ?? '—'} · {dateTime(a.createdAt)}
                  </span>
                </li>
              ))}
            </ol>
          ) : (
            <p className="m-0 text-sm text-muted">{t('st.pickups.noHistory')}</p>
          )
        ) : null}
      </Card>
    </div>
  );
}

/** Seller pickups waiting for a route, the nearest drivers for one, a reasoned override and the assignment history. */
export function PickupsPanel() {
  const t = useT();
  const qc = useQueryClient();
  const state = useTableState({ searchParams: useSearchParams(), prefix: 'pickups' });
  const q = useQuery(staffQueries.pickups(staffApi()));
  const [selected, setSelected] = useState<string | null>(null);
  const [lookup, setLookup] = useState('');
  const columns = useMemo<ColumnDef<PickupRow>[]>(
    () => [
      {
        id: 'orderNumber',
        accessorFn: (p) => p.orderNumber,
        header: t('st.pickups.order'),
        size: 170,
        meta: { kind: 'mono' },
        cell: ({ row }) => (
          <span className="font-semibold">
            {row.original.orderNumber}
            <span className="block text-xs font-normal text-muted">{row.original.shipmentId}</span>
          </span>
        )
      },
      { id: 'sellerName', accessorFn: (p) => p.sellerName, header: t('st.pickups.seller'), size: 180, meta: { kind: 'strong' } },
      { id: 'dropOff', accessorFn: (p) => `${p.city}, ${p.state} ${p.postalCode}`, header: t('st.pickups.dropOff'), size: 200 },
      {
        id: 'units',
        accessorFn: (p) => p.units,
        header: t('st.pickups.units'),
        size: 100,
        meta: { kind: 'number', align: 'end' },
        cell: ({ row }) => (
          <>
            {row.original.units}
            {row.original.perishable ? <span className="block text-xs text-muted">{t('st.pickups.perishable')}</span> : null}
          </>
        )
      },
      { id: 'promisedBy', accessorFn: (p) => p.promisedBy, header: t('st.pickups.promisedBy'), size: 130, meta: { kind: 'date' }, cell: ({ row }) => shortDate(row.original.promisedBy) },
      {
        id: 'actions',
        header: () => <span className="at-visually-hidden">{t('st.pickups.actions')}</span>,
        size: 120,
        enableSorting: false,
        meta: { export: false, label: t('st.pickups.actions') },
        cell: ({ row }) => (
          <Button size="sm" variant={selected === row.original.shipmentId ? 'primary' : 'neutral'} onClick={() => setSelected(row.original.shipmentId)} aria-label={t('st.pickups.driversFor', { order: row.original.orderNumber })}>
            {t('st.pickups.drivers')}
          </Button>
        )
      }
    ],
    [t, selected]
  );
  const items: PickupRow[] = q.data?.items ?? [];
  return (
    <div className="flex flex-col gap-4">
      <p className="m-0 text-sm text-muted">{t('st.pickups.intro')}</p>
      <Card title={t('st.pickups.waiting', { n: items.length })}>
        <DataTable<PickupRow>
          id="staff.pickups"
          label={t('st.pickups.waitingTable')}
          columns={columns}
          data={items}
          getRowId={(p) => p.shipmentId}
          rowLabel={(p) => p.orderNumber}
          rowTestId="pickup-row"
          state={state}
          mode="client"
          loading={q.isPending}
          fetching={q.isFetching}
          error={q.isError ? <ErrorState error={q.error} onRetry={() => void q.refetch()} /> : undefined}
          onRowClick={(p) => setSelected(p.shipmentId)}
          activeRowId={selected}
          empty={t('st.pickups.none')}
          onExport={(req) => exportDataset(qc, req)}
          exportsHref="/exports"
        />
        <form
          className="flex flex-wrap items-end gap-2"
          onSubmit={(e) => {
            e.preventDefault();
            if (lookup.trim()) setSelected(lookup.trim());
          }}
        >
          <TextField id="pickup-lookup" className="max-w-xs" label={t('st.pickups.lookup')} help={t('st.pickups.lookupHelp')} value={lookup} onChange={(e) => setLookup(e.currentTarget.value)} />
          <Button type="submit" variant="neutral">
            {t('st.pickups.open')}
          </Button>
        </form>
      </Card>
      {selected ? <Suggestions key={selected} shipmentId={selected} onAssigned={() => void q.refetch()} /> : null}
    </div>
  );
}
