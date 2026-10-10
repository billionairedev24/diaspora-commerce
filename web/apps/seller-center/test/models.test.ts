import { type ChartSpec, type ProductTypeSchema } from '@afrotransact/api-client';
import { strFromU8,unzipSync } from 'fflate';
import { describe, expect, it } from 'vitest';

import { emptyBuilder, gtinOk, validateStep } from '@/components/listings/builder-model';
import { validateCustom } from '@/components/provider/PolicyEditor';
import { estimateTakeHome } from '@/components/provider/ServicesView';
import { toCsv } from '@/lib/export/csv';
import { tabular } from '@/lib/export/table';
import { toXlsx } from '@/lib/export/xlsx';

const dress: ProductTypeSchema = {
  code: 'DRESS', label: 'Dresses', identifierRule: 'gtin',
  attributes: [{ key: 'brand', label: 'Brand', options: [], required: true, multiline: false }],
  themes: [{ name: 'Size', dimensions: [{ name: 'Size', options: ['S', 'M'] }] }]
};

describe('product builder steps', () => {
  it('blocks each step until it is complete', () => {
    const s = emptyBuilder();
    expect(validateStep('category', s, undefined)).toHaveProperty('category');
    expect(validateStep('category', { ...s, request: { id: 'cr1', path: 'Fashion › Bridal', reference: 'CR-1' } }, undefined)).toEqual({});
    expect(validateStep('images', s, dress)).toHaveProperty('images');
    expect(validateStep('details', { ...s, title: 'Short' }, dress)).toMatchObject({ title: expect.any(String), brand: 'Required' });
    expect(validateStep('details', { ...s, title: 'Ankara wrap dress', attrs: { brand: 'Lagos' } }, dress)).toEqual({});
    expect(validateStep('variations', s, dress)).toHaveProperty('theme');
  });

  it('checks GTIN check digits', () => {
    expect(gtinOk('4006381333931')).toBe(true);
    expect(gtinOk('4006381333932')).toBe(false);
  });
});

describe('provider rules', () => {
  it('rejects a partial-refund window that starts outside the free window', () => {
    const base = { freeHours: 24, partialRefund: true, partialFromHours: 48, partialPercent: 50, noShow: 'DEPOSIT' as const, rescheduleHours: 24, maxReschedules: 2 };
    expect(validateCustom(base)).toHaveProperty('partialFromHours');
    expect(validateCustom({ ...base, partialFromHours: 12 })).toEqual({});
  });

  it('estimates take-home after 10% commission and Stripe fees', () => {
    expect(estimateTakeHome(10000, 10)).toEqual({ commissionMinor: 1000, cardFeesMinor: 320, takeHomeMinor: 8680 });
  });
});

describe('dashboard exports', () => {
  const spec: ChartSpec = {
    id: 'top-products', title: 'Top products', kind: 'hbar', categoryLabel: 'Product', valueFormat: 'money', available: true,
    series: [{ key: 'sales', label: 'Sales', tone: 'ink' }, { key: 'units', label: 'Units', tone: 'gold', format: 'count' }],
    rows: [{ category: 'Ankara dress', values: { sales: 120.5, units: 4 } }, { category: 'Shea butter', values: { sales: 80, units: 3 } }]
  };

  it('writes CSV with a BOM and neutralised formulas', () => {
    const csv = toCsv({ title: 't', headers: ['a', 'b'], rows: [['=SUM(A1)', 'x,y']] });
    expect(csv.startsWith('﻿a,b\r\n')).toBe(true);
    expect(csv).toContain(`"'=SUM(A1)","x,y"`);
    expect(toCsv(tabular(spec))).toContain('Ankara dress,120.5,4');
  });

  it('writes an .xlsx workbook with a native chart', () => {
    const files = unzipSync(toXlsx(spec));
    expect(Object.keys(files)).toEqual(expect.arrayContaining(['[Content_Types].xml', 'xl/workbook.xml', 'xl/worksheets/sheet1.xml', 'xl/charts/chart1.xml']));
    const chart = strFromU8(files['xl/charts/chart1.xml'] ?? new Uint8Array());
    expect(chart).toContain('<c:barChart>');
  });
});
