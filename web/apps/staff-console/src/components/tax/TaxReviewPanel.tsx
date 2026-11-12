'use client';

import { staffTaxQueries, TAX_AUDIT_SAMPLE, type TaxReviewRow } from '@afrotransact/api-client';
import { DataTable, rowActionsColumn, useTableState } from '@afrotransact/data-table';
import { ChipGroup, Select } from '@afrotransact/ui';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { type ColumnDef } from '@tanstack/react-table';
import { usePathname, useRouter, useSearchParams } from 'next/navigation';
import { useMemo } from 'react';

import { ErrorState } from '@/components/ui/States';
import { useT } from '@/i18n/t';
import { staffApi } from '@/lib/api';
import { shortDate } from '@/lib/format';

import { RecordPill } from './shared';
import { TaxGateBanner, useLiveWithoutDecisionCount } from './TaxGateBanner';
import { TaxGateList } from './TaxGateList';
import { TaxReviewSheet } from './TaxReviewSheet';

type View = 'queue' | 'audit' | 'decisions' | 'gate';
const DECISION_STATUSES = [
  { value: 'APPROVED', label: 'Approved' },
  { value: 'REJECTED', label: 'Sent back to the seller' },
  { value: 'STALE', label: 'Needs a new review' }
];

/**
 * Tax review: the queue of product variants whose tax classification waits for a person, and the decisions already made
 * (who decided, when, under which rule version). Vendor supplies the facts, ADMIN TAX REVIEW approves, then it can publish.
 */
export function TaxReviewPanel() {
  const api = staffApi();
  const router = useRouter();
  const pathname = usePathname();
  const params = useSearchParams();
  const t = useT();
  const gateCount = useLiveWithoutDecisionCount();
  const requested = params.get('review.view');
  const view: View = requested === 'decisions' ? 'decisions' : requested === 'audit' ? 'audit' : requested === 'gate' ? 'gate' : 'queue';
  const decided = view === 'decisions';
  const audit = view === 'audit';
  const gate = view === 'gate';
  const status = decided ? (params.get('review.status') ?? 'APPROVED') : audit ? TAX_AUDIT_SAMPLE : 'PENDING_REVIEW';
  const openAtin = params.get('variant');
  const action = params.get('do');
  const state = useTableState({ searchParams: params, prefix: `review-${view}` });

  const go = (change: (p: URLSearchParams) => void) => {
    const next = new URLSearchParams(params.toString());
    change(next);
    const qs = next.toString();
    router.replace(qs ? `${pathname}?${qs}` : pathname, { scroll: false });
  };
  const open = (atin: string, doAction?: string) =>
    go((p) => {
      p.set('variant', atin);
      if (doAction) p.set('do', doAction);
      else p.delete('do');
    });

  const query = useQuery({ ...staffTaxQueries.reviews(api.tax, { status, q: state.q || undefined, page: state.pagination.pageIndex, size: state.pagination.pageSize }), placeholderData: keepPreviousData, enabled: !gate });
  const data = query.data;

  const columns = useMemo<ColumnDef<TaxReviewRow>[]>(
    () => [
      { id: 'title', header: 'Product', size: 240, enableSorting: false, accessorFn: (r) => r.title, cell: ({ row }) => <strong className="font-semibold">{row.original.title}</strong>, meta: { label: 'Product', kind: 'text' } },
      { id: 'sellers', header: 'Seller', size: 190, enableSorting: false, accessorFn: (r) => r.sellers.join(', '), meta: { label: 'Seller', kind: 'text' } },
      { id: 'categoryName', header: 'Category', size: 170, enableSorting: false, accessorFn: (r) => r.categoryName, meta: { label: 'Category', kind: 'text' } },
      ...(decided
        ? ([
            { id: 'status', header: 'Decision', size: 190, enableSorting: false, accessorFn: (r) => r.statusLabel, cell: ({ row }) => <RecordPill status={row.original.status} label={row.original.statusLabel} />, meta: { label: 'Decision', kind: 'status' } },
            { id: 'reviewedByName', header: 'Decided by', size: 160, enableSorting: false, accessorFn: (r) => r.reviewedByName, cell: ({ row }) => row.original.reviewedByName || '—', meta: { label: 'Decided by', kind: 'text' } },
            { id: 'reviewedAt', header: 'When', size: 120, enableSorting: false, accessorFn: (r) => r.reviewedAt, cell: ({ row }) => (row.original.reviewedAt ? shortDate(row.original.reviewedAt) : '—'), meta: { label: 'When', kind: 'text' } },
            { id: 'ruleVersion', header: 'Rule version', size: 190, enableSorting: false, accessorFn: (r) => r.ruleVersion, cell: ({ row }) => <span className="font-mono text-xs">{row.original.ruleVersion}</span>, meta: { label: 'Rule version', kind: 'text' } }
          ] satisfies ColumnDef<TaxReviewRow>[])
        : audit
          ? ([
              { id: 'proposed', header: 'Tax in force', size: 190, enableSorting: false, accessorFn: (r) => r.proposed.outcomeLabel || r.proposed.statusLabel, meta: { label: 'Tax in force', kind: 'text' } },
              { id: 'reviewedByName', header: 'Approved by', size: 230, enableSorting: false, accessorFn: (r) => r.reviewedByName, cell: ({ row }) => row.original.reviewedByName || '—', meta: { label: 'Approved by', kind: 'text' } },
              { id: 'reviewedAt', header: 'Approved', size: 120, enableSorting: false, accessorFn: (r) => r.reviewedAt, cell: ({ row }) => (row.original.reviewedAt ? shortDate(row.original.reviewedAt) : '—'), meta: { label: 'Approved', kind: 'text' } },
              { id: 'why', header: 'What to do', size: 330, enableSorting: false, accessorFn: (r) => r.why, meta: { label: 'What to do', kind: 'text' } }
            ] satisfies ColumnDef<TaxReviewRow>[])
        : ([
            { id: 'proposed', header: 'Proposed tax', size: 190, enableSorting: false, accessorFn: (r) => r.proposed.outcomeLabel || r.proposed.statusLabel, meta: { label: 'Proposed tax', kind: 'text' } },
            { id: 'why', header: 'Why it needs review', size: 330, enableSorting: false, accessorFn: (r) => r.why, meta: { label: 'Why it needs review', kind: 'text' } },
            { id: 'classifiedAt', header: 'Submitted', size: 120, enableSorting: false, accessorFn: (r) => r.classifiedAt, cell: ({ row }) => (row.original.classifiedAt ? shortDate(row.original.classifiedAt) : '—'), meta: { label: 'Submitted', kind: 'text' } }
          ] satisfies ColumnDef<TaxReviewRow>[])),
      rowActionsColumn<TaxReviewRow>({
        header: 'Actions',
        name: (r) => r.title,
        items: (r) => {
          const decidable = data?.canDecide && (r.status === 'PENDING_REVIEW' || r.status === 'STALE');
          const checkable = data?.canDecide && r.auditSample;
          return [
            { label: 'Open', onSelect: () => open(r.atin) },
            checkable ? { label: 'Confirm…', onSelect: () => open(r.atin, 'confirm') } : null,
            checkable ? { label: 'Move to another category…', onSelect: () => open(r.atin, 'reclassify') } : null,
            decidable ? { label: 'Approve…', onSelect: () => open(r.atin, 'approve') } : null,
            decidable ? { label: 'Move to another category…', onSelect: () => open(r.atin, 'reclassify') } : null,
            decidable ? { label: 'Ask the seller for more information…', onSelect: () => open(r.atin, 'info') } : null
          ];
        }
      })
    ],
    // eslint-disable-next-line react-hooks/exhaustive-deps -- open only reads the current URL when a menu entry is chosen
    [decided, audit, data?.canDecide]
  );

  const showGate = () =>
    go((p) => {
      p.set('review.view', 'gate');
      p.delete('variant');
      p.delete('do');
    });

  return (
    <div className="flex flex-col gap-3">
      {gate ? null : <TaxGateBanner onShow={showGate} />}
      {gate ? null : (
      <p className="m-0 max-w-[80ch] text-sm text-muted">
        {audit
          ? 'Items the system approved under the risk-based rule. They are live. Check the category and the answers, then confirm each one, or move it to the right category, which closes it until it is reviewed again.'
          : decided
          ? 'Every decision on a product’s tax: who made it, when, and under which rule version. A decision is used at checkout until the product changes.'
          : 'Products whose tax waits for a person. The seller supplied the facts; you check the category, the answers and the proof, then approve, move it to another category, or ask the seller for more. A product goes live only after it is approved.'}
      </p>
      )}
      <div className="flex flex-wrap items-end gap-x-6 gap-y-3">
        <ChipGroup<View>
          label="Show"
          options={[
            { value: 'queue', label: 'Waiting for review' },
            { value: 'audit', label: 'Audit sample' },
            { value: 'decisions', label: 'Decisions' },
            ...(gateCount > 0 || gate ? [{ value: 'gate' as const, label: t('st.tax.gate.chip', { n: gateCount }) }] : [])
          ]}
          value={view}
          onChange={(v) =>
            go((p) => {
              p.set('review.view', v);
              p.delete('variant');
              p.delete('do');
            })
          }
        />
        {decided ? (
          <Select label="Decision" value={status} options={DECISION_STATUSES} onChange={(e) => go((p) => p.set('review.status', e.currentTarget.value))} className="min-w-56" />
        ) : null}
      </div>
      {gate ? <TaxGateList /> : null}
      {gate ? null : (
      <DataTable<TaxReviewRow>
        id={`staff.tax.review.${view}`}
        label={decided ? 'Tax decisions' : audit ? 'Tax audit sample' : 'Tax review queue'}
        columns={columns}
        data={data?.items ?? []}
        getRowId={(r) => r.atin}
        rowLabel={(r) => r.title}
        state={state}
        mode="server"
        total={data?.total ?? null}
        loading={query.isPending}
        fetching={query.isFetching}
        error={query.isError ? <ErrorState error={query.error} onRetry={() => void query.refetch()} /> : undefined}
        searchPlaceholder="Search product name, ATIN or GTIN"
        enableSelection={false}
        onRowClick={(r) => open(r.atin)}
        activeRowId={openAtin}
        empty={decided ? 'No decisions of this kind yet.' : audit ? 'No items are in the audit sample.' : 'Nothing is waiting for tax review.'}
      />
      )}
      <TaxReviewSheet atin={openAtin} action={action} onClose={() => go((p) => (p.delete('variant'), p.delete('do')))} onAction={(a) => go((p) => (a ? p.set('do', a) : p.delete('do')))} />
    </div>
  );
}
