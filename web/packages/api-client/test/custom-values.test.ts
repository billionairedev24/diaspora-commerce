import { describe, expect, it } from 'vitest';

import { addValues, measurementKey, ownValues, sizeTable, splitValues, valueRejection } from '../src';

const SIZES = ['XS', 'S', 'M', 'L', 'XL', '2XL'];

describe('seller-written values (sizes and colours)', () => {
  it('splits a pasted list on commas, semicolons, tabs and line breaks and tidies spaces', () => {
    expect(splitValues(' UK 12 ,  UK   14;\tUK 16\nFree size,, ')).toEqual(['UK 12', 'UK 14', 'UK 16', 'Free size']);
  });

  it('rejects empty, over-long and odd values with a reason', () => {
    expect(valueRejection('  ')).toBe('empty');
    expect(valueRejection('x'.repeat(31))).toBe('tooLong');
    expect(valueRejection('x'.repeat(30))).toBeNull();
    expect(valueRejection('<b>big</b>')).toBe('characters');
    expect(valueRejection('-12')).toBe('characters');
    expect(valueRejection('UK 12')).toBeNull();
    expect(valueRejection("Women's 8")).toBeNull();
    expect(valueRejection('Indigo adire (hand-dyed)')).toBeNull();
  });

  it('adds a value, keeping the category spelling when one matches ignoring case', () => {
    const r = addValues(['M'], SIZES, 'xl');
    expect(r.selected).toEqual(['M', 'XL']);
    expect(r.added).toEqual(['XL']);
    expect(r.rejected).toEqual([]);
  });

  it('detects duplicates ignoring case and spacing, against selected values and within one paste', () => {
    const r = addValues(['UK 12'], SIZES, 'uk  12, UK 14, uk 14, m, M');
    expect(r.selected).toEqual(['UK 12', 'UK 14', 'M']);
    expect(r.rejected).toEqual([
      { value: 'uk 12', reason: 'duplicate' },
      { value: 'uk 14', reason: 'duplicate' },
      { value: 'M', reason: 'duplicate' }
    ]);
  });

  it('adds the good values of a pasted list and reports the bad ones', () => {
    const r = addValues([], SIZES, 'UK 8, <script>, UK 10, ' + 'y'.repeat(40));
    expect(r.added).toEqual(['UK 8', 'UK 10']);
    expect(r.rejected.map((x) => x.reason)).toEqual(['characters', 'tooLong']);
  });

  it('allows at most 40 different own values per dimension but never limits suggestions', () => {
    const own = Array.from({ length: 40 }, (_, i) => `Size ${i + 1}`);
    const full = addValues(own, SIZES, 'Size 41');
    expect(full.added).toEqual([]);
    expect(full.rejected).toEqual([{ value: 'Size 41', reason: 'limit' }]);
    expect(addValues(own, SIZES, 'XL').added).toEqual(['XL']);
  });

  it('tells the seller\'s own values from the category\'s', () => {
    expect(ownValues(['S', 'xl', 'UK 12', 'Free size'], SIZES)).toEqual(['UK 12', 'Free size']);
  });
});

describe('size table and measurement keys', () => {
  const chart = {
    unitSystem: 'CM' as const,
    columns: [{ key: 'bust', label: 'Bust' }, { key: 'waist', label: 'Waist' }],
    rows: [
      { size: 'S', values: { bust: { min: 84, max: 87 }, waist: { min: 66, max: 66 } } },
      { size: 'M', values: { bust: { min: 88, max: 92 } } }
    ]
  };

  it('writes ranges and single values, and a dash for a missing one', () => {
    const t = sizeTable(chart);
    expect(t.rows).toEqual([{ size: 'S', cells: ['84–87', '66'] }, { size: 'M', cells: ['88–92', '–'] }]);
  });

  it('converts to inches with one decimal when the chart has no alternate values, and prefers stored alternates', () => {
    expect(sizeTable(chart, 'IN').rows[0]?.cells).toEqual(['33.1–34.3', '26']);
    const withAlt = { ...chart, rows: [{ size: 'S', values: { bust: { min: 84, max: 87 } }, alternateValues: { bust: { min: 33, max: 34 } } }] };
    expect(sizeTable(withAlt, 'IN').rows[0]?.cells[0]).toBe('33–34');
  });

  it('derives column keys as the catalog does', () => {
    expect(measurementKey('Foot length')).toBe('foot_length');
    expect(measurementKey('  Sleeve  length (cm) ')).toBe('sleeve_length_cm');
    expect(measurementKey('3/4 sleeve')).toBe('m_3_4_sleeve');
    expect(measurementKey('Épaule')).toBe('epaule');
    expect(measurementKey('!!!')).toBe('');
  });
});
