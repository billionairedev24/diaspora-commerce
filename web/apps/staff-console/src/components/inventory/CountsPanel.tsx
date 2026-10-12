'use client';

import { CYCLE_COUNT_REASONS, type CycleCount, type CycleCountReason, inventoryHubQueries, validateCountEntry } from '@afrotransact/api-client';
import { DataTable, useTableState } from '@afrotransact/data-table';
import { Button, Checkbox, cx, Select, Sheet, SheetTableScroll, TextField } from '@afrotransact/ui';
import { ClipboardText } from '@phosphor-icons/react/ssr';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { type ColumnDef } from '@tanstack/react-table';
import { useSearchParams } from 'next/navigation';
import { useEffect, useMemo, useRef, useState } from 'react';

import { ErrorState, Spinner } from '@/components/ui/States';
import { StatusPill } from '@/components/ui/StatusPill';
import { dateTime, humanize, shortDate } from '@/lib/format';
import { exportDataset } from '@/lib/table-export';

import { toneOf, useHub, useHubAccess, useHubMutation, useParam } from './shared';

interface Entry {
  counted: string;
  reason: CycleCountReason | '';
  note: string;
}

/** One cycle count: enter counted quantities (blind counts hide the system quantity), review variances, post. */
function CountSheet({ id, onClose }: { id: string | null; onClose: () => void }) {
  const api = useHub();
  const { inventoryEdit } = useHubAccess();
  const q = useQuery({ ...inventoryHubQueries.count(api, id ?? ''), enabled: id !== null });
  const c = q.data;
  const [entries, setEntries] = useState<Record<number, Entry>>({});
  const [review, setReview] = useState(false);
  // Edits made while a save is in flight must survive the reload it triggers: entries are reset from the server only for
  // a new count or when nothing was edited since the last save started.
  const dirty = useRef(false);
  const shownId = useRef<string | null>(null);
  useEffect(() => {
    if (!c) return;
    if (dirty.current && shownId.current === c.id) return;
    shownId.current = c.id;
    setEntries(Object.fromEntries(c.lines.map((l) => [l.lineNo, { counted: l.countedQty === null || l.countedQty === undefined ? '' : String(l.countedQty), reason: l.reasonCode ?? '', note: l.note ?? '' }])));
  }, [c?.id, c?.version]); // eslint-disable-line react-hooks/exhaustive-deps
  const editable = Boolean(c && inventoryEdit && (c.status === 'OPEN' || c.status === 'COUNTED'));
  const hideExpected = Boolean(c?.blind && c.status === 'OPEN' && !review);
  const errors: Record<string, string> = {};
  c?.lines.forEach((l) => {
    const e = entries[l.lineNo];
    if (!e || e.counted === '') return;
    Object.entries(validateCountEntry(l.expectedQty, e.counted, e.reason || undefined)).forEach(([k, v]) => {
      errors[`${l.lineNo}.${k}`] = v;
    });
  });
  const filled = c ? c.lines.filter((l) => entries[l.lineNo]?.counted !== '' && entries[l.lineNo]?.counted !== undefined) : [];
  const save = useHubMutation(
    () => {
      dirty.current = false;
      return api.recordCounts(
        c?.id ?? '',
        filled.map((l) => {
          const e = entries[l.lineNo] as Entry;
          return { lineNo: l.lineNo, countedQty: Number(e.counted), reasonCode: e.reason || undefined, note: e.note.trim() || undefined };
        }),
        c?.version
      );
    },
    (x) => `${x.number}: ${x.countedLines} of ${x.lineCount} lines counted.`,
    () => setReview(true)
  );
  const post = useHubMutation(() => api.postCount(c?.id ?? '', c?.version), (x) => `${x.number} posted: ${x.varianceLines} adjustments (net ${x.netVariance > 0 ? '+' : ''}${x.netVariance}).`);
  const cancel = useHubMutation(() => api.cancelCount(c?.id ?? '', c?.version), (x) => `${x.number} cancelled.`);
  const set = (lineNo: number, patch: Partial<Entry>) => {
    dirty.current = true;
    setEntries((e) => ({ ...e, [lineNo]: { ...(e[lineNo] ?? { counted: '', reason: '', note: '' }), ...patch } }));
  };
  return (
    <Sheet open={id !== null} onOpenChange={(o) => !o && onClose()} title={c ? `Cycle count ${c.number}` : 'Cycle count'} width={820}>
      {q.isPending ? <Spinner /> : null}
      {q.isError ? <ErrorState error={q.error} onRetry={() => void q.refetch()} /> : null}
      {c ? (
        <div className="flex flex-col gap-3" data-testid="count-sheet">
          <div className="flex flex-wrap items-center gap-2 text-sm">
            <StatusPill tone={toneOf(c.status)}>{humanize(c.status)}</StatusPill>
            <span className="text-muted">
              {c.nodeId}
              {c.sku ? ` · ${c.sku}` : ''}
              {c.binPrefix ? ` · bins ${c.binPrefix}…` : ''} · {c.lineCount} lots · started {dateTime(c.createdAt)} by {c.createdBy}
            </span>
            {c.blind ? <StatusPill tone="info">Blind count</StatusPill> : null}
          </div>
          <div className="overflow-hidden rounded-md border border-border">
            <SheetTableScroll className="at-sheet-table-form">
            <table className="at-table text-sm" aria-label="Count lines">
              <thead className="bg-surface-muted text-left text-xs text-muted">
                <tr>
                  {['Bin', 'SKU · lot', 'Expires', 'System', 'Counted', 'Variance', 'Reason', 'Note'].map((h) => (
                    <th key={h} scope="col" className="px-2 py-1.5">{h}</th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {c.lines.map((l) => {
                  const e = entries[l.lineNo] ?? { counted: '', reason: '', note: '' };
                  const variance = e.counted !== '' && /^\d+$/.test(e.counted) ? Number(e.counted) - l.expectedQty : null;
                  const posted = l.postedDelta !== null && l.postedDelta !== undefined;
                  return (
                    <tr key={l.lineNo} className="border-t border-border align-top" data-testid="count-line">
                      <td className="px-2 py-1.5 font-mono text-xs">{l.bin ?? '—'}</td>
                      <td className="px-2 py-1.5">
                        <span className="at-cell-stack block font-mono text-xs">{l.sku}</span>
                        <span className="at-cell-stack font-mono text-xs text-muted">{l.lotNo}</span>
                      </td>
                      <td className="px-2 py-1.5 text-xs">{shortDate(l.expDate)}</td>
                      <td className="px-2 py-1.5 text-end">{hideExpected ? '•••' : l.expectedQty}</td>
                      <td className="px-2 py-1.5">
                        {editable ? (
                          <TextField label={`Counted ${l.lotNo}`} className="w-24 [&_label]:sr-only" inputMode="numeric" value={e.counted} onChange={(ev) => set(l.lineNo, { counted: ev.target.value })} error={errors[`${l.lineNo}.countedQty`]} />
                        ) : (
                          (l.countedQty ?? '—')
                        )}
                      </td>
                      <td className={cx('px-2 py-1.5 text-end font-semibold', variance === null || hideExpected ? '' : variance === 0 ? 'text-success' : 'text-danger')} data-testid="count-variance">
                        {posted ? `${(l.postedDelta ?? 0) > 0 ? '+' : ''}${l.postedDelta ?? 0} posted` : variance === null || hideExpected ? '—' : `${variance > 0 ? '+' : ''}${variance}`}
                      </td>
                      <td className="px-2 py-1.5">
                        {editable ? (
                          <Select label={`Reason for ${l.lotNo}`} className="min-w-36 [&_label]:sr-only" value={e.reason} placeholder="—" onChange={(ev) => set(l.lineNo, { reason: ev.target.value as CycleCountReason })} options={CYCLE_COUNT_REASONS} error={errors[`${l.lineNo}.reasonCode`]} />
                        ) : (
                          (CYCLE_COUNT_REASONS.find((r) => r.value === l.reasonCode)?.label ?? '—')
                        )}
                      </td>
                      <td className="px-2 py-1.5">
                        {editable ? <TextField label={`Note for ${l.lotNo}`} className="min-w-28 [&_label]:sr-only" value={e.note} onChange={(ev) => set(l.lineNo, { note: ev.target.value })} /> : (l.note ?? '')}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
            </SheetTableScroll>
          </div>
          <p className="m-0 text-sm" role="status">
            {c.countedLines} of {c.lineCount} lines counted · {c.varianceLines} variances · net {c.netVariance > 0 ? '+' : ''}
            {c.netVariance} units
          </p>
          {editable ? (
            <div className="flex flex-wrap gap-2">
              <Button variant="neutral" loading={save.isPending} disabled={filled.length === 0 || Object.keys(errors).some((k) => k.endsWith('countedQty'))} onClick={() => save.mutate(undefined)}>
                Save counts
              </Button>
              <Button loading={post.isPending} disabled={c.status !== 'COUNTED' || Object.keys(errors).length > 0} onClick={() => post.mutate(undefined)}>
                Post adjustments
              </Button>
              <Button variant="neutral" loading={cancel.isPending} onClick={() => cancel.mutate(undefined)}>
                Cancel count
              </Button>
            </div>
          ) : null}
          {c.status === 'POSTED' ? <p className="m-0 text-sm text-muted">Posted {dateTime(c.postedAt)} by {c.postedBy}: each variance is an ADJUST movement with its reason in the lot’s trace.</p> : null}
        </div>
      ) : null}
    </Sheet>
  );
}

/** Start a count of a fulfilment centre (optionally one SKU or a bin range). */
function StartCount({ onStarted }: { onStarted: (c: CycleCount) => void }) {
  const api = useHub();
  const nodes = useQuery(inventoryHubQueries.nodes(api));
  const [nodeId, setNodeId] = useState('HOU-FC');
  const [sku, setSku] = useState('');
  const [bin, setBin] = useState('');
  const [blind, setBlind] = useState(false);
  const start = useHubMutation(() => api.startCount({ nodeId, sku: sku.trim() || undefined, binPrefix: bin.trim() || undefined, blind }), (c) => `${c.number} started: ${c.lineCount} lots to count.`, onStarted);
  return (
    <form
      className="flex flex-wrap items-end gap-2 rounded-md border border-border bg-surface p-3"
      aria-label="Start a cycle count"
      onSubmit={(e) => {
        e.preventDefault();
        start.mutate(undefined);
      }}
    >
      <Select label="Fulfilment centre" value={nodeId} onChange={(e) => setNodeId(e.target.value)} options={(nodes.data ?? []).map((n) => ({ value: n.id, label: n.name }))} />
      <TextField label="SKU (optional)" value={sku} onChange={(e) => setSku(e.target.value)} />
      <TextField label="Bins starting with (optional)" value={bin} onChange={(e) => setBin(e.target.value)} placeholder="A-01" />
      <Checkbox label="Blind count" checked={blind} onChange={(e) => setBlind(e.target.checked)} />
      <Button type="submit" icon={<ClipboardText size={16} aria-hidden />} loading={start.isPending}>
        Start count
      </Button>
    </form>
  );
}

/** Cycle counts: start, count, review variances and post audited adjustments with reason codes. */
export function CountsPanel() {
  const api = useHub();
  const qc = useQueryClient();
  const { inventoryEdit } = useHubAccess();
  const state = useTableState({ searchParams: useSearchParams(), prefix: 'cc' });
  const [id, setId] = useParam('count');
  const query = useQuery(inventoryHubQueries.counts(api, { page: 0, size: 200 }));
  const columns = useMemo<ColumnDef<CycleCount>[]>(
    () => [
      { id: 'number', accessorFn: (c) => c.number, header: 'Count', size: 120, meta: { kind: 'mono' } },
      { id: 'nodeId', accessorFn: (c) => c.nodeId, header: 'FC', size: 90 },
      { id: 'scope', accessorFn: (c) => [c.sku, c.binPrefix ? `bins ${c.binPrefix}…` : null].filter(Boolean).join(' · ') || 'Whole FC', header: 'Scope', size: 180 },
      { id: 'lineCount', accessorFn: (c) => c.lineCount, header: 'Lots', size: 70, meta: { kind: 'number', align: 'end' } },
      { id: 'countedLines', accessorFn: (c) => c.countedLines, header: 'Counted', size: 90, meta: { kind: 'number', align: 'end' } },
      { id: 'varianceLines', accessorFn: (c) => c.varianceLines, header: 'Variances', size: 100, meta: { kind: 'number', align: 'end' } },
      { id: 'netVariance', accessorFn: (c) => c.netVariance, header: 'Net units', size: 100, meta: { kind: 'number', align: 'end' } },
      { id: 'createdAt', accessorFn: (c) => c.createdAt ?? '', header: 'Started', size: 150, meta: { kind: 'datetime' }, cell: ({ row }) => dateTime(row.original.createdAt) },
      { id: 'status', accessorFn: (c) => humanize(c.status), header: 'Status', size: 110, meta: { kind: 'status' }, cell: ({ row }) => <StatusPill tone={toneOf(row.original.status)}>{humanize(row.original.status)}</StatusPill> }
    ],
    []
  );
  return (
    <div className="flex flex-col gap-3">
      {inventoryEdit ? <StartCount onStarted={(c) => setId(c.id)} /> : null}
      <DataTable<CycleCount>
        id="staff.inventory.counts"
        label="Cycle counts"
        columns={columns}
        data={query.data?.items ?? []}
        getRowId={(c) => c.id}
        rowLabel={(c) => c.number}
        rowTestId="count-row"
        state={state}
        mode="client"
        loading={query.isPending}
        fetching={query.isFetching}
        error={query.isError ? <ErrorState error={query.error} onRetry={() => void query.refetch()} /> : undefined}
        onRowClick={(c) => setId(c.id)}
        activeRowId={id}
        empty="No cycle counts yet."
        onExport={(req) => exportDataset(qc, req)}
        exportsHref="/exports"
      />
      <CountSheet id={id} onClose={() => setId(null)} />
    </div>
  );
}
