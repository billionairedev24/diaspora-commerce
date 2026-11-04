'use client';

import { isApiError, sellerKeys, sellerQueries,type SizeChart, type SizeChartUnit } from '@afrotransact/api-client';
import { Button, ChipGroup, Dialog, Select, Sheet, TextField, useToast } from '@afrotransact/ui';
import { Plus, Trash } from '@phosphor-icons/react/ssr';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { type ClipboardEvent, useEffect, useMemo, useRef, useState } from 'react';

import { leavesOf } from '@/components/listings/form-model';
import { RequestDepartmentLink } from '@/components/listings/RequestDepartment';
import { Notice } from '@/components/ui/Notice';
import { problemMessage } from '@/components/ui/States';
import { type MessageKey, useT } from '@/i18n/t';
import { sellerApi } from '@/lib/api';
import { useSeller } from '@/lib/session';

import {
  addColumn, addRow, addSizes, buildInput, columnKey, type EditorErrors, type EditorState, emptyEditor, MAX_COLUMNS, MAX_HOW, MAX_ROWS, missingSizes, pasteRows, previewChart, removeColumn,
  removeRow, sameState, startState, stateOf, withUnit
} from './chart-model';
import { SizeTable, UnitSwitch } from './SizeTable';

const FIT_NOTES = ['RUNS_SMALL', 'TRUE_TO_SIZE', 'RUNS_LARGE'] as const;
const UNITS: { value: SizeChartUnit; label: string }[] = [{ value: 'CM', label: 'cm' }, { value: 'IN', label: 'in' }];

interface Props {
  open: boolean;
  /** The chart to change; null writes a new one. */
  chart: SizeChart | null;
  /** A product type the chart is for (opened from the product form): its code and name. Without one, the seller chooses the category. */
  scope?: { productType: string; label: string };
  /** Sizes the product being edited sells: the new chart starts with a row for each, and a missing one is offered. */
  selling?: readonly string[];
  onClose: () => void;
  onSaved: (chart: SizeChart) => void;
}

/** A side sheet to write or change a size chart: name, unit, the measurements (columns), one row per size, how it fits and how to measure,
    with the table the shopper will read below. Closing with unsaved changes asks first. */
export function SizeChartEditor({ open, ...rest }: Props) {
  return open ? <EditorShell key={rest.chart ? `${rest.chart.id}:${rest.chart.version}` : 'new'} {...rest} /> : null;
}

function EditorShell({ chart, scope, selling = [], onClose, onSaved }: Omit<Props, 'open'>) {
  const t = useT();
  const api = sellerApi();
  const qc = useQueryClient();
  const toast = useToast();
  const { orgId } = useSeller();
  const tree = useQuery(sellerQueries.categories(api, orgId));
  const leaves = useMemo(() => leavesOf(tree.data ?? []), [tree.data]);

  const initial = useMemo<EditorState>(() => (chart ? stateOf(chart) : { ...emptyEditor(), productTypeCode: scope?.productType ?? '' }), [chart, scope?.productType]);
  const [s, setS] = useState<EditorState>(initial);
  const [category, setCategory] = useState(chart?.categoryId ?? '');
  const [tried, setTried] = useState(false);
  const [server, setServer] = useState<EditorErrors>({});
  const [conflict, setConflict] = useState(false);
  const [confirmClose, setConfirmClose] = useState(false);
  const [pasteText, setPasteText] = useState('');
  const [pasteNote, setPasteNote] = useState('');
  const [previewUnit, setPreviewUnit] = useState<SizeChartUnit | null>(null);
  const edited = useRef(false);

  // What a category suggests (measurements, sizes, unit) starts a new chart, until the seller has typed anything of their own.
  const productTypeId = !scope && !chart ? category : '';
  const productType = scope?.productType ?? '';
  const sug = useQuery({ ...sellerQueries.sizeChartSuggestions(api, productTypeId ? { productTypeId } : { productType }), enabled: !chart && (productTypeId !== '' || productType !== '') });
  const sellingKey = selling.join('\u0000');
  useEffect(() => {
    if (chart || !sug.data || edited.current) return;
    const code = scope?.productType ?? leaves.find((l) => l.id === category)?.productType ?? '';
    setS((prev) => ({ ...startState(sug.data, code, sellingKey === '' ? [] : sellingKey.split('\u0000')), name: prev.name }));
  }, [chart, sug.data, scope?.productType, category, leaves, sellingKey]);

  const change = (fn: (prev: EditorState) => EditorState) => {
    edited.current = true;
    setServer({});
    setS(fn);
  };
  const dirty = !sameState(s, initial);
  const built = useMemo(() => buildInput(s, t, chart?.version), [s, t, chart?.version]);
  const errors: EditorErrors = { ...(tried ? built.errors : {}), ...server };

  const save = useMutation({
    mutationFn: () => (chart ? api.updateSizeChart(chart.id, built.input) : api.createSizeChart(built.input)),
    onSuccess: (saved) => {
      void qc.invalidateQueries({ queryKey: sellerKeys.sizeCharts(orgId) });
      toast.show(t(chart ? 'sc.sz.toast.saved' : 'sc.sz.toast.created', { name: saved.name }));
      onSaved(saved);
    },
    onError: (e) => {
      if (isApiError(e) && e.status === 409) {
        setConflict(true);
        return;
      }
      if (isApiError(e) && Object.keys(e.fieldErrors).length) {
        setServer(e.fieldErrors);
        return;
      }
      toast.show(problemMessage(e), 'error');
    }
  });
  const submit = () => {
    setTried(true);
    if (Object.keys(built.errors).length) {
      const first = document.querySelector<HTMLElement>('[data-sz-invalid="true"]');
      first?.focus();
      return;
    }
    save.mutate();
  };
  const requestClose = () => (dirty ? setConfirmClose(true) : onClose());
  const reload = async () => {
    const fresh = await api.getSizeChart(chart?.id ?? '');
    void qc.invalidateQueries({ queryKey: sellerKeys.sizeCharts(orgId) });
    setS(stateOf(fresh));
    setConflict(false);
    onSaved(fresh);
  };

  const type = scope ?? (leaves.find((l) => l.id === category) ? { productType: leaves.find((l) => l.id === category)?.productType ?? '', label: leaves.find((l) => l.id === category)?.name ?? '' } : null);
  const suggestedSizes = sug.data?.sizes ?? [];
  const absent = missingSizes(s, selling);
  const shownUnit = previewUnit ?? s.unit;
  const cellError = (ri: number, ci: number) => errors[`rows[${ri}].values.${columnKey({ key: s.columns[ci]?.key ?? '', label: s.columns[ci]?.label.trim() ?? '' })}`];
  const invalid = (msg?: string) => (msg ? { 'aria-invalid': true as const, 'data-sz-invalid': 'true' } : {});

  return (
    <Sheet open onOpenChange={(o) => { if (!o) requestClose(); }} title={chart ? t('sc.sz.editTitle', { name: chart.name }) : t('sc.sz.newTitle')} closeLabel={t('sc.sz.close')}>
      <div className="flex flex-col gap-5 pb-4" data-testid="size-chart-editor">
        {conflict ? (
          <Notice tone="warning" title={t('sc.sz.conflictTitle')} role="alert" action={<Button size="sm" variant="neutral" onClick={() => void reload()}>{t('sc.sz.conflictReload')}</Button>}>
            {t('sc.sz.conflictBody')}
          </Notice>
        ) : null}
        {errors.rows && !errors['rows[0].size'] ? <Notice tone="danger" title={t('sc.sz.err.rowsTitle')} role="alert">{errors.rows}</Notice> : null}
        {chart && chart.productCount > 0 ? <Notice tone="info" title={t('sc.sz.usedBy', { count: chart.productCount })}>{t('sc.sz.usedByBody')}</Notice> : null}

        <div className="grid grid-cols-1 gap-x-4 gap-y-1 sm:grid-cols-2">
          <TextField id="sz-name" label={t('sc.sz.name')} value={s.name} maxLength={90} error={errors.name} placeholder={t('sc.sz.namePlaceholder')} {...invalid(errors.name)}
            onChange={(e) => change((p) => ({ ...p, name: e.target.value }))} />
          {scope ? (
            <div className="flex flex-col gap-1">
              <span className="text-sm font-semibold">{t('sc.sz.madeFor')}</span>
              <span className="flex min-h-10 items-center text-sm">{scope.label}</span>
            </div>
          ) : (
            <Select id="sz-category" label={t('sc.sz.madeFor')} value={category} disabled={Boolean(chart)} help={chart ? undefined : t('sc.sz.madeForHelp')}
              options={leaves.map((l) => ({ value: l.id, label: l.name }))} placeholder={chart ? (chart.productTypeCode ?? t('sc.sz.anyCategory')) : t('sc.sz.anyCategory')}
              onChange={(e) => {
                setCategory(e.target.value);
                const l = leaves.find((x) => x.id === e.target.value);
                setS((p) => ({ ...p, productTypeCode: l?.productType ?? '' }));
              }} />
          )}
        </div>
        {chart ? null : <RequestDepartmentLink />}
        {!chart && type && sug.data && !sug.data.sized ? <Notice tone="info" title={t('sc.sz.notSized', { type: type.label })}>{t('sc.sz.notSizedBody')}</Notice> : null}

        <div className="flex flex-wrap items-end gap-4">
          <div className="flex flex-col gap-1.5">
            <span className="text-sm font-semibold">{t('sc.sz.unit')}</span>
            <ChipGroup<SizeChartUnit> label={t('sc.sz.unit')} options={UNITS} value={s.unit} onChange={(u) => change((p) => withUnit(p, u))} />
          </div>
          <div className="min-w-48 flex-1">
            <Select id="sz-fit" label={t('sc.pf.field.fitNote')} value={s.fitNote} options={[{ value: '', label: t('sc.sz.fitNone') }, ...FIT_NOTES.map((f) => ({ value: f, label: t(`sc.pf.fit.${f}` as MessageKey) }))]}
              onChange={(e) => change((p) => ({ ...p, fitNote: FIT_NOTES.find((f) => f === e.target.value) ?? '' }))} />
          </div>
        </div>

        {absent.length ? (
          <Notice tone="warning" title={t('sc.sz.missingTitle', { count: absent.length })} action={<Button size="sm" variant="neutral" onClick={() => change((p) => addSizes(p, absent))}>{t('sc.sz.missingAdd')}</Button>}>
            {absent.join(', ')}
          </Notice>
        ) : null}

        <section aria-labelledby="sz-grid-h" className="flex flex-col gap-2">
          <div className="flex flex-wrap items-center justify-between gap-2">
            <h3 id="sz-grid-h" className="m-0 text-base font-semibold">{t('sc.sz.gridTitle', { unit: s.unit === 'CM' ? 'cm' : 'in' })}</h3>
            <span className="text-xs text-muted">{t('sc.sz.gridHint')}</span>
          </div>
          <div className="overflow-x-auto rounded-md border border-border">
            <table className="w-full border-collapse text-sm" aria-label={t('sc.sz.gridLabel')}>
              <thead>
                <tr className="bg-surface-muted align-bottom">
                  <th scope="col" className="w-32 min-w-28 px-2 py-2 text-left text-xs font-semibold">{t('sc.pf.sizeChart.size')}</th>
                  {s.columns.map((c, ci) => {
                    const e = errors[`columns[${ci}].label`];
                    return (
                      <th key={ci} scope="col" className="min-w-32 px-2 py-2 text-left align-top">
                        <div className="flex items-start gap-1">
                          <input aria-label={t('sc.sz.measurementN', { n: ci + 1 })} value={c.label} maxLength={50} placeholder={t('sc.sz.measurementPlaceholder')} {...invalid(e)}
                            onChange={(ev) => change((p) => ({ ...p, columns: p.columns.map((x, i) => (i === ci ? { ...x, label: ev.target.value } : x)) }))}
                            className={`h-8 w-full min-w-0 rounded-sm border bg-surface px-2 text-sm font-semibold ${e ? 'border-danger' : 'border-border-strong'}`} />
                          {s.columns.length > 1 ? (
                            <button type="button" aria-label={t('sc.sz.removeMeasurement', { name: c.label || String(ci + 1) })} onClick={() => change((p) => removeColumn(p, ci))}
                              className="inline-flex size-8 flex-none items-center justify-center rounded-sm border-0 bg-transparent text-muted hover:bg-surface-hover"><Trash size={14} aria-hidden /></button>
                          ) : null}
                        </div>
                        {e ? <span role="alert" className="mt-0.5 block text-2xs font-normal text-danger">{e}</span> : null}
                      </th>
                    );
                  })}
                  <th scope="col" className="w-10 px-1"><span className="at-visually-hidden">{t('sc.sz.rowActions')}</span></th>
                </tr>
              </thead>
              <tbody>
                {s.rows.map((r, ri) => {
                  const se = errors[`rows[${ri}].size`];
                  return (
                    <tr key={ri} className="border-t border-border align-top" data-testid="size-row">
                      <th scope="row" className="px-2 py-1.5 text-left font-normal">
                        <input aria-label={t('sc.sz.sizeN', { n: ri + 1 })} value={r.size} maxLength={40} placeholder={t('sc.sz.sizePlaceholder')} {...invalid(se)}
                          onChange={(ev) => change((p) => ({ ...p, rows: p.rows.map((x, i) => (i === ri ? { ...x, size: ev.target.value } : x)) }))}
                          className={`h-8 w-full min-w-0 rounded-sm border bg-surface px-2 text-sm font-semibold ${se ? 'border-danger' : 'border-border-strong'}`} />
                        {se ? <span role="alert" className="mt-0.5 block text-2xs text-danger">{se}</span> : null}
                      </th>
                      {s.columns.map((c, ci) => {
                        const ce = cellError(ri, ci);
                        return (
                          <td key={ci} className="px-2 py-1.5">
                            <input aria-label={t('sc.sz.cellOf', { measurement: c.label.trim() || String(ci + 1), size: r.size.trim() || String(ri + 1) })} inputMode="decimal" value={r.cells[ci] ?? ''} {...invalid(ce)}
                              placeholder={t('sc.sz.cellPlaceholder')} maxLength={20}
                              onChange={(ev) => change((p) => ({ ...p, rows: p.rows.map((x, i) => (i === ri ? { ...x, cells: x.cells.map((v, k) => (k === ci ? ev.target.value : v)) } : x)) }))}
                              className={`h-8 w-full min-w-0 rounded-sm border bg-surface px-2 text-sm tabular-nums ${ce ? 'border-danger' : 'border-border-strong'}`} />
                            {ce ? <span role="alert" className="mt-0.5 block text-2xs text-danger">{ce}</span> : null}
                          </td>
                        );
                      })}
                      <td className="px-1 py-1.5">
                        {s.rows.length > 1 ? (
                          <button type="button" aria-label={t('sc.sz.removeSize', { size: r.size || String(ri + 1) })} onClick={() => change((p) => removeRow(p, ri))}
                            className="inline-flex size-8 items-center justify-center rounded-sm border-0 bg-transparent text-muted hover:bg-surface-hover"><Trash size={14} aria-hidden /></button>
                        ) : null}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
          <div className="flex flex-wrap items-center gap-2">
            <Button size="sm" variant="neutral" disabled={s.rows.length >= MAX_ROWS} onClick={() => change((p) => addRow(p))}><Plus size={14} aria-hidden /> {t('sc.sz.addSize')}</Button>
            <Button size="sm" variant="neutral" disabled={s.columns.length >= MAX_COLUMNS} onClick={() => change((p) => addColumn(p))}><Plus size={14} aria-hidden /> {t('sc.sz.addMeasurement')}</Button>
            {suggestedSizes.filter((x) => !s.rows.some((r) => r.size.trim().toLowerCase() === x.toLowerCase())).slice(0, 8).map((x) => (
              <button key={x} type="button" onClick={() => change((p) => addSizes(p, [x]))}
                className="h-[30px] rounded-pill border border-border-strong bg-surface px-3 text-xs font-semibold hover:bg-surface-hover">+ {x}</button>
            ))}
          </div>
          <details className="text-sm">
            <summary className="cursor-pointer font-semibold text-link">{t('sc.sz.pasteTitle')}</summary>
            <div className="mt-2 flex flex-col gap-2">
              <p className="m-0 text-xs text-muted">{t('sc.sz.pasteHelp')}</p>
              <textarea aria-label={t('sc.sz.pasteTitle')} rows={4} value={pasteText} onChange={(e) => setPasteText(e.target.value)}
                onPaste={(e: ClipboardEvent<HTMLTextAreaElement>) => e.stopPropagation()} className="w-full rounded-sm border border-border-strong bg-surface p-2 font-mono text-xs" />
              <div className="flex flex-wrap items-center gap-2">
                <Button size="sm" variant="neutral" disabled={!pasteText.trim()} onClick={() => {
                  const r = pasteRows(s, pasteText);
                  change(() => r.state);
                  setPasteNote(t('sc.sz.pasteDone', { read: r.read, skipped: r.skipped }));
                  setPasteText('');
                }}>{t('sc.sz.pasteApply')}</Button>
                {pasteNote ? <span role="status" className="text-xs text-muted">{pasteNote}</span> : null}
              </div>
            </div>
          </details>
        </section>

        <div className="flex flex-col gap-1">
          <label htmlFor="sz-how" className="text-sm font-semibold">{t('sc.sz.how')}</label>
          <textarea id="sz-how" rows={3} value={s.howToMeasure} maxLength={MAX_HOW + 100} onChange={(e) => change((p) => ({ ...p, howToMeasure: e.target.value }))}
            placeholder={t('sc.sz.howPlaceholder')} {...invalid(errors.howToMeasure)} className="w-full rounded-sm border border-border-strong bg-surface p-2 text-sm" />
          <span className="flex justify-between text-xs text-muted"><span>{t('sc.sz.howHelp')}</span><span>{s.howToMeasure.length} / {MAX_HOW}</span></span>
          {errors.howToMeasure ? <span role="alert" className="text-xs text-danger">{errors.howToMeasure}</span> : null}
        </div>

        <section aria-labelledby="sz-prev-h" className="flex flex-col gap-2" data-testid="chart-preview">
          <div className="flex flex-wrap items-center justify-between gap-2">
            <h3 id="sz-prev-h" className="m-0 text-base font-semibold">{t('sc.pf.sizeChart.preview')}</h3>
            <UnitSwitch value={shownUnit} onChange={setPreviewUnit} />
          </div>
          <SizeTable chart={previewChart(s)} name={s.name.trim() || t('sc.sz.untitled')} unit={shownUnit} />
        </section>

        <div className="sticky bottom-0 -mx-1 flex flex-wrap items-center justify-end gap-2 border-t border-border bg-surface px-1 py-3">
          {tried && Object.keys(built.errors).length ? <span role="alert" className="mr-auto text-xs text-danger">{t('sc.sz.fixErrors', { count: Object.keys(built.errors).length })}</span> : null}
          <Button variant="neutral" onClick={requestClose}>{t('sc.sz.cancel')}</Button>
          <Button variant="primary" loading={save.isPending} disabled={Boolean(chart) && !dirty} onClick={submit}>{chart ? t('sc.sz.saveChanges') : t('sc.sz.create')}</Button>
        </div>
      </div>
      <Dialog open={confirmClose} onOpenChange={setConfirmClose} title={t('sc.sz.discardTitle')} description={t('sc.sz.discardBody')} closeLabel={t('sc.sz.close')} width={420}>
        <div className="flex justify-end gap-2 pt-2">
          <Button variant="neutral" onClick={() => setConfirmClose(false)}>{t('sc.sz.keepEditing')}</Button>
          <Button variant="primary" onClick={() => { setConfirmClose(false); onClose(); }}>{t('sc.sz.discard')}</Button>
        </div>
      </Dialog>
    </Sheet>
  );
}
