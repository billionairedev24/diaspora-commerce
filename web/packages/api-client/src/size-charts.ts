/* Pure helpers for size charts and seller-written sizes, shared by Seller Center (chart editor, product form), the
   storefront size guide and the mocks. They mirror catalog's rules so the UI can answer instantly; catalog still
   decides on save. Nothing here knows a category: the product type's sizing trait and the variation dimensions
   (`allowCustomValues`) decide. */
import { type SizeChart, type SizeChartRow, type SizeChartUnit, type SizingTrait } from './schemas/seller';

/** Longest size or colour value a seller may write, and the most different own values of a dimension on one product. */
export const MAX_CUSTOM_VALUE_LENGTH = 30;
export const MAX_CUSTOM_VALUES_PER_PRODUCT = 40;

const ALLOWED_VALUE = /^[\p{L}\p{M}\p{N}][\p{L}\p{M}\p{N} .,/+()'’&#×:_–—-]*$/u;

/** Spaces tidied: "  free   size " becomes "free size". */
export function cleanValue(raw: string): string {
  return raw.trim().replace(/\s+/g, ' ');
}

/** What two spellings of a value share: "XL", "xl" and " XL " are one size. */
export function valueKey(raw: string): string {
  return cleanValue(raw).toLowerCase();
}

/** Why a size or colour value cannot be used, or null. `dimension` names the field in the message ("Size"). */
export function customValueProblem(dimension: string, raw: string): string | null {
  const v = cleanValue(raw);
  if (v === '') return `Choose or type a ${dimension.toLowerCase()}`;
  if ([...v].length > MAX_CUSTOM_VALUE_LENGTH) return `At most ${MAX_CUSTOM_VALUE_LENGTH} characters`;
  if (!ALLOWED_VALUE.test(v)) return "Use letters, digits, spaces and . , / + ( ) ' & # - only, starting with a letter or digit";
  return null;
}

/** The category's own spelling of a value ("xl" becomes "XL"), else the tidied value. */
export function canonicalValue(raw: string, suggestions: readonly string[]): string {
  const cleaned = cleanValue(raw);
  return suggestions.find((s) => valueKey(s) === valueKey(cleaned)) ?? cleaned;
}

/** The size chart row of a size (ignoring case and spacing), if the chart has one. */
export function chartRowFor(chart: Pick<SizeChart, 'rows'>, size: string): SizeChartRow | undefined {
  const key = valueKey(size);
  return chart.rows.find((r) => valueKey(r.size) === key);
}

export interface SizeCoverageProblem {
  /** Index of the first variant that sells the size. */
  variantIndex: number;
  size: string;
  message: string;
}

/**
 * Sizes the variants sell that the chart has no row for (the product form shows them under the Size field before
 * saving; catalog reports the same as `variants[i].options.Size`). One entry per missing size.
 */
export function sizesMissingFromChart(
  chart: Pick<SizeChart, 'rows' | 'name'>,
  sizing: Pick<SizingTrait, 'sizeDimension'>,
  variants: ReadonlyArray<{ options: Record<string, string> }>
): SizeCoverageProblem[] {
  const seen = new Set<string>();
  const out: SizeCoverageProblem[] = [];
  variants.forEach((v, variantIndex) => {
    const size = v.options[sizing.sizeDimension];
    if (!size || size.trim() === '' || seen.has(valueKey(size))) return;
    seen.add(valueKey(size));
    if (!chartRowFor(chart, size)) {
      out.push({
        variantIndex,
        size: cleanValue(size),
        message: `${sizing.sizeDimension} "${cleanValue(size)}" has no row in the size chart "${chart.name}". Add it to the chart or choose another chart.`
      });
    }
  });
  return out;
}

/** "86-90" for a range, "88" for a single value; at most one decimal, no trailing zero. */
export function formatMeasurement(v: MeasurementRange): string {
  const n = (x: number) => String(Math.round(x * 10) / 10);
  return v.max === undefined || v.min === v.max ? n(v.min) : `${n(v.min)}–${n(v.max)}`;
}

/** A measurement as the API sends it: a single value (no `max`, or `max` equal to `min`) or a range. */
export interface MeasurementRange {
  min: number;
  max?: number;
}

/** The text a seller types for a value ("88", "86-90", "86 to 90", "87,5", "88 cm"), or null when it is none of those. */
export function parseMeasurement(text: string): { min: number; max: number } | null {
  const s = text.trim().replace(/\s*(cm|in|")$/i, '').trim();
  const num = '(\\d{1,4}(?:[.,]\\d{1,2})?)';
  const single = new RegExp(`^${num}$`).exec(s);
  const range = new RegExp(`^${num}\\s*(?:-|–|—|to)\\s*${num}$`).exec(s);
  const toNumber = (x: string | undefined) => Number((x ?? '').replace(',', '.'));
  if (single) return { min: toNumber(single[1]), max: toNumber(single[1]) };
  if (range) return { min: toNumber(range[1]), max: toNumber(range[2]) };
  return null;
}

const CM_PER_INCH = 2.54;

/** A value converted between units, one decimal (the server converts the same way for `alternateValues`). */
export function convertMeasurement(value: number, from: SizeChartUnit, to: SizeChartUnit): number {
  if (from === to) return value;
  const converted = from === 'CM' ? value / CM_PER_INCH : value * CM_PER_INCH;
  return Math.round(converted * 10) / 10;
}

/** The measurement column keys of a chart in display order: what the table renders and a size guide lists. */
export function chartColumnKeys(chart: Pick<SizeChart, 'columns'>): string[] {
  return chart.columns.map((c) => c.key);
}

/* ── Shopper size guide (the storefront and the customer app show the same table) ── */

export interface GuideRow {
  size: string;
  values: Readonly<Record<string, MeasurementRange>>;
  alternateValues: Readonly<Record<string, MeasurementRange>>;
}

/** The chart as the product page receives it, reduced to what the size guide needs. */
export interface GuideChart {
  unitSystem: SizeChartUnit;
  alternateUnit: SizeChartUnit;
  columns: ReadonlyArray<{ key: string }>;
  rows: ReadonlyArray<GuideRow>;
}

/** The variation dimension that carries the size (`options.Size`, see docs/engineering/size-charts.md). */
export const SIZE_DIMENSION = 'Size';

/** A range converted between units, one decimal at each end (a single value stays single). */
export function convertRange(v: MeasurementRange, from: SizeChartUnit, to: SizeChartUnit): MeasurementRange {
  const min = convertMeasurement(v.min, from, to);
  if (v.max === undefined || v.max === v.min) return { min };
  return { min, max: convertMeasurement(v.max, from, to) };
}

/** The units a shopper can switch between: the chart's own first, then the other one. */
export function guideUnits(chart: Pick<GuideChart, 'unitSystem' | 'alternateUnit'>): SizeChartUnit[] {
  return chart.alternateUnit === chart.unitSystem ? [chart.unitSystem] : [chart.unitSystem, chart.alternateUnit];
}

/** The unit to show: the shopper's earlier choice if the chart offers it, otherwise the chart's own unit. */
export function initialGuideUnit(chart: Pick<GuideChart, 'unitSystem' | 'alternateUnit'>, remembered: string | null | undefined): SizeChartUnit {
  return guideUnits(chart).find((u) => u === remembered) ?? chart.unitSystem;
}

/** One cell in `unit`: the chart's own value, the server's `alternateValues`, or converted when those are missing. */
export function guideValue(chart: GuideChart, row: GuideRow, key: string, unit: SizeChartUnit): MeasurementRange | undefined {
  const own = row.values[key];
  if (unit === chart.unitSystem) return own;
  return row.alternateValues[key] ?? (own ? convertRange(own, chart.unitSystem, unit) : undefined);
}

/** The text of one cell ("86–90", "88"), or "–" where the chart has no value. */
export function guideCell(chart: GuideChart, row: GuideRow, key: string, unit: SizeChartUnit): string {
  const v = guideValue(chart, row, key, unit);
  return v ? formatMeasurement(v) : '–';
}

/** Index of the row of the shopper's size (ignoring case and spacing), or -1: no size selected or no such row in the chart. */
export function selectedRowIndex(chart: Pick<GuideChart, 'rows'>, size: string | null | undefined): number {
  if (!size || size.trim() === '') return -1;
  const key = valueKey(size);
  return chart.rows.findIndex((r) => valueKey(r.size) === key);
}

/** The size of the selected variant (its `Size` option), or null when the product has none. */
export function selectedSize(variants: ReadonlyArray<{ selected: boolean; options: Readonly<Record<string, string>> }>): string | null {
  return variants.find((v) => v.selected)?.options[SIZE_DIMENSION] ?? null;
}

/* ── Variant picker model: Size, Color ... chips from the variants' options ── */

export interface PickerVariant {
  atin: string;
  selected: boolean;
  available: boolean;
  options: Readonly<Record<string, string>>;
}

export type ChipState = 'selected' | 'available' | 'elsewhere' | 'unavailable';

export interface PickerChip {
  value: string;
  state: ChipState;
  /** The variant a click goes to (undefined when the chip is unavailable or selected). */
  atin?: string;
}

export interface PickerDimension {
  name: string;
  chips: PickerChip[];
}

const sameOptions = (a: Readonly<Record<string, string>>, b: Readonly<Record<string, string>>): boolean =>
  Object.keys(a).length === Object.keys(b).length && Object.entries(a).every(([k, v]) => b[k] !== undefined && valueKey(b[k]) === valueKey(v));

/**
 * The option chips of a product: one dimension per option name (in order of appearance), each value once. A chip is
 * `selected` (the current variant's value), `available` (switching only this value gives an in-stock variant),
 * `elsewhere` (that exact combination is out of stock or not made, but the value is in stock in another combination: the
 * click goes to the closest in-stock variant) or `unavailable` (nothing in stock has this value). Returns [] when the
 * variants carry no options (the page then lists the variants by label).
 */
export function pickerDimensions(variants: ReadonlyArray<PickerVariant>): PickerDimension[] {
  const current = variants.find((v) => v.selected);
  if (!current || variants.some((v) => Object.keys(v.options).length === 0)) return [];
  const names: string[] = [];
  for (const v of variants) for (const k of Object.keys(v.options)) if (!names.includes(k)) names.push(k);
  return names.map((name) => {
    const values: string[] = [];
    for (const v of variants) {
      const val = v.options[name];
      if (val !== undefined && !values.some((x) => valueKey(x) === valueKey(val))) values.push(val);
    }
    const chips = values.map((value): PickerChip => {
      const holds = (v: PickerVariant) => v.options[name] !== undefined && valueKey(v.options[name]) === valueKey(value);
      if (current.options[name] !== undefined && valueKey(current.options[name]) === valueKey(value)) return { value, state: 'selected' };
      const wanted = { ...current.options, [name]: value };
      const exact = variants.find((v) => sameOptions(v.options, wanted));
      if (exact?.available) return { value, state: 'available', atin: exact.atin };
      // Closest in-stock variant with this value: it keeps as many of the other current choices as possible.
      const score = (v: PickerVariant) => Object.entries(current.options).filter(([k, x]) => k !== name && v.options[k] !== undefined && valueKey(v.options[k]) === valueKey(x)).length;
      const near = variants.filter((v) => holds(v) && v.available).sort((a, b) => score(b) - score(a))[0];
      return near ? { value, state: 'elsewhere', atin: near.atin } : { value, state: 'unavailable' };
    });
    return { name, chips };
  });
}

/** The variant of a size in the product's own variants (used by "Select this size"): in stock, closest to the current colour. */
export function variantForSize(variants: ReadonlyArray<PickerVariant>, size: string): PickerChip | undefined {
  return pickerDimensions(variants).find((d) => d.name === SIZE_DIMENSION)?.chips.find((c) => valueKey(c.value) === valueKey(size));
}
