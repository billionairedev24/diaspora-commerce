'use client';

import { isApiError, staffTaxKeys, staffTaxQueries, type TaxCodeEntry, type TaxCodeMapImportResult, type TaxCompleteness, type TaxProvider } from '@afrotransact/api-client';
import { CellText, DataTable, rowActionsColumn, useTableState } from '@afrotransact/data-table';
import { Button, ChipGroup, ProgressMeter, Select, TextField, useToast } from '@afrotransact/ui';
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { type ColumnDef } from '@tanstack/react-table';
import { usePathname, useRouter, useSearchParams } from 'next/navigation';
import { useMemo, useRef, useState } from 'react';

import { Notice } from '@/components/ui/Notice';
import { ErrorState, problemMessage } from '@/components/ui/States';
import { staffApi } from '@/lib/api';
import { shortDate } from '@/lib/format';

import { ActionDialog, CodeStatePill, mono } from './shared';

const PROVIDERS: { value: TaxProvider; label: string }[] = [
  { value: 'STRIPE', label: 'Stripe Tax' },
  { value: 'AVALARA', label: 'Avalara' }
];

/** How far along a provider is: outcomes with a verified code over outcomes that need one. */
export function CompletenessMeter({ c, label }: { c: TaxCompleteness; label: string }) {
  return (
    <ProgressMeter
      label={label}
      value={c.verified}
      max={c.needed}
      valueText={`${c.verified} of ${c.needed} outcomes verified`}
      attention={!c.complete}
      help={
        c.complete
          ? 'Every outcome has a verified code. This provider can be chosen once it is set up.'
          : `${c.unmapped} outcome${c.unmapped === 1 ? ' has' : 's have'} no code yet and ${c.unverified} ${c.unverified === 1 ? 'has a code that is' : 'have codes that are'} not verified. A line with no verified code is blocked at checkout, never guessed.`
      }
    />
  );
}

/**
 * Provider code maps: for each outcome (not each category) the code the provider uses, with whether a person verified it.
 * Edit one code with a reason, export or import the whole map as CSV, and watch the completeness meter for the provider
 * in use.
 */
export function TaxCodeMapPanel() {
  const api = staffApi();
  const router = useRouter();
  const pathname = usePathname();
  const params = useSearchParams();
  const providerParam = params.get('codes.provider');
  const provider: TaxProvider | undefined = providerParam === 'AVALARA' || providerParam === 'STRIPE' ? providerParam : undefined;
  const stateFilter = params.get('codes.state') ?? '';
  const state = useTableState({ searchParams: params, prefix: 'codes' });
  const [editing, setEditing] = useState<TaxCodeEntry | null>(null);
  const [importing, setImporting] = useState(false);
  const toast = useToast();

  const query = useQuery({
    ...staffTaxQueries.codeMap(api.tax, { provider, state: stateFilter || undefined, q: state.q || undefined, sort: state.sort, page: state.pagination.pageIndex, size: state.pagination.pageSize }),
    placeholderData: keepPreviousData
  });
  const data = query.data;
  const current = data?.provider ?? provider ?? 'STRIPE';

  const go = (change: (p: URLSearchParams) => void) => {
    const next = new URLSearchParams(params.toString());
    change(next);
    const qs = next.toString();
    router.replace(qs ? `${pathname}?${qs}` : pathname, { scroll: false });
  };

  const exporter = useMutation({
    mutationFn: () => api.tax.exportCodeMap(current),
    onSuccess: (f) => {
      const url = URL.createObjectURL(new Blob([f.csv], { type: 'text/csv;charset=utf-8' }));
      const a = document.createElement('a');
      a.href = url;
      a.download = f.filename;
      a.click();
      URL.revokeObjectURL(url);
      toast.show(`Exported ${f.rows} outcomes to ${f.filename}.`);
    },
    onError: (e) => toast.show(problemMessage(e), 'info')
  });

  const columns = useMemo<ColumnDef<TaxCodeEntry>[]>(
    () => [
      {
        id: 'internalTaxCode',
        header: 'Outcome',
        size: 280,
        accessorFn: (r) => r.internalTaxCode,
        cell: ({ row }) => (
          <span className="flex min-w-0 flex-col gap-0.5">
            <strong title={row.original.outcomeName} className="block truncate font-semibold">
              {row.original.outcomeName}
            </strong>
            <CellText mono>{row.original.internalTaxCode}</CellText>
          </span>
        ),
        meta: { label: 'Outcome', kind: 'text' }
      },
      { id: 'categoryName', header: 'Category', size: 170, accessorFn: (r) => r.categoryName, meta: { label: 'Category', kind: 'text' } },
      { id: 'outcomeLabel', header: 'Tax', size: 150, accessorFn: (r) => r.outcomeLabel, meta: { label: 'Tax', kind: 'text' } },
      { id: 'providerCode', header: 'Provider code', size: 170, accessorFn: (r) => r.providerCode, cell: ({ row }) => <CellText mono>{row.original.providerCode || '—'}</CellText>, meta: { label: 'Provider code', kind: 'text' } },
      { id: 'stateLabel', header: 'Status', size: 190, accessorFn: (r) => r.stateLabel, cell: ({ row }) => <CodeStatePill state={row.original.state} label={row.original.stateLabel} />, meta: { label: 'Status', kind: 'status' } },
      {
        id: 'changedByName',
        header: 'Last change',
        size: 190,
        enableSorting: false,
        accessorFn: (r) => r.changedByName,
        cell: ({ row }) => (row.original.changedByName ? `${row.original.changedByName}, ${shortDate(row.original.changedAt)}` : '—'),
        meta: { label: 'Last change', kind: 'text' }
      },
      rowActionsColumn<TaxCodeEntry>({
        header: 'Actions',
        name: (r) => r.outcomeName,
        items: (r) => [data?.canEdit ? { label: 'Edit code…', onSelect: () => setEditing(r), disabled: r.state === 'NOT_NEEDED' ? 'This outcome is never sold, so it needs no code.' : false } : { label: 'Edit code…', disabled: 'Needs the tax:classify-admin permission.' }]
      })
    ],
    [data?.canEdit]
  );

  return (
    <div className="flex flex-col gap-4">
      <p className="m-0 max-w-[80ch] text-sm text-muted">
        The code each provider uses for each outcome. It is mapped per outcome, never per category, because one provider code can carry different outcomes. A mapping counts only once a person has verified it.
      </p>
      <div className="flex flex-wrap items-end gap-x-6 gap-y-3">
        <ChipGroup<TaxProvider>
          label="Provider"
          options={PROVIDERS}
          value={current}
          onChange={(v) =>
            go((p) => {
              p.set('codes.provider', v);
              p.delete('codes.page');
            })
          }
        />
        <Select
          label="Show"
          value={stateFilter}
          placeholder="All outcomes"
          options={(data?.states ?? []).map((s) => ({ value: s.value, label: s.label }))}
          onChange={(e) =>
            go((p) => {
              if (e.currentTarget.value) p.set('codes.state', e.currentTarget.value);
              else p.delete('codes.state');
              p.delete('codes.page');
            })
          }
          className="min-w-56"
        />
        <div className="flex gap-2">
          <Button size="sm" variant="neutral" loading={exporter.isPending} onClick={() => exporter.mutate()}>
            Export CSV
          </Button>
          {data?.canEdit ? (
            <Button size="sm" variant="neutral" onClick={() => setImporting(true)}>
              Import CSV…
            </Button>
          ) : null}
        </div>
      </div>
      {data ? (
        <>
          <CompletenessMeter c={data.completeness} label={data.providerLabel} />
          {data.activeProvider && data.activeProvider !== data.provider ? (
            <p className="m-0 text-xs text-muted">The provider in use now is {PROVIDERS.find((p) => p.value === data.activeProvider)?.label ?? data.activeProvider}.</p>
          ) : null}
        </>
      ) : null}
      <DataTable<TaxCodeEntry>
        rowHeight={60}
        id={`staff.tax.codes.${current}`}
        label={`${data?.providerLabel ?? 'Provider'} codes`}
        columns={columns}
        data={data?.items ?? []}
        getRowId={(r) => r.internalTaxCode}
        rowLabel={(r) => r.outcomeName}
        state={state}
        mode="server"
        total={data?.total ?? null}
        loading={query.isPending}
        fetching={query.isFetching}
        error={query.isError ? <ErrorState error={query.error} onRetry={() => void query.refetch()} /> : undefined}
        searchPlaceholder="Search outcomes, categories or codes"
        enableSelection={false}
        onRowClick={data?.canEdit ? (r) => r.state !== 'NOT_NEEDED' && setEditing(r) : undefined}
        empty="No outcome matches these filters."
      />
      {editing ? <EditCodeDialog entry={editing} onClose={() => setEditing(null)} /> : null}
      {importing && data ? <ImportDialog provider={data.provider} label={data.providerLabel} onClose={() => setImporting(false)} /> : null}
    </div>
  );
}

function EditCodeDialog({ entry, onClose }: { entry: TaxCodeEntry; onClose: () => void }) {
  const api = staffApi();
  const qc = useQueryClient();
  const toast = useToast();
  const provider = entry.provider === 'AVALARA' ? 'AVALARA' : 'STRIPE';
  const [code, setCode] = useState(entry.providerCode);
  const [verified, setVerified] = useState(entry.verified);
  const [reason, setReason] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [fields, setFields] = useState<Record<string, string>>({});
  const run = useMutation({
    mutationFn: () => api.tax.updateCode(entry.internalTaxCode, { provider, providerCode: code.trim(), verified: code.trim() ? verified : false, reason: reason.trim() }),
    retry: false,
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: staffTaxKeys.all });
      toast.show(code.trim() ? `${entry.outcomeName}: code saved.` : `${entry.outcomeName}: code cleared.`);
      onClose();
    },
    onError: (e) => {
      setFields(isApiError(e) ? e.fieldErrors : {});
      setError(problemMessage(e));
    }
  });
  return (
    <ActionDialog
      open
      title={`Edit the ${provider === 'AVALARA' ? 'Avalara' : 'Stripe'} code`}
      intro={
        <p className="m-0">
          <strong className="font-semibold">{entry.outcomeName}</strong> in {entry.categoryName}: {entry.outcomeLabel}. Leave the code empty to clear it. Verify it only after checking that the provider treats this code as this outcome.
        </p>
      }
      submitLabel="Save code"
      reasonLabel="Reason"
      reasonHelp="Where the code comes from and what you checked. Kept with your name."
      reason={reason}
      onReason={setReason}
      pending={run.isPending}
      error={error}
      onSubmit={() => run.mutate()}
      onClose={onClose}
    >
      <TextField label="Provider code" value={code} maxLength={40} error={fields.providerCode} help={provider === 'STRIPE' ? 'Looks like txcd_40040016.' : 'Letters and digits, e.g. PF050099.'} onChange={(e) => setCode(e.currentTarget.value)} className="font-mono" />
      <label className="flex items-start gap-2 text-sm">
        <input type="checkbox" className="mt-1" checked={verified && code.trim() !== ''} disabled={code.trim() === ''} onChange={(e) => setVerified(e.currentTarget.checked)} />
        <span>
          <strong className="font-semibold">Verified</strong>
          <span className="block text-muted">A person confirmed the provider treats this code as this outcome. Unverified codes block lines at checkout.</span>
          {fields.verified ? <span className="block text-danger">{fields.verified}</span> : null}
        </span>
      </label>
    </ActionDialog>
  );
}

function ImportDialog({ provider, label, onClose }: { provider: TaxProvider; label: string; onClose: () => void }) {
  const api = staffApi();
  const qc = useQueryClient();
  const toast = useToast();
  const file = useRef<HTMLInputElement>(null);
  const [csv, setCsv] = useState('');
  const [name, setName] = useState('');
  const [reason, setReason] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [preview, setPreview] = useState<TaxCodeMapImportResult | null>(null);
  const check = useMutation({
    mutationFn: (text: string) => api.tax.importCodeMap({ provider, csv: text, dryRun: true }),
    onSuccess: setPreview,
    onError: (e) => {
      setPreview(null);
      setError(problemMessage(e));
    }
  });
  const apply = useMutation({
    mutationFn: () => api.tax.importCodeMap({ provider, csv, reason: reason.trim() }),
    retry: false,
    onSuccess: (r) => {
      void qc.invalidateQueries({ queryKey: staffTaxKeys.all });
      if (r.blocked || r.problems.length > 0) {
        setPreview(r);
        setError(r.blocked ? 'The file has rows that could not be used, so nothing was changed.' : 'Some rows could not be saved. The others were.');
        return;
      }
      toast.show(`${r.applied} ${label} code${r.applied === 1 ? '' : 's'} imported.`);
      onClose();
    },
    onError: (e) => setError(problemMessage(e))
  });
  const read = async (f: File | undefined) => {
    setError(null);
    setPreview(null);
    if (!f) return;
    const text = await f.text();
    setCsv(text);
    setName(f.name);
    check.mutate(text);
  };
  const ready = preview !== null && preview.problems.length === 0 && preview.changed > 0;
  return (
    <ActionDialog
      open
      title={`Import ${label} codes from CSV`}
      intro={<p className="m-0">Use a file exported from here: the columns <span className={mono}>internal_tax_code</span>, <span className={mono}>provider_code</span> and <span className={mono}>verified</span> are read. You see what would change before anything is saved, and a file with a bad row is not applied at all.</p>}
      submitLabel={preview ? `Import ${preview.changed} change${preview.changed === 1 ? '' : 's'}` : 'Import'}
      reasonLabel="Reason"
      reasonHelp="Where the file comes from. Kept with your name for every code it changes."
      reason={reason}
      onReason={setReason}
      extraValid={ready}
      pending={apply.isPending}
      error={error}
      onSubmit={() => apply.mutate()}
      onClose={onClose}
    >
      <div className="flex flex-col gap-1">
        <label htmlFor="tax-import-file" className="text-sm font-semibold">
          CSV file
        </label>
        <input ref={file} id="tax-import-file" type="file" accept=".csv,text/csv" onChange={(e) => void read(e.currentTarget.files?.[0])} className="text-sm" />
        {name ? <span className="text-xs text-muted">{name}</span> : null}
      </div>
      {check.isPending ? <p className="m-0 text-sm text-muted">Checking the file…</p> : null}
      {preview ? <ImportPreview r={preview} /> : null}
    </ActionDialog>
  );
}

function ImportPreview({ r }: { r: TaxCodeMapImportResult }) {
  return (
    <div className="flex flex-col gap-2 text-sm" aria-live="polite">
      <p className="m-0">
        {r.rows} row{r.rows === 1 ? '' : 's'} read: {r.changed} would change, {r.unchanged} already match.
      </p>
      {r.problems.length > 0 ? (
        <Notice tone="danger" title={`${r.problems.length} row${r.problems.length === 1 ? '' : 's'} can’t be used`}>
          <ul className="m-0 list-disc ps-5">
            {r.problems.slice(0, 8).map((p) => (
              <li key={`${p.line}-${p.internalTaxCode}`}>
                Line {p.line}: {p.message}
              </li>
            ))}
          </ul>
        </Notice>
      ) : null}
      {r.preview.length > 0 ? (
        <ul className="m-0 max-h-40 list-none overflow-auto p-0">
          {r.preview.slice(0, 20).map((p) => (
            <li key={p.line} className={`${mono} py-0.5`}>
              {p.internalTaxCode}: {p.from || 'no code'} → {p.to || 'cleared'} {p.to ? (p.verified ? '(verified)' : '(not verified)') : ''}
            </li>
          ))}
        </ul>
      ) : null}
    </div>
  );
}
