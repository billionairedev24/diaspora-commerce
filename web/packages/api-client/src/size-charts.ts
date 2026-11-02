/* Pure helpers for size charts and seller-written sizes, shared by Seller Center (chart editor, product form), the
   storefront size guide and the mocks. They mirror catalog's rules so the UI can answer instantly; catalog still
   decides on save. Nothing here knows a category: the product type's sizing trait and the variation dimensions
   (`allowCustomValues`) decide. */
import { type SizeChart, type SizeChartRow, type SizeChartUnit, type SizeChartValue, type SizingTrait } from './schemas/seller';

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
export function formatMeasurement(v: SizeChartValue): string {
  const n = (x: number) => String(Math.round(x * 10) / 10);
  return v.min === v.max ? n(v.min) : `${n(v.min)}–${n(v.max)}`;
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
