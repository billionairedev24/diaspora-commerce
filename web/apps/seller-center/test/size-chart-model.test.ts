import { type SizeChart, type SizeChartSuggestions } from '@afrotransact/api-client';
import { getSellerDb, resetSellerDb, SIZE_CHART_IDS } from '@afrotransact/api-client/mocks/seller';
import { beforeEach, describe, expect, it } from 'vitest';

import {
  addColumn,
  addRow,
  addSizes,
  buildInput,
  columnKey,
  type EditorState,
  emptyEditor,
  MAX_ROWS,
  missingSizes,
  pasteRows,
  removeColumn,
  removeRow,
  sameState,
  startState,
  stateOf,
  withUnit
} from '@/components/size-charts/chart-model';
import { tEn } from '@/i18n/t';

const suggestions: SizeChartSuggestions = {
  productTypeCode: 'dress', productTypeLabel: 'women’s dresses', sized: true, sizeChartRequired: false, sizeDimension: 'Size', sizes: ['XS', 'S', 'M'], sizesAllowCustomValues: true,
  unitSystem: 'CM', measurements: [{ key: 'bust', label: 'Bust', unit: 'CM', help: null }, { key: 'waist', label: 'Waist', unit: 'CM', help: null }]
};

let mens: SizeChart;
beforeEach(() => {
  resetSellerDb(new Date('2026-10-10T15:00:00Z'));
  const found = getSellerDb().sizeCharts.find((c) => c.id === SIZE_CHART_IDS.mensTops);
  if (!found) throw new Error('seeded chart missing');
  mens = found;
});

const filled = (extra: Partial<EditorState> = {}): EditorState => ({
  name: 'Adire dresses', unit: 'CM', productTypeCode: 'dress', columns: [{ key: '', label: 'Bust' }, { key: '', label: 'Waist' }],
  rows: [{ size: 'S', cells: ['84-87', '66-69'] }, { size: 'M', cells: ['88-92', '70-74'] }], fitNote: '', howToMeasure: '', ...extra
});

describe('size chart editor: starting points', () => {
  it('starts a chart from what the category suggests, with the sizes the product sells first', () => {
    const s = startState(suggestions, 'dress', ['4XL', 'm']);
    expect(s).toMatchObject({ unit: 'CM', productTypeCode: 'dress' });
    expect(s.columns.map((c) => c.label)).toEqual(['Bust', 'Waist']);
    expect(s.rows.map((r) => r.size)).toEqual(['4XL', 'm', 'XS', 'S']);
    expect(s.rows.every((r) => r.cells.length === 2 && r.cells.every((c) => c === ''))).toBe(true);
    expect(startState({ ...suggestions, sized: false }, 'grocery').columns).toHaveLength(1);
    expect(startState(null, '').rows).toHaveLength(1);
  });

  it('shows a saved chart as the shopper reads it and gives it back unchanged', () => {
    const s = stateOf(mens);
    expect(s).toMatchObject({ name: 'Kaftans and shirts', unit: 'IN', fitNote: 'RUNS_LARGE' });
    expect(s.rows[0]).toEqual({ size: 'S', cells: ['35–37', '29–31', '24.5'] });
    const { input, errors } = buildInput(s, tEn, mens.version);
    expect(errors).toEqual({});
    expect(input).toMatchObject({ name: 'Kaftans and shirts', unitSystem: 'IN', version: mens.version, fitNote: 'RUNS_LARGE' });
    expect(input.columns.map((c) => c.key)).toEqual(['chest', 'waist', 'sleeve_length']);
    expect(input.rows[0]?.values.chest).toEqual({ min: 35, max: 37 });
    // A single value is sent without a maximum.
    expect(input.rows[0]?.values.sleeve_length).toEqual({ min: 24.5 });
    expect(sameState(s, stateOf(mens))).toBe(true);
  });
});

describe('size chart editor: checks mirror catalog', () => {
  it('accepts a complete chart and derives the key of a new measurement from its label', () => {
    const { input, errors } = buildInput(filled({ columns: [{ key: '', label: 'Foot length' }, { key: '', label: '3D width' }], rows: [{ size: 'EU 38', cells: ['24,5', '9'] }] }), tEn);
    expect(errors).toEqual({});
    expect(input.columns.map((c) => c.key)).toEqual(['foot_length', 'm_3d_width']);
    expect(input.rows[0]?.values.foot_length).toEqual({ min: 24.5 });
    expect(input).not.toHaveProperty('version');
    expect(columnKey({ key: '', label: 'Foot length' })).toBe('foot_length');
  });

  it('names each problem at the field catalog would, so server and editor errors land on the same cell', () => {
    const bad = filled({
      name: ' x ',
      columns: [{ key: '', label: 'Bust' }, { key: '', label: 'bust' }, { key: '', label: '' }],
      rows: [{ size: 'S', cells: ['84-87', '66', ''] }, { size: 's', cells: ['abc', '95-90', '1200'] }, { size: '', cells: ['', '', ''] }, { size: 'Bad$', cells: ['1', '2', '3'] }]
    });
    const { errors } = buildInput(bad, tEn);
    expect(errors.name).toBe('Name the chart (at least 2 characters).');
    expect(errors['columns[1].label']).toBe('Two measurements are called bust.');
    expect(errors['columns[2].label']).toBe('Name the measurement.');
    expect(errors['rows[1].size']).toBe('Size s appears twice (case is ignored).');
    expect(errors['rows[2].size']).toBe('Name the size.');
    expect(errors['rows[3].size']).toContain('Use letters, digits, spaces');
    expect(errors['rows[0].values.bust']).toBeUndefined();
    expect(errors['rows[2].values.bust']).toBe('Enter the bust of size 3.');
  });

  it('refuses a range written the wrong way round and values outside 0 to 1000', () => {
    const a = buildInput(filled({ rows: [{ size: 'S', cells: ['95-90', '1200'] }] }), tEn).errors;
    expect(a['rows[0].values.bust']).toBe('The smallest value (95) is above the largest (90).');
    expect(a['rows[0].values.waist']).toBe('Between 0 and 1000.');
    expect(buildInput(filled({ rows: [{ size: 'S', cells: ['84', '0'] }] }), tEn).errors['rows[0].values.waist']).toBe('Between 0 and 1000.');
    expect(buildInput(filled({ rows: [{ size: 'S', cells: ['abc', '66 to 69 cm'] }] }), tEn).errors).toEqual({ 'rows[0].values.bust': 'Use a number or a range such as 86-90.' });
  });

  it('limits the how-to-measure text', () => {
    expect(buildInput(filled({ howToMeasure: 'x'.repeat(1001) }), tEn).errors.howToMeasure).toBe('At most 1000 characters.');
  });
});

describe('size chart editor: changes', () => {
  it('converts what was typed when the unit changes, one decimal, and leaves the rest alone', () => {
    const inches = withUnit(filled({ rows: [{ size: 'S', cells: ['84-87', 'tbd'] }] }), 'IN');
    expect(inches.unit).toBe('IN');
    expect(inches.rows[0]?.cells).toEqual(['33.1–34.3', 'tbd']);
    expect(withUnit(inches, 'CM').rows[0]?.cells[0]).toBe('84.1–87.1');
    const same = filled();
    expect(withUnit(same, 'CM')).toBe(same);
  });

  it('adds and removes rows and measurements, keeping at least one of each', () => {
    let s = addColumn(filled(), 'Hip');
    expect(s.columns).toHaveLength(3);
    expect(s.rows.every((r) => r.cells.length === 3)).toBe(true);
    s = removeColumn(s, 0);
    expect(s.columns.map((c) => c.label)).toEqual(['Waist', 'Hip']);
    expect(s.rows[0]?.cells).toEqual(['66-69', '']);
    expect(removeColumn({ ...s, columns: [{ key: '', label: 'Only' }], rows: [{ size: 'S', cells: [''] }] }, 0).columns).toHaveLength(1);
    s = removeRow(addRow(s, 'L'), 0);
    expect(s.rows.map((r) => r.size)).toEqual(['M', 'L']);
    expect(removeRow({ ...s, rows: [{ size: 'M', cells: ['', ''] }] }, 0).rows).toHaveLength(1);
    const full = { ...s, rows: Array.from({ length: MAX_ROWS }, (_, i) => ({ size: `S${i}`, cells: ['', ''] })) };
    expect(addRow(full).rows).toHaveLength(MAX_ROWS);
  });

  it('adds sizes once (ignoring case), filling a still-blank first row first', () => {
    const blank = startState(null, '');
    expect(addSizes(blank, ['4XL']).rows.map((r) => r.size)).toEqual(['4XL']);
    expect(addSizes(filled(), ['s', 'XL', ' xl ']).rows.map((r) => r.size)).toEqual(['S', 'M', 'XL']);
    expect(missingSizes(filled(), ['M', 'm', '4XL', '', '5XL'])).toEqual(['4XL', '5XL']);
  });

  it('reads rows pasted from a spreadsheet: tabs, a header line, updates by size, and what it skipped', () => {
    const text = 'Size\tBust\tWaist\nXL\t99-105\t81-88\n\nM\t90\t72\n\t1\t2\n';
    const { state, read, skipped } = pasteRows(filled(), text);
    expect(read).toBe(2);
    expect(skipped).toBe(1);
    expect(state.rows.map((r) => r.size)).toEqual(['S', 'M', 'XL']);
    expect(state.rows[1]?.cells).toEqual(['90', '72']);
    expect(state.rows[2]?.cells).toEqual(['99-105', '81-88']);
    expect(pasteRows(emptyEditor(), 'S, 84-87').state.rows).toEqual([{ size: 'S', cells: ['84-87'] }]);
  });
});
