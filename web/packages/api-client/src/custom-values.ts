/* Seller-written variation values (sizes and colours the category does not list), as one pure helper shared by the
   product form and the tests. The limits mirror catalog's `CustomValues` (docs/engineering/size-charts.md): at most 30
   characters, letters / digits / spaces and a few marks, starting with a letter or digit, at most 40 different own
   values per dimension on one product, and no case-insensitive duplicates. */
import { canonicalValue, cleanValue, MAX_CUSTOM_VALUE_LENGTH, MAX_CUSTOM_VALUES_PER_PRODUCT, valueKey } from './size-charts';

export type CustomValueRejection = 'empty' | 'tooLong' | 'characters' | 'duplicate' | 'limit';

const ALLOWED = /^[\p{L}\p{M}\p{N}][\p{L}\p{M}\p{N} .,/+()'’&#×:_–—-]*$/u;

/** Why one value cannot be used as it is (not counting duplicates and the count limit), or null. */
export function valueRejection(raw: string): Extract<CustomValueRejection, 'empty' | 'tooLong' | 'characters'> | null {
  const v = cleanValue(raw);
  if (v === '') return 'empty';
  if ([...v].length > MAX_CUSTOM_VALUE_LENGTH) return 'tooLong';
  if (!ALLOWED.test(v)) return 'characters';
  return null;
}

/** Splits what a seller typed or pasted into separate values: commas, semicolons, tabs and line breaks separate them. */
export function splitValues(text: string): string[] {
  return text.split(/[,;\t\r\n]+/).map(cleanValue).filter((v) => v !== '');
}

export interface AddValuesResult {
  /** The selected values after adding (the suggestion's spelling where one matches). */
  selected: string[];
  /** Values that were added (own ones and suggestions that were switched on), in the spelling stored. */
  added: string[];
  rejected: Array<{ value: string; reason: CustomValueRejection }>;
}

/** The seller's own values among `values`: the ones no suggestion spells the same (ignoring case and spacing). */
export function ownValues(values: readonly string[], suggestions: readonly string[]): string[] {
  return values.filter((v) => !suggestions.some((s) => valueKey(s) === valueKey(v)));
}

/**
 * Adds typed or pasted values to the selected values of a dimension. A value equal (ignoring case and spacing) to a
 * suggestion switches that suggestion on; one already selected is a duplicate; an own value over the limits is rejected
 * with a reason. One bad value never blocks the others of a pasted list.
 */
export function addValues(selected: readonly string[], suggestions: readonly string[], text: string): AddValuesResult {
  const next = [...selected];
  const added: string[] = [];
  const rejected: AddValuesResult['rejected'] = [];
  for (const raw of splitValues(text)) {
    const bad = valueRejection(raw);
    if (bad) {
      rejected.push({ value: raw, reason: bad });
      continue;
    }
    const value = canonicalValue(raw, suggestions);
    const key = valueKey(value);
    if (next.some((v) => valueKey(v) === key)) {
      rejected.push({ value, reason: 'duplicate' });
      continue;
    }
    const isSuggestion = suggestions.some((s) => valueKey(s) === key);
    if (!isSuggestion && ownValues(next, suggestions).length >= MAX_CUSTOM_VALUES_PER_PRODUCT) {
      rejected.push({ value, reason: 'limit' });
      continue;
    }
    next.push(value);
    added.push(value);
  }
  return { selected: next, added, rejected };
}
