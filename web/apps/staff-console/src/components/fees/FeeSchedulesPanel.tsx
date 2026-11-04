'use client';

import { type FeeScheduleStatus, type FeeScheduleView, formatBps, type RecordTone, staffKeys, staffQueries, tariffSummary } from '@afrotransact/api-client';
import { DataTable, useTableState } from '@afrotransact/data-table';
import { Button, Dialog, Field, fieldStyles, useToast } from '@afrotransact/ui';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { type ColumnDef } from '@tanstack/react-table';
import { useSearchParams } from 'next/navigation';
import { type FormEvent, useMemo, useState } from 'react';

import { Card } from '@/components/ui/Card';
import { Notice } from '@/components/ui/Notice';
import { ErrorState, problemMessage, Spinner } from '@/components/ui/States';
import { StatusPill } from '@/components/ui/StatusPill';
import { useT } from '@/i18n/t';
import { staffApi } from '@/lib/api';
import { dateTime, money } from '@/lib/format';
import { sellerName } from '@/lib/names';
import { exportDataset } from '@/lib/table-export';

import { FeeEditor } from './FeeEditor';

const TONE: Record<FeeScheduleStatus, RecordTone> = { ACTIVE: 'good', SCHEDULED: 'info', SUPERSEDED: 'brand', CANCELLED: 'bad' };
const LABEL: Record<FeeScheduleStatus, string> = { ACTIVE: 'Active', SCHEDULED: 'Scheduled', SUPERSEDED: 'Past', CANCELLED: 'Cancelled' };

function CancelDialog({ schedule, onOpenChange }: { schedule: FeeScheduleView | null; onOpenChange: (open: boolean) => void }) {
  const qc = useQueryClient();
  const toast = useToast();
  const [reason, setReason] = useState('');
  const [error, setError] = useState<string | null>(null);
  const cancel = useMutation({
    mutationFn: (v: { id: string; reason: string }) => staffApi().cancelFeeSchedule(v.id, v.reason),
    onSuccess: (v) => {
      toast.show(`Fee schedule v${v.version} cancelled`);
      void qc.invalidateQueries({ queryKey: staffKeys.fees() });
      void qc.invalidateQueries({ queryKey: staffKeys.resource('fee-schedules') });
      setReason('');
      onOpenChange(false);
    },
    onError: (e) => setError(problemMessage(e))
  });
  const submit = (e: FormEvent) => {
    e.preventDefault();
    if (!schedule) return;
    if (reason.trim().length < 3) {
      setError('Say why the version is cancelled (3 characters or more).');
      return;
    }
    setError(null);
    cancel.mutate({ id: schedule.id, reason: reason.trim() });
  };
  return (
    <Dialog
      open={schedule !== null}
      onOpenChange={onOpenChange}
      title={`Cancel fee schedule v${schedule?.version ?? ''}`}
      description={`It would take effect ${dateTime(schedule?.effectiveFrom)}. The version stays in the history, marked cancelled, and never applies.`}
      width={480}
    >
      <form noValidate onSubmit={submit} className="flex flex-col gap-3">
        <Field id="fee-cancel-reason" label="Reason (kept in the audit trail)" error={error}>
          {(a11y) => <textarea {...a11y} className={fieldStyles.control} rows={3} maxLength={500} value={reason} onChange={(e) => setReason(e.currentTarget.value)} />}
        </Field>
        <div className="flex justify-end gap-2">
          <Button variant="neutral" onClick={() => onOpenChange(false)}>
            Keep it
          </Button>
          <Button type="submit" variant="neutral" className="text-danger" loading={cancel.isPending}>
            Cancel version
          </Button>
        </div>
      </form>
    </Dialog>
  );
}

function CurrentSchedule({ s }: { s: FeeScheduleView }) {
  const t = useT();
  const dp = s.deliveryPricing;
  return (
    <Card title={`In force: v${s.version}`} actions={<span className="text-xs text-muted">since {dateTime(s.effectiveFrom)}</span>}>
      <div className="grid grid-cols-[repeat(auto-fit,minmax(min(100%,180px),1fr))] gap-3">
        {[
          ['Default commission', formatBps(s.defaultCommissionBps)],
          ['Card fee estimate', `${formatBps(s.cardPctBps)} + ${money(s.cardFixedMinor)}`],
          ['FBA fee per unit', money(s.fbaPerUnitMinor)],
          ['Commission on refunds', s.reverseCommissionOnRefund ? 'Returned to the seller' : 'Kept']
        ].map(([label, value]) => (
          <div key={label} className="flex flex-col gap-1 rounded-md border border-border p-3">
            <span className="text-xs font-semibold text-muted">{label}</span>
            <strong className="text-lg">{value}</strong>
          </div>
        ))}
      </div>
      <div className="flex flex-wrap gap-2" aria-label="Commission by category">
        {s.categoryRates.map((c) => (
          <span key={c.category} className="rounded-pill border border-border px-3 py-1 text-sm">
            {c.label} <strong>{formatBps(c.commissionBps)}</strong>
          </span>
        ))}
      </div>
      {s.overrides.length ? (
        <p className="m-0 text-sm text-muted">
          Overrides: {s.overrides.map((o) => `${sellerName(t, o.orgName)} (${o.categoryLabel}) ${formatBps(o.commissionBps)}`).join(' · ')}
        </p>
      ) : null}
      <p className="m-0 text-sm" data-testid="current-delivery-pricing">
        {dp?.enabled
          ? t('st.delivery.current', { customer: tariffSummary(dp.customerCharge), seller: tariffSummary(dp.sellerFee), miles: dp.maxRadiusMiles })
          : t('st.delivery.currentOff')}
      </p>
    </Card>
  );
}

/** Fee schedules: the version in force, the history (active, scheduled, past, cancelled) and the editor. */
export function FeeSchedulesPanel() {
  const qc = useQueryClient();
  const q = useQuery(staffQueries.fees(staffApi()));
  const state = useTableState({ searchParams: useSearchParams(), prefix: 'versions', defaultSort: [{ id: 'version', desc: true }] });
  const [editing, setEditing] = useState(false);
  const [cancelling, setCancelling] = useState<FeeScheduleView | null>(null);
  const columns = useMemo<ColumnDef<FeeScheduleView>[]>(
    () => [
      { id: 'version', accessorFn: (s) => s.version, header: 'Version', size: 90, meta: { kind: 'number' }, cell: ({ row }) => <strong>v{row.original.version}</strong> },
      {
        id: 'status',
        accessorFn: (s) => LABEL[s.status],
        header: 'Status',
        size: 120,
        meta: { kind: 'status' },
        cell: ({ row }) => <StatusPill tone={TONE[row.original.status]}>{LABEL[row.original.status]}</StatusPill>
      },
      { id: 'effectiveFrom', accessorFn: (s) => s.effectiveFrom, header: 'Effective from', size: 160, meta: { kind: 'datetime' }, cell: ({ row }) => dateTime(row.original.effectiveFrom) },
      {
        id: 'commission',
        accessorFn: (s) => `${formatBps(s.defaultCommissionBps)} default · ${s.categoryRates.length} categories · ${s.overrides.length} overrides`,
        header: 'Commission',
        size: 260
      },
      {
        id: 'note',
        accessorFn: (s) => [s.note, s.status === 'CANCELLED' ? `Cancelled by ${s.cancelledBy ?? '—'}: ${s.cancelReason ?? ''}` : ''].filter(Boolean).join(' · ') || undefined,
        header: 'Note',
        size: 260,
        cell: ({ row }) => (
          <>
            {row.original.note ?? '—'}
            {row.original.status === 'CANCELLED' ? (
              <span className="block text-xs text-muted">
                Cancelled by {row.original.cancelledBy ?? '—'} {dateTime(row.original.cancelledAt)}: {row.original.cancelReason}
              </span>
            ) : null}
          </>
        )
      },
      {
        id: 'createdAt',
        accessorFn: (s) => s.createdAt,
        header: 'Created',
        size: 170,
        meta: { kind: 'datetime', exportValue: (s) => `${s.createdBy} · ${s.createdAt}` },
        cell: ({ row }) => (
          <>
            {row.original.createdBy}
            <span className="block text-xs text-muted">{dateTime(row.original.createdAt)}</span>
          </>
        )
      },
      {
        id: 'actions',
        header: () => <span className="at-visually-hidden">Actions</span>,
        size: 100,
        enableSorting: false,
        meta: { export: false, label: 'Actions' },
        cell: ({ row }) =>
          row.original.cancellable ? (
            <Button size="sm" variant="neutral" onClick={() => setCancelling(row.original)} aria-label={`Cancel v${row.original.version}`}>
              Cancel
            </Button>
          ) : null
      }
    ],
    []
  );
  if (q.isPending) return <Spinner />;
  if (q.isError) return <ErrorState error={q.error} onRetry={() => void q.refetch()} />;
  const o = q.data;
  if (editing) return <FeeEditor overview={o} onClose={() => setEditing(false)} />;
  return (
    <div className="flex flex-col gap-4">
      <Notice tone="info" title={`${o.retail.name}:`}>
        {o.retail.note}. Payments never charges it commission, FBA or seller fees, and overrides can’t be set for it.
      </Notice>
      {o.current ? <CurrentSchedule s={o.current} /> : <Notice tone="warning" title="No fee schedule is in force." />}
      <Card
        title="Versions"
        actions={
          o.canConfigure ? (
            <Button size="sm" onClick={() => setEditing(true)}>
              New version
            </Button>
          ) : (
            <span className="text-xs text-muted">Read-only: creating and cancelling versions needs payments:configure (Super Admin, Finance).</span>
          )
        }
      >
        <DataTable<FeeScheduleView>
          id="staff.fee-schedule-versions"
          label="Fee schedule versions"
          columns={columns}
          data={o.schedules}
          getRowId={(s) => s.id}
          rowLabel={(s) => `v${s.version}`}
          state={state}
          mode="client"
          fetching={q.isFetching}
          empty="No versions yet."
          onExport={(req) => exportDataset(qc, req)}
          exportsHref="/exports"
        />
      </Card>
      <CancelDialog schedule={cancelling} onOpenChange={(open) => !open && setCancelling(null)} />
    </div>
  );
}
