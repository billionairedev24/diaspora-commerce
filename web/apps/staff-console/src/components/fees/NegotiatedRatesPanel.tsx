'use client';

import {
  formatBps,
  isApiError,
  type NegotiatedRate,
  type NegotiatedRateDraft,
  type NegotiatedRateQuery,
  type NegotiatedRateStatus,
  type NegotiatedRateType,
  parsePercent,
  type RecordTone,
  staffFeeRateKeys,
  staffFeeRateQueries,
  staffQueries
} from '@afrotransact/api-client';
import { DataTable, useTableState } from '@afrotransact/data-table';
import { Button, Dialog, Select, TextField, useToast } from '@afrotransact/ui';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { type ColumnDef } from '@tanstack/react-table';
import { useSearchParams } from 'next/navigation';
import { type FormEvent, useMemo, useState } from 'react';

import { ErrorState, problemMessage } from '@/components/ui/States';
import { StatusPill } from '@/components/ui/StatusPill';
import { type MessageKey, type Translator, useT } from '@/i18n/t';
import { staffApi } from '@/lib/api';
import { dateTime, money, shortDate } from '@/lib/format';

import { parseDollars } from './money-input';
import { OrgSearch } from './OrgSearch';

type T = Translator<MessageKey>;

export const feeRatesApi = () => staffApi().feeRates;

const TONE: Record<NegotiatedRateStatus, RecordTone> = { ACTIVE: 'good', SCHEDULED: 'info', ENDED: 'bad', EXPIRED: 'brand' };
const TYPES: NegotiatedRateType[] = ['COMMISSION', 'CONNECTION_FEE', 'FBA_FEE'];
const STATUSES: NegotiatedRateStatus[] = ['ACTIVE', 'SCHEDULED', 'ENDED', 'EXPIRED'];

export function errorsOf(e: unknown): Record<string, string> {
  return isApiError(e) && Object.keys(e.fieldErrors).length ? e.fieldErrors : { _: problemMessage(e) };
}

/** Negotiate a commission or connection fee (a reason is required; audited by payments). */
export function NegotiateRateDialog({ open, onClose, org, t }: { open: boolean; onClose: () => void; org?: { orgId: string; name: string }; t: T }) {
  const qc = useQueryClient();
  const toast = useToast();
  const fees = useQuery({ ...staffQueries.fees(staffApi()), enabled: open && !org });
  const [picked, setPicked] = useState<{ orgId: string; name: string } | null>(org ?? null);
  const [type, setType] = useState<NegotiatedRateType>('COMMISSION');
  const [category, setCategory] = useState('');
  const [rate, setRate] = useState('');
  const [startsAt, setStartsAt] = useState('');
  const [endsAt, setEndsAt] = useState('');
  const [reason, setReason] = useState('');
  const [reference, setReference] = useState('');
  const [errors, setErrors] = useState<Record<string, string>>({});
  const save = useMutation({
    mutationFn: () => {
      const isFba = type === 'FBA_FEE';
      const bps = isFba ? 0 : parsePercent(rate);
      const minor = isFba ? parseDollars(rate) : null;
      const local: Record<string, string> = {};
      if (!picked) local.orgId = t('st.rates.pickOrg');
      if (isFba ? minor === null : bps === null) local[isFba ? 'amountMinor' : 'rateBps'] = isFba ? t('st.rates.badAmount') : t('st.rates.badRate');
      if (reason.trim().length < 3) local.reason = t('st.rates.reasonRequired');
      if (Object.keys(local).length) {
        setErrors(local);
        throw new Error('invalid');
      }
      const body: NegotiatedRateDraft = {
        orgId: picked?.orgId ?? '', type, category: isFba ? null : category.trim() || null, rateBps: bps ?? 0, amountMinor: isFba ? minor : null,
        startsAt: startsAt ? new Date(startsAt).toISOString() : null, endsAt: endsAt ? new Date(endsAt).toISOString() : null,
        reason: reason.trim(), negotiatedBy: null, reference: reference.trim() || null
      };
      return feeRatesApi().createNegotiatedRate(body);
    },
    onSuccess: (r) => {
      toast.show(t('st.rates.negotiated', { org: r.orgName ?? r.orgId, rate: r.amountMinor === null ? formatBps(r.rateBps) : money(r.amountMinor) }));
      void qc.invalidateQueries({ queryKey: staffFeeRateKeys.all });
      onClose();
    },
    onError: (e) => {
      if (e instanceof Error && e.message === 'invalid') return;
      setErrors(errorsOf(e));
    }
  });
  const submit = (e: FormEvent) => {
    e.preventDefault();
    setErrors({});
    save.mutate();
  };
  return (
    <Dialog open={open} onOpenChange={(o) => !o && onClose()} title={t('st.rates.negotiateTitle')} description={t('st.rates.negotiateHelp')} width={560} closeLabel={t('st.rates.close')}>
      <form noValidate onSubmit={submit} className="flex flex-col gap-2">
        {org ? (
          <p className="m-0 text-sm"><strong>{org.name}</strong></p>
        ) : picked ? (
          <p className="m-0 flex items-center gap-2 text-sm"><strong>{picked.name}</strong><Button size="sm" variant="link" onClick={() => setPicked(null)}>{t('st.rates.change')}</Button></p>
        ) : fees.data ? (
          <OrgSearch overview={fees.data} label={t('st.rates.findOrg')} taken={() => false} onPick={(h) => setPicked({ orgId: h.orgId, name: h.name })} />
        ) : null}
        {errors.orgId ? <p role="alert" className="m-0 text-sm text-danger">{errors.orgId}</p> : null}
        <Select id="nr-type" label={t('st.rates.type')} value={type} error={errors.type} onChange={(e) => setType(e.currentTarget.value as NegotiatedRateType)}
          options={TYPES.map((x) => ({ value: x, label: t(`st.rates.type.${x}`) }))} />
        {type === 'FBA_FEE' ? null : (
          <TextField id="nr-category" label={t('st.rates.categoryOptional')} value={category} error={errors.category} onChange={(e) => setCategory(e.currentTarget.value)} />
        )}
        <TextField id="nr-rate" label={type === 'FBA_FEE' ? t('st.rates.amountDollars') : t('st.rates.ratePercent')} inputMode="decimal" value={rate} error={errors.rateBps ?? errors.amountMinor} onChange={(e) => setRate(e.currentTarget.value)} />
        <div className="grid grid-cols-[repeat(auto-fit,minmax(min(100%,200px),1fr))] gap-2">
          <TextField id="nr-start" type="datetime-local" label={t('st.rates.startsAt')} value={startsAt} error={errors.startsAt} onChange={(e) => setStartsAt(e.currentTarget.value)} />
          <TextField id="nr-end" type="datetime-local" label={t('st.rates.endsAt')} value={endsAt} error={errors.endsAt} onChange={(e) => setEndsAt(e.currentTarget.value)} />
        </div>
        <TextField id="nr-reason" label={t('st.rates.reason')} value={reason} error={errors.reason} onChange={(e) => setReason(e.currentTarget.value)} />
        <TextField id="nr-ref" label={t('st.rates.reference')} value={reference} error={errors.reference} onChange={(e) => setReference(e.currentTarget.value)} />
        {errors._ ? <p role="alert" className="m-0 text-sm text-danger">{errors._}</p> : null}
        <div className="flex justify-end gap-2">
          <Button variant="neutral" onClick={onClose}>{t('st.rates.cancel')}</Button>
          <Button type="submit" loading={save.isPending}>{t('st.rates.negotiate')}</Button>
        </div>
      </form>
    </Dialog>
  );
}

/** End a negotiated rate now, with a reason (audited). */
function EndRateDialog({ rate, onClose, t }: { rate: NegotiatedRate | null; onClose: () => void; t: T }) {
  const qc = useQueryClient();
  const toast = useToast();
  const [reason, setReason] = useState('');
  const [error, setError] = useState<string | null>(null);
  const end = useMutation({
    mutationFn: (r: NegotiatedRate) => feeRatesApi().endNegotiatedRate(r.id, reason.trim()),
    onSuccess: (r) => {
      toast.show(t('st.rates.ended', { org: r.orgName ?? r.orgId }));
      void qc.invalidateQueries({ queryKey: staffFeeRateKeys.all });
      setReason('');
      onClose();
    },
    onError: (e) => setError(errorsOf(e).reason ?? problemMessage(e))
  });
  return (
    <Dialog open={rate !== null} onOpenChange={(o) => !o && onClose()} title={t('st.rates.endTitle')} description={rate ? t('st.rates.endHelp', { org: rate.orgName ?? rate.orgId }) : ''} width={480} closeLabel={t('st.rates.close')}>
      <form noValidate className="flex flex-col gap-2" onSubmit={(e) => {
        e.preventDefault();
        if (reason.trim().length < 3) {
          setError(t('st.rates.reasonRequired'));
          return;
        }
        setError(null);
        if (rate) end.mutate(rate);
      }}>
        <TextField id="nr-end-reason" label={t('st.rates.endReason')} value={reason} error={error} onChange={(e) => setReason(e.currentTarget.value)} />
        <div className="flex justify-end gap-2">
          <Button variant="neutral" onClick={onClose}>{t('st.rates.cancel')}</Button>
          <Button type="submit" variant="dark" loading={end.isPending}>{t('st.rates.endNow')}</Button>
        </div>
      </form>
    </Dialog>
  );
}

/** Negotiated rates (server-driven): every organisation's, or one organisation's (seller billing panel). */
export function NegotiatedRatesPanel({ org }: { org?: { orgId: string; name: string } }) {
  const t = useT();
  const state = useTableState({ searchParams: useSearchParams(), prefix: org ? 'onr' : 'nr', syncUrl: !org, defaultSort: [{ id: 'createdAt', desc: true }] });
  const [type, setType] = useState<'' | NegotiatedRateType>('');
  const [status, setStatus] = useState<'' | NegotiatedRateStatus>('');
  const [creating, setCreating] = useState(false);
  const [ending, setEnding] = useState<NegotiatedRate | null>(null);
  const query: NegotiatedRateQuery = { orgId: org?.orgId, type: type || undefined, status: status || undefined, q: state.q || undefined, page: state.pagination.pageIndex, size: state.pagination.pageSize };
  const rates = useQuery(staffFeeRateQueries.negotiated(feeRatesApi(), query));
  const canConfigure = rates.data?.canConfigure ?? false;
  const columns = useMemo<ColumnDef<NegotiatedRate>[]>(() => [
    ...(org ? [] : [{ id: 'org', accessorFn: (r: NegotiatedRate) => r.orgName ?? r.orgId, header: t('st.rates.col.org'), size: 180, meta: { kind: 'strong' as const } }]),
    { id: 'type', accessorFn: (r) => r.type, header: t('st.rates.col.type'), size: 130, cell: ({ row }) => t(`st.rates.type.${row.original.type}`) },
    { id: 'category', accessorFn: (r) => r.categoryLabel, header: t('st.rates.col.category'), size: 140 },
    { id: 'rateBps', accessorFn: (r) => r.rateBps, header: t('st.rates.col.rate'), size: 90, meta: { align: 'end' }, cell: ({ row }) => (row.original.amountMinor === null ? formatBps(row.original.rateBps) : `${money(row.original.amountMinor)} / unit`) },
    { id: 'period', accessorFn: (r) => r.startsAt, header: t('st.rates.col.period'), size: 190,
      cell: ({ row }) => `${shortDate(row.original.startsAt)} – ${row.original.endsAt ? shortDate(row.original.endsAt) : t('st.rates.openEnded')}` },
    { id: 'status', accessorFn: (r) => r.status, header: t('st.rates.col.status'), size: 110, meta: { kind: 'status' },
      cell: ({ row }) => <StatusPill tone={TONE[row.original.status]}>{t(`st.rates.status.${row.original.status}`)}</StatusPill> },
    { id: 'reason', accessorFn: (r) => r.reason, header: t('st.rates.col.reason'), size: 260,
      cell: ({ row }) => (
        <span className="text-xs">
          {row.original.reason}
          <span className="block text-muted">
            {[t('st.rates.by', { who: row.original.negotiatedBy }), row.original.reference, row.original.endReason ? t('st.rates.endedBecause', { why: row.original.endReason }) : null].filter(Boolean).join(' · ')}
          </span>
        </span>
      ) },
    { id: 'createdAt', accessorFn: (r) => r.createdAt, header: t('st.rates.col.created'), size: 150, meta: { kind: 'datetime' }, cell: ({ row }) => dateTime(row.original.createdAt) },
    { id: 'actions', header: () => <span className="at-visually-hidden">{t('st.rates.col.actions')}</span>, size: 90, enableSorting: false, meta: { export: false, label: t('st.rates.col.actions') },
      cell: ({ row }) => (row.original.canEnd ? <Button size="sm" variant="link" onClick={() => setEnding(row.original)}>{t('st.rates.end')}</Button> : null) }
  ], [t, org]);
  return (
    <>
      <DataTable<NegotiatedRate> id={org ? 'staff.fees.org-negotiated-rates' : 'staff.fees.negotiated-rates'} label={t('st.rates.negotiatedTitle')} columns={columns}
        data={rates.data?.items ?? []} getRowId={(r) => r.id} rowLabel={(r) => `${r.orgName ?? r.orgId} ${r.amountMinor === null ? formatBps(r.rateBps) : money(r.amountMinor)}`} state={state} mode="server" paging="pages"
        total={rates.data?.total ?? null} loading={rates.isPending} fetching={rates.isFetching}
        error={rates.isError ? <ErrorState error={rates.error} onRetry={() => void rates.refetch()} /> : undefined}
        searchPlaceholder={t('st.rates.search')} empty={t('st.rates.none')}
        toolbar={
          <div className="flex flex-wrap at-align gap-2">
            <Select id={`${org ? 'o' : ''}nr-filter-type`} label={t('st.rates.col.type')} value={type} onChange={(e) => { setType(e.currentTarget.value as '' | NegotiatedRateType); state.resetPaging(); }}
              options={[{ value: '', label: t('st.rates.all') }, ...TYPES.map((x) => ({ value: x, label: t(`st.rates.type.${x}`) }))]} />
            <Select id={`${org ? 'o' : ''}nr-filter-status`} label={t('st.rates.col.status')} value={status} onChange={(e) => { setStatus(e.currentTarget.value as '' | NegotiatedRateStatus); state.resetPaging(); }}
              options={[{ value: '', label: t('st.rates.all') }, ...STATUSES.map((x) => ({ value: x, label: t(`st.rates.status.${x}`) }))]} />
            {canConfigure ? <Button onClick={() => setCreating(true)}>{t('st.rates.negotiateRate')}</Button> : null}
          </div>
        } />
      {creating ? <NegotiateRateDialog open={creating} onClose={() => setCreating(false)} org={org} t={t} /> : null}
      <EndRateDialog rate={ending} onClose={() => setEnding(null)} t={t} />
    </>
  );
}
