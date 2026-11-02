import {
  cleanValue,
  convertMeasurement,
  customValueProblem,
  type FitNote,
  formatMeasurement,
  measurementKey,
  parseMeasurement,
  type SizeChart,
  type SizeChartInput,
  type SizeChartSuggestions,
  type SizeChartUnit,
  type SizeTableChart,
  valueKey
} from '@afrotransact/api-client';

import { type MessageKey } from '@/i18n/t';

/* Pure state and rules of the size chart editor: the chart as the seller types it (every number is text, a range is
   "86-90"), its checks (the same limits as catalog, so the editor answers before saving), the request body, a unit
   change that converts what was typed, and pasted rows from a spreadsheet. Messages are i18n keys. */

export const MAX_COLUMNS = 12;
export const MAX_ROWS = 60;
export const MAX_NAME = 80;
export const MAX_LABEL = 40;
export const MAX_HOW = 1000;

export interface EditorColumn {
  /** The key catalog stores for an existing measurement ('' for a new one: derived from its label when saved). */
  key: string;
  label: string;
}

export interface EditorRow {
  size: string;
  /** One text per column, same order as the columns: "88" or "86-90". */
  cells: string[];
}

export interface EditorState {
  name: string;
  unit: SizeChartUnit;
  /** The product type the chart is made for ('' = any category). */
  productTypeCode: string;
  columns: EditorColumn[];
  rows: EditorRow[];
  fitNote: FitNote | '';
  howToMeasure: string;
}

export const emptyEditor = (): EditorState => ({ name: '', unit: 'CM', productTypeCode: '', columns: [{ key: '', label: '' }], rows: [{ size: '', cells: [''] }], fitNote: '', howToMeasure: '' });

/** A new chart for a product type: its suggested measurements as columns and one row per size, the sizes the product sells first. */
export function startState(suggestions: SizeChartSuggestions | null | undefined, productTypeCode: string, selling: readonly string[] = []): EditorState {
  if (!suggestions || !suggestions.sized) return { ...emptyEditor(), productTypeCode };
  const columns = suggestions.measurements.map((m): EditorColumn => ({ key: m.key, label: m.label }));
  const sizes: string[] = [];
  [...selling, ...suggestions.sizes].forEach((size) => {
    const clean = cleanValue(size);
    if (clean && !sizes.some((x) => valueKey(x) === valueKey(clean))) sizes.push(clean);
  });
  // Without sold sizes the whole suggested ladder would make a long empty table: start with what the category lists.
  return {
    ...emptyEditor(), unit: suggestions.unitSystem, productTypeCode, columns: columns.length ? columns : emptyEditor().columns,
    rows: (sizes.length ? sizes : ['']).map((size) => ({ size, cells: columns.map(() => '') }))
  };
}

/** The editor state of a saved chart (numbers written as the shopper reads them). */
export function stateOf(chart: SizeChart): EditorState {
  return {
    name: chart.name, unit: chart.unitSystem, productTypeCode: chart.productTypeCode ?? '', columns: chart.columns.map((c) => ({ key: c.key, label: c.label })),
    rows: chart.rows.map((r) => ({ size: r.size, cells: chart.columns.map((c) => (r.values[c.key] ? formatMeasurement(r.values[c.key] ?? { min: 0, max: 0 }) : '')) })),
    fitNote: chart.fitNote ?? '', howToMeasure: chart.howToMeasure ?? ''
  };
}

/** The key a column is stored under: the one it has, else the one derived from its label. */
export const columnKey = (c: EditorColumn): string => (c.key !== '' ? c.key : measurementKey(c.label));

export const sameState = (a: EditorState, b: EditorState): boolean => JSON.stringify(a) === JSON.stringify(b);

/* ── Changes ── */

const convertText = (text: string, from: SizeChartUnit, to: SizeChartUnit): string => {
  const v = parseMeasurement(text);
  if (!v) return text;
  return formatMeasurement({ min: convertMeasurement(v.min, from, to), max: convertMeasurement(v.max, from, to) });
};

/** Switching cm / in converts the numbers already typed (one decimal) so the chart keeps describing the same garment. */
export function withUnit(s: EditorState, unit: SizeChartUnit): EditorState {
  if (unit === s.unit) return s;
  return { ...s, unit, rows: s.rows.map((r) => ({ ...r, cells: r.cells.map((c) => convertText(c, s.unit, unit)) })) };
}

export const addColumn = (s: EditorState, label = ''): EditorState =>
  s.columns.length >= MAX_COLUMNS ? s : { ...s, columns: [...s.columns, { key: '', label }], rows: s.rows.map((r) => ({ ...r, cells: [...r.cells, ''] })) };

export function removeColumn(s: EditorState, index: number): EditorState {
  if (s.columns.length <= 1) return s;
  return { ...s, columns: s.columns.filter((_, i) => i !== index), rows: s.rows.map((r) => ({ ...r, cells: r.cells.filter((_, i) => i !== index) })) };
}

export const addRow = (s: EditorState, size = ''): EditorState =>
  s.rows.length >= MAX_ROWS ? s : { ...s, rows: [...s.rows, { size, cells: s.columns.map(() => '') }] };

export const removeRow = (s: EditorState, index: number): EditorState => (s.rows.length <= 1 ? s : { ...s, rows: s.rows.filter((_, i) => i !== index) });

/** Adds a row for each size not yet in the chart (the suggestion chips and "add the sizes I sell"). */
export function addSizes(s: EditorState, sizes: readonly string[]): EditorState {
  let next = s;
  sizes.forEach((size) => {
    const clean = cleanValue(size);
    if (clean && !next.rows.some((r) => valueKey(r.size) === valueKey(clean))) {
      // A still-blank row (a new chart's first one) takes the size instead of leaving an empty line above it.
      const blank = next.rows.findIndex((r) => r.size.trim() === '' && r.cells.every((c) => c.trim() === ''));
      next = blank >= 0 ? { ...next, rows: next.rows.map((r, i) => (i === blank ? { ...r, size: clean } : r)) } : addRow(next, clean);
    }
  });
  return next;
}

/** Rows pasted from a spreadsheet: one size per line, then one value per measurement, separated by tabs (or commas / semicolons). Returns the chart and how many rows were read. */
export function pasteRows(s: EditorState, text: string): { state: EditorState; read: number; skipped: number } {
  const lines = text.split(/\r?\n/).filter((l) => l.trim() !== '');
  const parsed = lines.map((line) => line.split(line.includes('\t') ? '\t' : /[;,]/).map((c) => c.trim()));
  // A first line that is a header (no number in it after the first cell) is not a size.
  const rows = parsed.filter((cells, i) => !(i === 0 && cells.length > 1 && cells.slice(1).every((c) => c !== '' && parseMeasurement(c) === null)));
  let next = s;
  let read = 0;
  let skipped = 0;
  rows.forEach((cells) => {
    const size = cleanValue(cells[0] ?? '');
    if (!size) {
      skipped += 1;
      return;
    }
    const values = s.columns.map((_, i) => cells[i + 1] ?? '');
    const at = next.rows.findIndex((r) => valueKey(r.size) === valueKey(size));
    if (at >= 0) next = { ...next, rows: next.rows.map((r, i) => (i === at ? { size: r.size, cells: values } : r)) };
    else if (next.rows.length >= MAX_ROWS) {
      skipped += 1;
      return;
    } else {
      const blank = next.rows.findIndex((r) => r.size.trim() === '' && r.cells.every((c) => c.trim() === ''));
      next = blank >= 0 ? { ...next, rows: next.rows.map((r, i) => (i === blank ? { size, cells: values } : r)) } : { ...next, rows: [...next.rows, { size, cells: values }] };
    }
    read += 1;
  });
  return { state: next, read, skipped };
}

/* ── Checks and request ── */

/** Problems by field, the way catalog names them: name, columns[1].label, rows[2].size, rows[2].values.bust, rows, columns. */
export type EditorErrors = Record<string, string>;

export interface Built {
  input: SizeChartInput;
  errors: EditorErrors;
}

const MAX_MEASURE = 1000;

/** Checks the chart and builds the request body (`version` is the one the editor loaded, for a saved chart). */
export function buildInput(s: EditorState, t: (key: MessageKey, vars?: Record<string, string | number>) => string, version?: number): Built {
  const errors: EditorErrors = {};
  const name = cleanValue(s.name);
  if ([...name].length < 2) errors.name = t('sc.sz.err.name');
  else if ([...name].length > MAX_NAME) errors.name = t('sc.sz.err.nameLong', { max: MAX_NAME });

  const keys = new Set<string>();
  const labels = new Set<string>();
  s.columns.forEach((c, i) => {
    const label = cleanValue(c.label);
    if (label === '') errors[`columns[${i}].label`] = t('sc.sz.err.measurement');
    else if ([...label].length > MAX_LABEL) errors[`columns[${i}].label`] = t('sc.sz.err.measurementLong', { max: MAX_LABEL });
    else if (labels.has(label.toLowerCase())) errors[`columns[${i}].label`] = t('sc.sz.err.measurementTwice', { label });
    else {
      const key = columnKey({ key: c.key, label });
      if (key === '' || keys.has(key)) errors[`columns[${i}].label`] = t('sc.sz.err.measurementKey');
      keys.add(key);
    }
    labels.add(label.toLowerCase());
  });
  if (!s.columns.length) errors.columns = t('sc.sz.err.noColumns');

  const sizes = new Set<string>();
  s.rows.forEach((r, i) => {
    const size = cleanValue(r.size);
    if (size === '') errors[`rows[${i}].size`] = t('sc.sz.err.size');
    else {
      const problem = customValueProblem('Size', size);
      if (problem) errors[`rows[${i}].size`] = [...size].length > 30 ? t('sc.sz.err.sizeLong') : t('sc.sz.err.sizeChars');
      else if (sizes.has(valueKey(size))) errors[`rows[${i}].size`] = t('sc.sz.err.sizeTwice', { size });
      sizes.add(valueKey(size));
    }
    s.columns.forEach((c, ci) => {
      const key = columnKey({ key: c.key, label: cleanValue(c.label) });
      const text = r.cells[ci] ?? '';
      const field = `rows[${i}].values.${key}`;
      if (text.trim() === '') {
        errors[field] = t('sc.sz.err.valueEmpty', { measurement: cleanValue(c.label).toLowerCase() || t('sc.sz.measurement'), size: size || String(i + 1) });
        return;
      }
      const v = parseMeasurement(text);
      if (!v) errors[field] = t('sc.sz.err.valueFormat');
      else if (v.min <= 0 || v.max > MAX_MEASURE) errors[field] = t('sc.sz.err.valueRange', { max: MAX_MEASURE });
      else if (v.min > v.max) errors[field] = t('sc.sz.err.valueOrder', { min: v.min, max: v.max });
    });
  });
  if (!s.rows.length) errors.rows = t('sc.sz.err.noRows');
  if (s.howToMeasure.length > MAX_HOW) errors.howToMeasure = t('sc.sz.err.howLong', { max: MAX_HOW });

  const input: SizeChartInput = {
    name,
    unitSystem: s.unit,
    productTypeCode: s.productTypeCode || null,
    columns: s.columns.map((c) => ({ key: columnKey({ key: c.key, label: cleanValue(c.label) }) || undefined, label: cleanValue(c.label) })),
    rows: s.rows.map((r) => ({
      size: cleanValue(r.size),
      values: Object.fromEntries(
        s.columns.flatMap((c, ci) => {
          const v = parseMeasurement(r.cells[ci] ?? '');
          return v ? [[columnKey({ key: c.key, label: cleanValue(c.label) }), v.min === v.max ? { min: v.min } : { min: v.min, max: v.max }]] : [];
        })
      )
    })),
    fitNote: s.fitNote || null,
    howToMeasure: s.howToMeasure.trim() || null,
    ...(version === undefined ? {} : { version })
  };
  return { input, errors };
}

/** A chart-shaped view of the editor for the preview table (rows whose numbers are not valid yet show a dash). */
export function previewChart(s: EditorState): SizeTableChart {
  return {
    unitSystem: s.unit,
    columns: s.columns.map((c) => ({ key: columnKey({ key: c.key, label: cleanValue(c.label) }) || c.label, label: cleanValue(c.label) || '–' })),
    rows: s.rows.filter((r) => r.size.trim() !== '').map((r) => ({
      size: cleanValue(r.size),
      values: Object.fromEntries(s.columns.flatMap((c, ci) => {
        const v = parseMeasurement(r.cells[ci] ?? '');
        return v ? [[columnKey({ key: c.key, label: cleanValue(c.label) }) || c.label, v]] : [];
      }))
    }))
  };
}

/** The sizes of `wanted` that the chart has no row for (the sizes the product being edited sells). */
export const missingSizes = (s: EditorState, wanted: readonly string[]): string[] =>
  wanted.filter((w, i) => w.trim() !== '' && wanted.findIndex((x) => valueKey(x) === valueKey(w)) === i && !s.rows.some((r) => valueKey(r.size) === valueKey(w)));
