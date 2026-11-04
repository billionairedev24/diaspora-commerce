'use client';

import { type ProductDraftSummary, sellerKeys, sellerQueries } from '@afrotransact/api-client';
import { DataTable, rowActionsColumn, useTableState } from '@afrotransact/data-table';
import { Button, buttonClassName, ConfirmDelete, RowName, useToast } from '@afrotransact/ui';
import { ArrowRight, Plus, Trash } from '@phosphor-icons/react/ssr';
import { keepPreviousData, useQuery, useQueryClient } from '@tanstack/react-query';
import { type ColumnDef } from '@tanstack/react-table';
import Link from 'next/link';
import { useRouter, useSearchParams } from 'next/navigation';
import { useCallback, useMemo, useRef, useState } from 'react';

import { ErrorState, problemMessage } from '@/components/ui/States';
import { useT } from '@/i18n/t';
import { sellerApi } from '@/lib/api';
import { dateTime } from '@/lib/format';
import { useSeller } from '@/lib/session';
import { runForEach } from '@/lib/table-export';

/** The basics a draft is checked on (category, title, a photo, a price, a description): the catalog counts what is left. */
const CHECKS = 5;

interface Pending {
  rows: ProductDraftSummary[];
  clear?: () => void;
}

/** Drafts: the autosaved product forms of the organisation in their own server-driven table (search over title and
    category, sort, pages), so a seller with thousands of them is as well served as one with three. */
export function DraftsTable() {
  const api = sellerApi();
  const t = useT();
  const qc = useQueryClient();
  const router = useRouter();
  const toast = useToast();
  const { orgId } = useSeller();
  const routerRef = useRef(router);
  routerRef.current = router;
  const go = useCallback((href: string) => routerRef.current.push(href), []);
  const [pending, setPending] = useState<Pending | null>(null);
  const [busy, setBusy] = useState(false);
  const state = useTableState({ searchParams: useSearchParams(), defaultSort: [{ id: 'updatedAt', desc: true }] });
  const q = useQuery({
    ...sellerQueries.productDraftPage(api, orgId, { kind: 'new', q: state.q || undefined, sort: state.sort, page: state.pagination.pageIndex, size: state.pagination.pageSize }),
    placeholderData: keepPreviousData
  });
  const untitled = t('sc.listings.untitled');
  const nameOf = (d: ProductDraftSummary) => d.title || untitled;
  const open = (d: ProductDraftSummary) => `/listings/new?draft=${encodeURIComponent(d.id)}`;

  const discard = async () => {
    if (!pending) return;
    setBusy(true);
    try {
      if (pending.rows.length === 1 && pending.rows[0]) {
        await api.deleteProductDraft(pending.rows[0].id);
        toast.show(t('sc.drafts.discarded'));
      } else {
        const r = await runForEach(pending.rows, (d) => api.deleteProductDraft(d.id), 'discarded', problemMessage);
        toast.show(r.text, r.tone);
      }
      pending.clear?.();
      await qc.invalidateQueries({ queryKey: sellerKeys.productDrafts(orgId) });
      setPending(null);
    } catch (e) {
      toast.show(problemMessage(e), 'error');
    } finally {
      setBusy(false);
    }
  };

  const columns = useMemo<ColumnDef<ProductDraftSummary>[]>(
    () => [
      {
        id: 'title', accessorFn: (d) => d.title, header: t('sc.drafts.col.draft'), size: 320, meta: { kind: 'strong', label: t('sc.drafts.col.draft'), exportValue: (d) => d.title || untitled },
        cell: ({ row }) => <RowName href={open(row.original)} linkAs={Link}>{nameOf(row.original)}</RowName>
      },
      { id: 'categoryPath', accessorFn: (d) => d.categoryPath ?? '', header: t('sc.drafts.col.category'), size: 260, meta: { label: t('sc.drafts.col.category') }, cell: ({ row }) => row.original.categoryPath ?? '—' },
      {
        id: 'progress', accessorFn: (d) => d.todo, header: t('sc.drafts.col.progress'), size: 170, enableSorting: false, meta: { kind: 'number', label: t('sc.drafts.col.progress') },
        cell: ({ row }) => {
          const done = CHECKS - Math.min(CHECKS, row.original.todo);
          return (
            <span className="flex flex-col gap-1">
              <span className="text-sm">{row.original.todo === 0 ? t('sc.drafts.ready') : t('sc.drafts.todo', { count: row.original.todo })}</span>
              <span role="img" aria-label={t('sc.drafts.progressAria', { done, total: CHECKS })} className="block h-1 w-24 overflow-hidden rounded-pill bg-border">
                <span className="block h-full bg-success" style={{ width: `${(100 * done) / CHECKS}%` }} />
              </span>
            </span>
          );
        }
      },
      { id: 'updatedAt', accessorFn: (d) => d.updatedAt, header: t('sc.drafts.col.saved'), size: 170, meta: { kind: 'datetime', label: t('sc.drafts.col.saved') }, cell: ({ row }) => dateTime(row.original.updatedAt) },
      rowActionsColumn<ProductDraftSummary>({
        header: t('sc.rows.actionsHeader'),
        name: (d) => d.title || untitled,
        label: (d) => t('sc.rows.actionsFor', { name: d.title || untitled }),
        items: (d) => [
          { label: t('sc.drafts.continue'), icon: <ArrowRight size={16} aria-hidden />, onSelect: () => go(open(d)) },
          { label: t('sc.drafts.discard'), icon: <Trash size={16} aria-hidden />, danger: true, onSelect: () => setPending({ rows: [d] }) }
        ]
      })
    ],
    // eslint-disable-next-line react-hooks/exhaustive-deps -- nameOf/open are pure helpers of t and the draft
    [t, go, untitled]
  );
  const many = pending?.rows.length ?? 0;
  return (
    <>
      <DataTable<ProductDraftSummary>
        id="seller.drafts"
        label={t('sc.drafts.col.draft')}
        columns={columns}
        data={q.data?.items ?? []}
        getRowId={(d) => d.id}
        rowLabel={nameOf}
        onRowClick={(d) => go(open(d))}
        state={state}
        total={q.data?.total ?? null}
        loading={q.isPending}
        fetching={q.isFetching}
        error={q.isError ? <ErrorState error={q.error} onRetry={() => void q.refetch()} /> : undefined}
        searchPlaceholder={t('sc.drafts.search')}
        empty={
          <span className="flex flex-col items-center gap-3">
            {t('sc.drafts.empty')}
            <Link href="/listings/new" className={buttonClassName({ variant: 'primary' })}>
              <Plus size={18} aria-hidden /> {t('sc.listings.add')}
            </Link>
          </span>
        }
        noMatches={t('sc.drafts.noMatches')}
        bulkActions={(rows, clear) => (
          <Button size="sm" variant="neutral" onClick={() => setPending({ rows, clear })}>
            {t('sc.drafts.discardMany')}
          </Button>
        )}
      />
      <ConfirmDelete
        open={pending !== null}
        onOpenChange={(o) => (o ? undefined : setPending(null))}
        title={many === 1 && pending?.rows[0] ? t('sc.drafts.discardTitle', { title: nameOf(pending.rows[0]) }) : t('sc.drafts.discardManyTitle', { count: many })}
        description={t('sc.drafts.discardBody')}
        verb={t('sc.drafts.discard')}
        cancelLabel={t('sc.rows.cancel')}
        closeLabel={t('sc.rows.close')}
        pending={busy}
        onConfirm={discard}
      />
    </>
  );
}
