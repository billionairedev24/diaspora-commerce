import { describe, expect, it } from 'vitest';

import {
  convertRange,
  formatMeasurement,
  guideCell,
  type GuideChart,
  guideUnits,
  initialGuideUnit,
  pickerDimensions,
  type PickerVariant,
  selectedRowIndex,
  selectedSize,
  variantForSize,
} from '../src';

/* The shopper size guide helpers: unit switching, cell text, the selected row and the Size / Color chips. */

const chart: GuideChart = {
  unitSystem: 'CM',
  alternateUnit: 'IN',
  columns: [{ key: 'bust' }, { key: 'waist' }],
  rows: [
    { size: 'S', values: { bust: { min: 84, max: 88 }, waist: { min: 66 } }, alternateValues: { bust: { min: 33.1, max: 34.6 } } },
    { size: 'UK 12', values: { bust: { min: 92 } }, alternateValues: {} },
  ],
};

const v = (atin: string, size: string, color: string, available: boolean, selected = false): PickerVariant => ({
  atin,
  selected,
  available,
  options: { Size: size, Color: color },
});

describe('size guide units and cells', () => {
  it('offers the chart unit first, then the other', () => {
    expect(guideUnits(chart)).toEqual(['CM', 'IN']);
    expect(guideUnits({ unitSystem: 'CM', alternateUnit: 'CM' })).toEqual(['CM']);
  });

  it('remembers a unit only when the chart offers it', () => {
    expect(initialGuideUnit(chart, 'IN')).toBe('IN');
    expect(initialGuideUnit(chart, 'FT')).toBe('CM');
    expect(initialGuideUnit(chart, null)).toBe('CM');
  });

  it('formats the chart value, the server alternate value, a converted value and a gap', () => {
    expect(guideCell(chart, chart.rows[0]!, 'bust', 'CM')).toBe(formatMeasurement({ min: 84, max: 88 }));
    expect(guideCell(chart, chart.rows[0]!, 'bust', 'IN')).toBe(formatMeasurement({ min: 33.1, max: 34.6 }));
    expect(guideCell(chart, chart.rows[0]!, 'waist', 'IN')).toBe(formatMeasurement(convertRange({ min: 66 }, 'CM', 'IN')));
    expect(guideCell(chart, chart.rows[1]!, 'waist', 'CM')).toBe('–');
  });

  it('keeps a single value single when converting', () => {
    expect(convertRange({ min: 66 }, 'CM', 'IN').max).toBeUndefined();
    expect(convertRange({ min: 66, max: 66 }, 'CM', 'IN').max).toBeUndefined();
  });
});

describe('selected size', () => {
  it('finds the row ignoring case and spacing', () => {
    expect(selectedRowIndex(chart, 's')).toBe(0);
    expect(selectedRowIndex(chart, ' uk 12 ')).toBe(1);
    expect(selectedRowIndex(chart, 'XL')).toBe(-1);
    expect(selectedRowIndex(chart, null)).toBe(-1);
    expect(selectedRowIndex(chart, '  ')).toBe(-1);
  });

  it('reads the size of the selected variant', () => {
    expect(selectedSize([v('a', 'S', 'Blue', true), v('b', 'M', 'Blue', true, true)])).toBe('M');
    expect(selectedSize([{ selected: true, options: {} }])).toBeNull();
    expect(selectedSize([])).toBeNull();
  });
});

describe('variant picker model', () => {
  const variants = [
    v('s-blue', 'S', 'Blue', true, true),
    v('m-blue', 'M', 'Blue', true),
    v('f-blue', 'Free size', 'Blue', false),
    v('s-ind', 'S', 'Indigo', true),
    v('f-ind', 'Free size', 'Indigo', true),
  ];

  it('builds one dimension per option, values once, in order of appearance', () => {
    const dims = pickerDimensions(variants);
    expect(dims.map((d) => d.name)).toEqual(['Size', 'Color']);
    expect(dims[0]!.chips.map((c) => c.value)).toEqual(['S', 'M', 'Free size']);
    expect(dims[1]!.chips.map((c) => c.value)).toEqual(['Blue', 'Indigo']);
  });

  it('marks selected, available and elsewhere chips with the variant a click goes to', () => {
    const [size, color] = pickerDimensions(variants);
    expect(size!.chips[0]).toEqual({ value: 'S', state: 'selected' });
    expect(size!.chips[1]).toEqual({ value: 'M', state: 'available', atin: 'm-blue' });
    expect(size!.chips[2]).toEqual({ value: 'Free size', state: 'elsewhere', atin: 'f-ind' });
    expect(color!.chips[1]).toEqual({ value: 'Indigo', state: 'available', atin: 's-ind' });
  });

  it('marks a value unavailable when nothing in stock has it', () => {
    const dims = pickerDimensions([...variants.slice(0, 3), v('l-blue', 'L', 'Blue', false)]);
    expect(dims[0]!.chips.find((c) => c.value === 'L')).toEqual({ value: 'L', state: 'unavailable' });
  });

  it('returns no chips without a selected variant or without options', () => {
    expect(pickerDimensions(variants.map((x) => ({ ...x, selected: false })))).toEqual([]);
    expect(pickerDimensions([{ atin: 'x', selected: true, available: true, options: {} }])).toEqual([]);
  });

  it('finds the variant of a size, closest to the current colour', () => {
    expect(variantForSize(variants, 'm')).toEqual({ value: 'M', state: 'available', atin: 'm-blue' });
    expect(variantForSize(variants, 'XL')).toBeUndefined();
  });
});
