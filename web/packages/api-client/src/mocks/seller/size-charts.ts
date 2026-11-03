/* Mock state and rules of the seller's size charts: a few realistic charts (women's dresses, men's tops, shoes), the same
   rules as catalog's SizeChartRules (names, columns, rows, measurements), the product link rules (a chart where the
   category requires one, a row for every size the variants sell) and a CSV size chart upload with dry run and update by
   name, so Seller Center can be built and tested without the services. */
import { type ProductSizeChart } from '../../schemas/catalog';
import {
  type FitNote,
  type ProductDraft,
  type ProductTypeSchema,
  type SizeChart,
  type SizeChartColumn,
  type SizeChartImportChart,
  type SizeChartImportReport,
  type SizeChartImportRow,
  type SizeChartInput,
  type SizeChartRow,
  type SizeChartSuggestions,
  type SizeChartUnit,
  type SizeChartValue
} from '../../schemas/seller';
import { cleanValue, customValueProblem, parseMeasurement, sizesMissingFromChart, valueKey } from '../../size-charts';
import { parseCsv, toCsv } from './listing-form';

export interface MockSizeChart extends SizeChart {
  orgId: string;
}

const CM_PER_INCH = 2.54;
const round1 = (n: number) => Math.round(n * 10) / 10;
const round2 = (n: number) => Math.round(n * 100) / 100;

/** The other unit's value of a measurement (one decimal), as catalog converts for `alternateValues`. */
function alternate(v: SizeChartValue, from: SizeChartUnit): SizeChartValue {
  const to = (n: number) => round1(from === 'CM' ? n / CM_PER_INCH : n * CM_PER_INCH);
  return { min: to(v.min), max: to(v.max) };
}

const range = (min: number, max?: number): SizeChartValue => ({ min, max: max ?? min });

function chart(orgId: string, id: string, name: string, unit: SizeChartUnit, productTypeCode: string, categoryId: string | null, columns: Array<[string, string]>,
  rows: Array<[string, Record<string, SizeChartValue>]>, fitNote: FitNote | null, howToMeasure: string | null, productCount: number, at: string): MockSizeChart {
  const cols: SizeChartColumn[] = columns.map(([key, label]) => ({ key, label, unit }));
  return {
    orgId, id, ownerOrgId: orgId, name, unitSystem: unit, alternateUnit: unit === 'CM' ? 'IN' : 'CM', productTypeCode, categoryId, columns: cols,
    rows: rows.map(([size, values]) => ({ size, values, alternateValues: Object.fromEntries(Object.entries(values).map(([k, v]) => [k, alternate(v, unit)])) })),
    fitNote, howToMeasure, retired: false, retiredAt: null, productCount, createdAt: at, updatedAt: at, version: 0
  };
}

/** Fixtures: the organisation's own charts. Their ids are stable so tests and stories can name them. */
export const SIZE_CHART_IDS = {
  dresses: '5c1e0000-0000-4000-8000-0000000000a1',
  mensTops: '5c1e0000-0000-4000-8000-0000000000a2',
  shoes: '5c1e0000-0000-4000-8000-0000000000a3'
} as const;

export function seedSizeCharts(orgId: string, now: Date): MockSizeChart[] {
  const at = new Date(now.getTime() - 9 * 86_400_000).toISOString();
  return [
    chart(orgId, SIZE_CHART_IDS.dresses, 'Adire dresses', 'CM', 'dress', 'pt-dresses', [['bust', 'Bust'], ['waist', 'Waist'], ['hip', 'Hip']], [
      ['XS', { bust: range(80, 83), waist: range(62, 65), hip: range(88, 91) }],
      ['S', { bust: range(84, 87), waist: range(66, 69), hip: range(92, 95) }],
      ['M', { bust: range(88, 92), waist: range(70, 74), hip: range(96, 100) }],
      ['L', { bust: range(93, 98), waist: range(75, 80), hip: range(101, 106) }],
      ['XL', { bust: range(99, 105), waist: range(81, 88), hip: range(107, 113) }],
      ['UK 12', { bust: range(89, 93), waist: range(71, 75), hip: range(97, 101) }],
      ['Free size', { bust: range(88, 110), waist: range(70, 96), hip: range(96, 120) }]
    ], 'TRUE_TO_SIZE', 'Measure over light clothing, keeping the tape level and snug but not tight. For the bust, measure around the fullest part.', 3, at),
    chart(orgId, SIZE_CHART_IDS.mensTops, 'Kaftans and shirts', 'IN', 'menwear', 'pt-mens-tops', [['chest', 'Chest'], ['waist', 'Waist'], ['sleeve_length', 'Sleeve length']], [
      ['S', { chest: range(35, 37), waist: range(29, 31), sleeve_length: range(24.5) }],
      ['M', { chest: range(38, 40), waist: range(32, 34), sleeve_length: range(25) }],
      ['L', { chest: range(41, 43), waist: range(35, 37), sleeve_length: range(25.5) }],
      ['XL', { chest: range(44, 46), waist: range(38, 40), sleeve_length: range(26) }],
      ['2XL', { chest: range(47, 49), waist: range(41, 43), sleeve_length: range(26.5) }]
    ], 'RUNS_LARGE', null, 1, at),
    chart(orgId, SIZE_CHART_IDS.shoes, 'Leather sandals', 'CM', 'shoes', 'pt-shoes', [['foot_length', 'Foot length']], [
      ['EU 36', { foot_length: range(22.5, 23) }],
      ['EU 37', { foot_length: range(23.5, 24) }],
      ['EU 38', { foot_length: range(24.5, 25) }],
      ['EU 39', { foot_length: range(25.5, 26) }],
      ['EU 40', { foot_length: range(26.5, 27) }]
    ], 'RUNS_SMALL', 'Stand on a sheet of paper and mark the heel and the longest toe; measure between the marks. Choose the size whose range holds your length; between two sizes, take the larger.', 0, at)
  ];
}

/** The chart as the storefront product page gets it (catalog PublicProduct.sizeChart): no owner, no ids, the product's fit note. */
export function publicSizeChart(chart: SizeChart, fitNote?: FitNote | null): ProductSizeChart {
  return {
    name: chart.name, unitSystem: chart.unitSystem, alternateUnit: chart.alternateUnit, columns: chart.columns, rows: chart.rows,
    fitNote: fitNote ?? chart.fitNote, howToMeasure: chart.howToMeasure
  };
}

/* ── Rules (catalog SizeChartRules) ── */

export const slugKey = (label: string): string => {
  const key = label.normalize('NFD').replace(/\p{M}+/gu, '').toLowerCase().replace(/[^a-z0-9]+/g, '_').replace(/^_+|_+$/g, '');
  if (key === '') return '';
  return (/^[a-z]/.test(key) ? key : `m_${key}`).slice(0, 40);
};

export interface BuiltChart {
  columns: SizeChartColumn[];
  rows: SizeChartRow[];
}

/** Checks a chart as catalog does and returns the problems by field (`name`, `rows[1].values.bust` ...). */
export function chartProblems(input: SizeChartInput, others: readonly SizeChart[], ownId?: string): { errors: Record<string, string>; built: BuiltChart } {
  const errors: Record<string, string> = {};
  const name = cleanValue(input.name);
  if (others.some((c) => c.id !== ownId && !c.retired && valueKey(c.name) === valueKey(name))) errors.name = `You already have a size chart called ${name}`;
  const columns: SizeChartColumn[] = [];
  const keys = new Set<string>();
  const labels = new Set<string>();
  input.columns.forEach((c, i) => {
    const label = cleanValue(c.label);
    if (labels.has(label.toLowerCase())) errors[`columns[${i}].label`] = `Two measurements are called ${label}`;
    labels.add(label.toLowerCase());
    const key = c.key && c.key.trim() !== '' ? c.key.trim() : slugKey(label);
    if (!/^[a-z][a-z0-9_]{0,39}$/.test(key)) errors[`columns[${i}].key`] = 'Lower-case letters, digits and _, starting with a letter';
    else if (keys.has(key)) errors[`columns[${i}].key`] = 'Used twice';
    keys.add(key);
    if (c.unit && c.unit !== input.unitSystem) errors[`columns[${i}].unit`] = `Use the chart's unit (${input.unitSystem === 'CM' ? 'cm' : 'in'})`;
    columns.push({ key, label, unit: input.unitSystem });
  });
  const sizes = new Set<string>();
  const rows: SizeChartRow[] = input.rows.map((r, i) => {
    const size = cleanValue(r.size);
    const problem = customValueProblem('Size', size);
    if (problem) errors[`rows[${i}].size`] = problem;
    else if (sizes.has(valueKey(size))) errors[`rows[${i}].size`] = `Duplicate size ${size} (sizes are compared ignoring case)`;
    sizes.add(valueKey(size));
    const values: Record<string, SizeChartValue> = {};
    columns.forEach((c) => {
      const given = r.values[c.key];
      if (!given) {
        errors[`rows[${i}].values.${c.key}`] = `Enter the ${c.label.toLowerCase()} of size ${size}`;
        return;
      }
      const min = round2(given.min);
      const max = round2(given.max ?? given.min);
      if (min > max) errors[`rows[${i}].values.${c.key}`] = `The smallest value (${min}) is above the largest (${max})`;
      else values[c.key] = { min, max };
    });
    Object.keys(r.values).forEach((k) => {
      if (!keys.has(k)) errors[`rows[${i}].values.${k}`] = 'Not a measurement of this chart';
    });
    return { size, values, alternateValues: Object.fromEntries(Object.entries(values).map(([k, v]) => [k, alternate(v, input.unitSystem)])) };
  });
  return { errors, built: { columns, rows } };
}

/** The product's size rules (catalog DefaultSizeChartPolicy): a chart where required, the organisation's own, a row for every size sold. */
export function sizeProblems(
  d: ProductDraft,
  type: ProductTypeSchema | undefined,
  charts: readonly MockSizeChart[],
  orgId: string,
  linkedChartId?: string | null
): Record<string, string> {
  const errs: Record<string, string> = {};
  const sizing = type?.traits.sizing;
  const chartId = d.listing?.sizeChartId ?? null;
  if (!sizing) {
    if (chartId && chartId !== linkedChartId) errs['listing.sizeChartId'] = `Size charts are not used for ${type?.label ?? 'this category'}`;
    return errs;
  }
  if (!chartId) {
    if (sizing.sizeChartRequired) errs['listing.sizeChartId'] = `Add a size chart: shoppers need it to choose a size for ${type?.label ?? 'this category'}`;
    return errs;
  }
  const found = charts.find((c) => c.id === chartId);
  if (!found || found.orgId !== orgId) {
    errs['listing.sizeChartId'] = 'Size chart not found';
    return errs;
  }
  if (found.retired && found.id !== linkedChartId) {
    errs['listing.sizeChartId'] = `The size chart "${found.name}" was retired; choose another or create a new one`;
    return errs;
  }
  sizesMissingFromChart(found, sizing, d.variants).forEach((p) => {
    errs[`variants[${p.variantIndex}].options.${sizing.sizeDimension}`] = p.message;
  });
  return errs;
}

export function suggestionsFor(type: ProductTypeSchema): SizeChartSuggestions {
  const sizing = type.traits.sizing;
  if (!sizing) {
    return { productTypeCode: type.code, productTypeLabel: type.label, sized: false, sizeChartRequired: false, sizeDimension: 'Size', sizes: [], sizesAllowCustomValues: false, unitSystem: 'CM', measurements: [] };
  }
  const sizes: string[] = [];
  type.themes.forEach((t) =>
    t.dimensions.filter((d) => d.name === sizing.sizeDimension).forEach((d) => d.options.forEach((o) => !sizes.includes(o) && sizes.push(o)))
  );
  const unit = sizing.suggestedMeasurements.find((m) => m.unit)?.unit ?? 'CM';
  return { productTypeCode: type.code, productTypeLabel: type.label, sized: true, sizeChartRequired: sizing.sizeChartRequired, sizeDimension: sizing.sizeDimension, sizes, sizesAllowCustomValues: true, unitSystem: unit, measurements: sizing.suggestedMeasurements };
}

/* ── Size chart upload (catalog ChartImportValidator) ── */

const FIXED = ['size chart name', 'chart name', 'chart_name', 'unit', 'size', 'size fit', 'fit_note', 'how to measure', 'how_to_measure'];

export function sizeChartTemplateCsv(type: ProductTypeSchema | undefined): string {
  const measurements = type?.traits.sizing?.suggestedMeasurements ?? [
    { key: 'chest', label: 'Chest' },
    { key: 'waist', label: 'Waist' }
  ];
  const sizes = type ? suggestionsFor(type).sizes.slice(0, 3) : [];
  const header = ['Size chart name *', 'Unit', 'Size *', ...measurements.map((m) => `${m.label} *`), 'Size fit', 'How to measure'];
  const rows = (sizes.length ? sizes : ['S', 'M', 'L']).map((s, i) => [
    `${type?.label ?? 'My'} chart`, i === 0 ? 'cm' : '', s, ...measurements.map((_m, j) => `${80 + i * 4 + j * 4}-${84 + i * 4 + j * 4}`), i === 0 ? 'True to size' : '', ''
  ]);
  const notes = ['# One row per size. Rows with the same Size chart name make one chart (repeat the name on every row).', '# Any column after Size is a measurement: keep, rename or add your own.'];
  return `\uFEFF${notes.join('\r\n')}\r\n${toCsv([header, ...rows])}`;
}

const parseUnit = (raw: string): SizeChartUnit | null => {
  const s = raw.trim().toLowerCase();
  if (['cm', 'centimetre', 'centimetres', 'centimeter', 'centimeters'].includes(s)) return 'CM';
  if (['in', 'inch', 'inches', '"'].includes(s)) return 'IN';
  return null;
};
const parseFit = (raw: string): FitNote | null => {
  const s = raw.trim().toLowerCase().replace(/[ -]/g, '_');
  return (['runs_small', 'true_to_size', 'runs_large'] as const).includes(s as 'runs_small') ? (s.toUpperCase() as FitNote) : null;
};

export interface SizeChartUploadResult {
  report: SizeChartImportReport;
  /** Charts to save (create or update) when not a dry run. */
  save: Array<{ name: string; input: SizeChartInput; existing: MockSizeChart | undefined; action: 'CREATE' | 'UPDATE' | 'UNCHANGED' }>;
}

/** Reads a CSV size chart file like catalog does: row-level messages, a chart saved whole or not at all, matched by name. */
export function readSizeChartCsv(text: string, fileName: string, charts: readonly MockSizeChart[], productType: string | undefined, dryRun: boolean): SizeChartUploadResult {
  const sheet = parseCsv(text);
  const header = sheet[0] ?? [];
  const norm = (h: string) => h.replace(/\s*\*+$/, '').trim().toLowerCase();
  const col = (names: string[]) => header.findIndex((h) => names.includes(norm(h)));
  const nameCol = col(['size chart name', 'chart name', 'chart_name']);
  const sizeCol = col(['size']);
  const unitCol = col(['unit']);
  const fitCol = col(['size fit', 'fit_note']);
  const howCol = col(['how to measure', 'how_to_measure']);
  if (nameCol < 0 || sizeCol < 0) throw new Error('Missing required column: Size chart name *, Size *. Download the size chart template and keep its header row.');
  const measurementCols = header.map((h, i) => ({ h, i })).filter(({ h, i }) => h.trim() !== '' && !FIXED.includes(norm(h)) && i !== nameCol && i !== sizeCol);
  if (measurementCols.length === 0) throw new Error('Add at least one measurement column (for example Bust or Foot length) after the Size column');
  const rows: SizeChartImportRow[] = [];
  const groups = new Map<string, SizeChartImportRow[]>();
  sheet.slice(1).forEach((cells, k) => {
    const chartName = cleanValue(cells[nameCol] ?? '');
    const row: SizeChartImportRow = { rowNumber: k + 2, chartName, size: cleanValue(cells[sizeCol] ?? ''), status: 'VALID', messages: [], cells };
    const err = (column: string | null, columnIndex: number, message: string) => row.messages.push({ column, columnIndex, severity: 'ERROR', message });
    if (chartName === '') err('chart_name', nameCol, 'Required: the name of the size chart');
    const problem = customValueProblem('Size', row.size);
    if (problem) err('size', sizeCol, row.size === '' ? 'Required: the size label, for example M or UK 12' : problem);
    measurementCols.forEach(({ h, i }) => {
      const raw = (cells[i] ?? '').trim();
      const label = h.replace(/\s*\*+$/, '').replace(/\s*\([^)]*\)\s*$/, '').trim();
      const column = `m:${slugKey(label)}`;
      if (raw === '') err(column, i, `Enter the ${label.toLowerCase()}${row.size ? ` of size ${row.size}` : ''}`);
      else {
        const v = parseMeasurement(raw);
        if (!v) err(column, i, `"${raw.slice(0, 40)}" is not a number or a range like 86-90`);
        else if (v.min > v.max) err(column, i, `The smallest value (${v.min}) is above the largest (${v.max})`);
        else if (v.min <= 0) err(column, i, 'A measurement above zero');
      }
    });
    if (unitCol >= 0 && (cells[unitCol] ?? '').trim() !== '' && !parseUnit(cells[unitCol] ?? '')) err('unit', unitCol, 'Write cm or in');
    if (fitCol >= 0 && (cells[fitCol] ?? '').trim() !== '' && !parseFit(cells[fitCol] ?? '')) err('fit_note', fitCol, 'Choose one of Runs small, True to size, Runs large');
    rows.push(row);
    if (chartName !== '') groups.set(valueKey(chartName), [...(groups.get(valueKey(chartName)) ?? []), row]);
  });
  const outcomes: SizeChartImportChart[] = [];
  const save: SizeChartUploadResult['save'] = [];
  groups.forEach((group) => {
    const name = group[0]?.chartName ?? '';
    const seen = new Map<string, number>();
    group.forEach((r) => {
      const key = valueKey(r.size);
      const first = seen.get(key);
      if (first !== undefined && key !== '') r.messages.push({ column: 'size', columnIndex: sizeCol, severity: 'ERROR', message: `Duplicate size ${r.size}: also on row ${first} (sizes are compared ignoring case)` });
      else seen.set(key, r.rowNumber);
    });
    const bad = group.find((r) => r.messages.length > 0);
    if (bad) {
      group.forEach((r) => {
        if (r.messages.length === 0) r.messages.push({ column: 'chart_name', columnIndex: nameCol, severity: 'ERROR', message: `Another row of this chart has errors (row ${bad.rowNumber}); a chart is saved whole` });
      });
      outcomes.push({ name, action: 'FAILED', chartId: null, rows: group.map((r) => r.rowNumber), message: 'Fix the rows with errors and upload the file again; the chart is saved whole' });
      return;
    }
    const first = sheet[(group[0]?.rowNumber ?? 2) - 1] ?? [];
    const unit = (unitCol >= 0 && parseUnit(group.map((r) => r.cells[unitCol] ?? '').find((c) => c.trim() !== '') ?? '')) || 'CM';
    const input: SizeChartInput = {
      name, unitSystem: unit, productTypeCode: productType ?? null,
      columns: measurementCols.map(({ h }) => ({ label: h.replace(/\s*\*+$/, '').replace(/\s*\([^)]*\)\s*$/, '').trim() })),
      rows: group.map((r) => ({
        size: r.size,
        values: Object.fromEntries(measurementCols.map(({ h, i }) => {
          const v = parseMeasurement(r.cells[i] ?? '') as { min: number; max: number };
          return [slugKey(h.replace(/\s*\*+$/, '').replace(/\s*\([^)]*\)\s*$/, '').trim()), { min: v.min, max: v.max }];
        }))
      })),
      fitNote: parseFit(group.map((r) => r.cells[fitCol] ?? '').find((c) => c.trim() !== '') ?? ''),
      howToMeasure: (group.map((r) => r.cells[howCol] ?? '').find((c) => c.trim() !== '') ?? first[howCol] ?? '').trim() || null
    };
    const existing = charts.find((c) => !c.retired && valueKey(c.name) === valueKey(name));
    const { errors, built } = chartProblems(input, charts, existing?.id);
    if (Object.keys(errors).length > 0) {
      outcomes.push({ name, action: 'FAILED', chartId: null, rows: group.map((r) => r.rowNumber), message: Object.values(errors)[0] ?? 'The chart cannot be saved' });
      return;
    }
    const same = existing && existing.unitSystem === unit && JSON.stringify(existing.rows.map((r) => [r.size, r.values])) === JSON.stringify(built.rows.map((r) => [r.size, r.values]));
    const action = !existing ? 'CREATE' : same ? 'UNCHANGED' : 'UPDATE';
    save.push({ name, input, existing, action });
    outcomes.push({ name, action, chartId: dryRun ? (existing?.id ?? null) : null, rows: group.map((r) => r.rowNumber), message: null });
  });
  rows.forEach((r) => {
    r.status = r.messages.some((m) => m.severity === 'ERROR') ? 'ERROR' : r.messages.length ? 'WARNING' : 'VALID';
  });
  const count = (a: SizeChartImportChart['action']) => outcomes.filter((o) => o.action === a).length;
  return {
    save,
    report: {
      applied: !dryRun, fileName, fileWarnings: [], chartCount: groups.size, created: count('CREATE'), updated: count('UPDATE'), unchanged: count('UNCHANGED'),
      failed: count('FAILED'), errorRows: rows.filter((r) => r.status === 'ERROR').length, warningRows: rows.filter((r) => r.status === 'WARNING').length, charts: outcomes, rows
    }
  };
}
