'use client';

import { staffTaxQueries, TAX_AUDIT_SAMPLE, type TaxReviewRow } from '@afrotransact/api-client';
import { DataTable, rowActionsColumn, useTableState } from '@afrotransact/data-table';
import { ChipGroup, Combobox, Select } from '@afrotransact/ui';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { type ColumnDef } from '@tanstack/react-table';
import { usePathname, useRouter, useSearchParams } from 'next/navigation';
import { useMemo } from 'react';

import { ErrorState } from '@/components/ui/States';
import { EN_MESSAGES, type MessageKey, type Translator, useT } from '@/i18n/t';
import { staffApi } from '@/lib/api';
import { shortDate } from '@/lib/format';

import { SellerFilter } from './SellerFilter';
import { RecordPill } from './shared';
import { TaxGateBanner, useLiveWithoutDecisionCount } from './TaxGateBanner';
import { TaxGateList } from './TaxGateList';
import { TaxReviewSheet } from './TaxReviewSheet';

type View = 'queue' | 'audit' | 'decisions' | 'gate';

/** Record states the queue can show (the first is the queue itself; "ALL" looks in every state). */
const QUEUE_STATUSES = ['PENDING_REVIEW', 'ALL', 'STALE', 'REJECTED', 'MISSING_FACTS'] as const;
const DECISION_STATUSES = ['APPROVED', 'REJECTED', 'STALE'] as const;
const ANY = '';

const statusLabel = (t: Translator<MessageKey>, status: string): string => t(`st.tax.review.status.${status}` as MessageKey);

/** The short reason a person is needed, in the active language (the BFF's English label for a code not translated yet). */
function reasonLabel(t: Translator<MessageKey>, code: string, fallback: string): string {
  const key = `st.tax.reason.${code}`;
  return key in EN_MESSAGES ? t(key as MessageKey) : fallback;
}

/**
 * Tax review: the queue of product variants whose tax classification waits for a person, and the decisions already made
 * (who decided, when, under which rule version). Vendor supplies the facts, ADMIN TAX REVIEW approves, then it can publish.
 * A server-driven table: one search over the item's name, ATIN, GTIN and seller's name; filters by state, why a person is
 * needed, category and seller; sortable columns; paging. Each row shows the item with its photo, its seller by name and
 * why it needs review; a click opens the item.
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
  const reason = params.get('review.reason') ?? ANY;
  const category = params.get('review.category') ?? ANY;
  const sellerId = params.get('review.seller') ?? ANY;
  const sellerName = params.get('review.sellerName') ?? ANY;
  // Asking for a reason looks in every state (a missing answer or an audit sample is not "pending review"), unless a state was chosen.
  const status = decided ? (params.get('review.status') ?? 'APPROVED') : audit ? TAX_AUDIT_SAMPLE : (params.get('review.status') ?? (reason ? 'ALL' : 'PENDING_REVIEW'));
  const openAtin = params.get('variant');
  const action = params.get('do');
  const state = useTableState({ searchParams: params, prefix: `review-${view}` });
  const filtered = Boolean(state.q || reason || category || sellerId || (!audit && status !== (decided ? 'APPROVED' : 'PENDING_REVIEW')));

  const go = (change: (p: URLSearchParams) => void) => {
    const next = new URLSearchParams(params.toString());
    change(next);
    const qs = next.toString();
    router.replace(qs ? `${pathname}?${qs}` : pathname, { scroll: false });
  };
  /** A filter changed: the table goes back to its first page. */
  const filter = (change: (p: URLSearchParams) => void) =>
    go((p) => {
      change(p);
      p.delete(`review-${view}.page`);
    });
  const open = (atin: string, doAction?: string) =>
    go((p) => {
      p.set('variant', atin);
      if (doAction) p.set('do', doAction);
      else p.delete('do');
    });
  const setParam = (name: string, value: string) => (p: URLSearchParams) => (value ? p.set(name, value) : p.delete(name));

  const query = useQuery({
    ...staffTaxQueries.reviews(api.tax, {
      status,
      q: state.q || undefined,
      reason: reason || undefined,
      categoryCode: category || undefined,
      sellerOrgId: sellerId || undefined,
      sort: state.sort,
      page: state.pagination.pageIndex,
      size: state.pagination.pageSize
    }),
    placeholderData: keepPreviousData,
    enabled: !gate
  });
  const data = query.data;
  const categories = useQuery({ ...staffTaxQueries.profiles(api.tax, { size: 200 }), enabled: !gate });
  const reasons = (data?.reasons ?? []).map((r) => ({ value: r.value, label: reasonLabel(t, r.value, r.label) }));

  const columns = useMemo<ColumnDef<TaxReviewRow>[]>(
    () => [
      {
        id: 'title',
        header: t('st.tax.review.col.item'),
        size: 230,
        accessorFn: (r) => r.title,
        cell: ({ row }) => (
          <span className="flex items-center gap-3">
            {row.original.imageUrl ? (
              <img src={row.original.imageUrl} alt="" width={40} height={40} className="size-10 shrink-0 rounded-sm border border-border object-cover" />
            ) : (
              <span aria-hidden className="size-10 shrink-0 rounded-sm border border-border bg-surface-muted" />
            )}
            <strong className="min-w-0 font-semibold">{row.original.title}</strong>
          </span>
        ),
        meta: { label: t('st.tax.review.col.item'), kind: 'text' }
      },
      { id: 'atin', header: t('st.tax.review.col.atin'), size: 110, accessorFn: (r) => r.atin, cell: ({ row }) => <span className="font-mono text-xs">{row.original.atin}</span>, meta: { label: t('st.tax.review.col.atin'), kind: 'text' } },
      {
        id: 'sellers',
        header: t('st.tax.review.col.seller'),
        size: 140,
        enableSorting: false,
        accessorFn: (r) => r.sellers.join(', '),
        cell: ({ row }) => row.original.sellers.join(', ') || t('st.tax.review.unknownSeller'),
        meta: { label: t('st.tax.review.col.seller'), kind: 'text' }
      },
      { id: 'category', header: t('st.tax.review.col.category'), size: 130, accessorFn: (r) => r.categoryName, meta: { label: t('st.tax.review.col.category'), kind: 'text' } },
      ...(decided
        ? ([
            {
              id: 'status',
              header: t('st.tax.review.col.decision'),
              size: 140,
              accessorFn: (r) => r.statusLabel,
              cell: ({ row }) => <RecordPill status={row.original.status} label={row.original.statusLabel} />,
              meta: { label: t('st.tax.review.col.decision'), kind: 'status' }
            },
            {
              id: 'reviewedByName',
              header: t('st.tax.review.col.decidedBy'),
              size: 130,
              enableSorting: false,
              accessorFn: (r) => r.reviewedByName,
              cell: ({ row }) => row.original.reviewedByName || '—',
              meta: { label: t('st.tax.review.col.decidedBy'), kind: 'text' }
            },
            {
              id: 'reviewed',
              header: t('st.tax.review.col.when'),
              size: 100,
              accessorFn: (r) => r.reviewedAt,
              cell: ({ row }) => (row.original.reviewedAt ? shortDate(row.original.reviewedAt) : '—'),
              meta: { label: t('st.tax.review.col.when'), kind: 'text' }
            },
            {
              id: 'ruleVersion',
              header: t('st.tax.review.col.ruleVersion'),
              size: 170,
              enableSorting: false,
              accessorFn: (r) => r.ruleVersion,
              cell: ({ row }) => <span className="font-mono text-xs">{row.original.ruleVersion}</span>,
              meta: { label: t('st.tax.review.col.ruleVersion'), kind: 'text' }
            }
          ] satisfies ColumnDef<TaxReviewRow>[])
        : audit
          ? ([
              {
                id: 'proposed',
                header: t('st.tax.review.col.taxInForce'),
                size: 120,
                enableSorting: false,
                accessorFn: (r) => r.proposed.outcomeLabel || r.proposed.statusLabel,
                meta: { label: t('st.tax.review.col.taxInForce'), kind: 'text' }
              },
              {
                id: 'reviewedByName',
                header: t('st.tax.review.col.approvedBy'),
                size: 140,
                enableSorting: false,
                accessorFn: (r) => r.reviewedByName,
                cell: ({ row }) => row.original.reviewedByName || '—',
                meta: { label: t('st.tax.review.col.approvedBy'), kind: 'text' }
              },
              {
                id: 'reviewed',
                header: t('st.tax.review.col.approved'),
                size: 100,
                accessorFn: (r) => r.reviewedAt,
                cell: ({ row }) => (row.original.reviewedAt ? shortDate(row.original.reviewedAt) : '—'),
                meta: { label: t('st.tax.review.col.approved'), kind: 'text' }
              },
              { id: 'why', header: t('st.tax.review.col.whatToDo'), size: 220, enableSorting: false, accessorFn: (r) => r.why, meta: { label: t('st.tax.review.col.whatToDo'), kind: 'text' } }
            ] satisfies ColumnDef<TaxReviewRow>[])
          : ([
              {
                id: 'proposed',
                header: t('st.tax.review.col.proposed'),
                size: 120,
                enableSorting: false,
                accessorFn: (r) => r.proposed.outcomeLabel || r.proposed.statusLabel,
                meta: { label: t('st.tax.review.col.proposed'), kind: 'text' }
              },
              {
                id: 'why',
                header: t('st.tax.review.col.why'),
                size: 240,
                enableSorting: false,
                accessorFn: (r) => r.why,
                cell: ({ row }) => (
                  <span className="flex flex-col gap-0.5">
                    {row.original.reviewReasonCode ? <strong className="font-semibold">{reasonLabel(t, row.original.reviewReasonCode, row.original.reviewReasonLabel)}</strong> : null}
                    <span className="line-clamp-2 text-xs text-muted">{row.original.why}</span>
                  </span>
                ),
                meta: { label: t('st.tax.review.col.why'), kind: 'text' }
              },
              {
                id: 'submitted',
                header: t('st.tax.review.col.submitted'),
                size: 100,
                accessorFn: (r) => r.classifiedAt,
                cell: ({ row }) => (row.original.classifiedAt ? shortDate(row.original.classifiedAt) : '—'),
                meta: { label: t('st.tax.review.col.submitted'), kind: 'text' }
              }
            ] satisfies ColumnDef<TaxReviewRow>[])),
      rowActionsColumn<TaxReviewRow>({
        header: t('st.tax.review.col.actions'),
        name: (r) => r.title,
        items: (r) => {
          const decidable = data?.canDecide && (r.status === 'PENDING_REVIEW' || r.status === 'STALE');
          const checkable = data?.canDecide && r.auditSample;
          return [
            { label: t('st.tax.review.action.open'), onSelect: () => open(r.atin) },
            checkable ? { label: t('st.tax.review.action.confirm'), onSelect: () => open(r.atin, 'confirm') } : null,
            checkable ? { label: t('st.tax.review.action.reclassify'), onSelect: () => open(r.atin, 'reclassify') } : null,
            decidable ? { label: t('st.tax.review.action.approve'), onSelect: () => open(r.atin, 'approve') } : null,
            decidable ? { label: t('st.tax.review.action.reclassify'), onSelect: () => open(r.atin, 'reclassify') } : null,
            decidable ? { label: t('st.tax.review.action.ask'), onSelect: () => open(r.atin, 'info') } : null
          ];
        }
      })
    ],
    // eslint-disable-next-line react-hooks/exhaustive-deps -- open only reads the current URL when a menu entry is chosen
    [decided, audit, data?.canDecide, t]
  );

  const showGate = () =>
    go((p) => {
      p.set('review.view', 'gate');
      p.delete('variant');
      p.delete('do');
    });

  const statuses = decided ? DECISION_STATUSES : QUEUE_STATUSES;
  return (
    <div className="flex flex-col gap-3">
      {gate ? null : <TaxGateBanner onShow={showGate} />}
      {gate ? null : <p className="m-0 max-w-[80ch] text-sm text-muted">{t(audit ? 'st.tax.review.help.audit' : decided ? 'st.tax.review.help.decisions' : 'st.tax.review.help.queue')}</p>}
      <div className="flex flex-wrap items-end gap-x-6 gap-y-3" data-tour="tax-review-views">
        <ChipGroup<View>
          label={t('st.tax.review.show')}
          options={[
            { value: 'queue', label: t('st.tax.review.view.queue') },
            { value: 'audit', label: t('st.tax.review.view.audit') },
            { value: 'decisions', label: t('st.tax.review.view.decisions') },
            ...(gateCount > 0 || gate ? [{ value: 'gate' as const, label: t('st.tax.gate.chip', { n: gateCount }) }] : [])
          ]}
          value={view}
          onChange={(v) =>
            go((p) => {
              p.set('review.view', v);
              p.delete('review.status');
              p.delete('review.reason');
              p.delete('variant');
              p.delete('do');
            })
          }
        />
      </div>
      {gate ? null : (
        <div className="flex flex-wrap items-end gap-x-4 gap-y-3" role="group" aria-label={t('st.tax.review.filters')} data-tour="tax-review-filters">
          {audit ? null : (
            <Select
              label={t('st.tax.review.filter.status')}
              value={status}
              options={statuses.map((s) => ({ value: s, label: statusLabel(t, s) }))}
              onChange={(e) => filter(setParam('review.status', e.currentTarget.value))}
              className="min-w-48"
            />
          )}
          {decided || audit ? null : (
            <Select
              label={t('st.tax.review.filter.reason')}
              value={reason}
              placeholder={t('st.tax.review.filter.reasonAny')}
              options={reasons}
              onChange={(e) => filter(setParam('review.reason', e.currentTarget.value))}
              className="min-w-48"
            />
          )}
          <div className="min-w-56">
            <Combobox
              label={t('st.tax.review.filter.category')}
              placeholder={t('st.tax.review.filter.categoryAny')}
              value={category}
              clearable
              options={(categories.data?.items ?? []).map((c) => ({ value: c.categoryCode, label: c.name, description: c.departmentName }))}
              onChange={(v) => filter(setParam('review.category', v))}
              emptyLabel={t('st.tax.review.filter.categoryNone')}
            />
          </div>
          <SellerFilter
            id={sellerId}
            name={sellerName}
            onChange={(seller) =>
              filter((p) => {
                setParam('review.seller', seller?.id ?? '')(p);
                setParam('review.sellerName', seller?.name ?? '')(p);
              })
            }
          />
          {filtered ? (
            <button
              type="button"
              className="min-h-10 cursor-pointer border-0 bg-transparent px-1 text-sm font-semibold text-link underline"
              onClick={() =>
                filter((p) => {
                  ['review.status', 'review.reason', 'review.category', 'review.seller', 'review.sellerName'].forEach((k) => p.delete(k));
                  p.delete(`review-${view}.q`);
                })
              }
            >
              {t('st.tax.review.filter.clear')}
            </button>
          ) : null}
        </div>
      )}
      {gate ? <TaxGateList /> : null}
      {gate ? null : (
        <DataTable<TaxReviewRow>
          id={`staff.tax.review.${view}`}
          label={t(decided ? 'st.tax.review.table.decisions' : audit ? 'st.tax.review.table.audit' : 'st.tax.review.table.queue')}
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
          searchPlaceholder={t('st.tax.review.search')}
          noMatches={t('st.tax.review.noMatches')}
          enableSelection={false}
          onRowClick={(r) => open(r.atin)}
          activeRowId={openAtin}
          empty={filtered ? t('st.tax.review.noMatches') : t(decided ? 'st.tax.review.empty.decisions' : audit ? 'st.tax.review.empty.audit' : 'st.tax.review.empty.queue')}
        />
      )}
      <TaxReviewSheet atin={openAtin} action={action} onClose={() => go((p) => (p.delete('variant'), p.delete('do')))} onAction={(a) => go((p) => (a ? p.set('do', a) : p.delete('do')))} />
    </div>
  );
}
