import { beforeEach, describe, expect, expectTypeOf, it } from 'vitest';

import {
  canonicalValue,
  chartRowFor,
  convertMeasurement,
  createSellerApiClient,
  customValueProblem,
  formatMeasurement,
  isApiError,
  parseMeasurement,
  type ProductDraft,
  type SizeChart,
  type SizeChartInput,
  sizesMissingFromChart,
  valueKey
} from '../src';
import type { components } from '../src/generated/gateway-bff-seller';
import { resetSellerDb } from '../src/mocks/seller';
import { SIZE_CHART_IDS } from '../src/mocks/seller/size-charts';
import { handleSellerMockRequest } from '../src/mocks/seller-runtime';

/* Seller-written sizes and size charts: the helpers the forms share, the chart endpoints of the mock BFF (create,
   update with versions, retire, duplicate, suggestions, CSV upload with dry run) and the product rules (a chart where
   the category requires one, a row for every size the variants sell, custom sizes and colours). */

const api = createSellerApiClient({ baseUrl: 'http://seller.test/api/bff', fetch: (req) => handleSellerMockRequest(req) });

beforeEach(() => resetSellerDb());

const chartInput = (name: string, overrides: Partial<SizeChartInput> = {}): SizeChartInput => ({
  name,
  unitSystem: 'CM',
  productTypeCode: 'dress',
  columns: [{ label: 'Bust' }, { label: 'Waist' }],
  rows: [
    { size: 'S', values: { bust: { min: 84, max: 87 }, waist: { min: 66, max: 69 } } },
    { size: 'UK 12', values: { bust: { min: 89 }, waist: { min: 71 } } }
  ],
  ...overrides
});

const fieldErrors = async (p: Promise<unknown>): Promise<Record<string, string>> => {
  try {
    await p;
  } catch (e) {
    if (isApiError(e) && e.status === 422) return e.fieldErrors;
    throw e;
  }
  throw new Error('expected a 422');
};

describe('size and colour values', () => {
  it('tidy spacing and compare ignoring case', () => {
    expect(valueKey('  UK   12 ')).toBe('uk 12');
    expect(canonicalValue('xl', ['S', 'XL'])).toBe('XL');
    expect(canonicalValue('  Free   size ', ['S', 'XL'])).toBe('Free size');
  });

  it('are limited in length and characters', () => {
    expect(customValueProblem('Size', 'UK 12')).toBeNull();
    expect(customValueProblem('Color', 'Indigo adire')).toBeNull();
    expect(customValueProblem('Size', '  ')).toBe('Choose or type a size');
    expect(customValueProblem('Size', 'x'.repeat(31))).toMatch(/at most 30/i);
    expect(customValueProblem('Size', '<b>')).toMatch(/letters, digits/i);
  });
});

describe('measurements', () => {
  it('read what a seller types and show it plainly', () => {
    expect(parseMeasurement('88')).toEqual({ min: 88, max: 88 });
    expect(parseMeasurement('86-90')).toEqual({ min: 86, max: 90 });
    expect(parseMeasurement('86 to 90 cm')).toEqual({ min: 86, max: 90 });
    expect(parseMeasurement('87,5')).toEqual({ min: 87.5, max: 87.5 });
    expect(parseMeasurement('wide')).toBeNull();
    expect(formatMeasurement({ min: 86, max: 90 })).toBe('86–90');
    expect(formatMeasurement({ min: 88, max: 88 })).toBe('88');
  });

  it('convert between centimetres and inches to one decimal', () => {
    expect(convertMeasurement(88, 'CM', 'IN')).toBe(34.6);
    expect(convertMeasurement(34, 'IN', 'CM')).toBe(86.4);
    expect(convertMeasurement(10, 'CM', 'CM')).toBe(10);
  });

  it('find the chart row of a size and the sizes a chart misses', () => {
    const chart = { name: 'Adire dresses', rows: [{ size: 'S', values: {}, alternateValues: {} }, { size: 'UK 12', values: {}, alternateValues: {} }] };
    expect(chartRowFor(chart, ' uk 12 ')?.size).toBe('UK 12');
    const missing = sizesMissingFromChart(chart, { sizeDimension: 'Size' }, [{ options: { Size: 'S' } }, { options: { Size: 'XL' } }, { options: { Size: 'xl' } }]);
    expect(missing).toHaveLength(1);
    expect(missing[0]).toMatchObject({ variantIndex: 1, size: 'XL' });
    expect(missing[0]?.message).toMatch(/no row in the size chart "Adire dresses"/);
  });
});

describe('the sized product types', () => {
  it('say which category is sized, whether a chart is required, and allow custom sizes and colours', async () => {
    const dress = await api.getProductType('dress');
    expect(dress.traits.sizing).toMatchObject({ sizeChartRequired: false, sizeDimension: 'Size' });
    expect(dress.traits.sizing?.suggestedMeasurements.map((m) => m.key)).toEqual(['bust', 'waist', 'hip']);
    const dims = dress.themes[0]?.dimensions ?? [];
    expect(dims.find((d) => d.name === 'Size')?.allowCustomValues).toBe(true);
    expect(dims.find((d) => d.name === 'Color')?.allowCustomValues).toBe(true);
    expect((await api.getProductType('shoes')).traits.sizing?.sizeChartRequired).toBe(true);
    expect((await api.getProductType('grocery')).traits.sizing).toBeNull();
  });

  it('suggest sizes and measurements for the category, and nothing for an unsized one', async () => {
    const dress = await api.getSizeChartSuggestions({ productTypeId: 'pt-dresses' });
    expect(dress).toMatchObject({ sized: true, sizeDimension: 'Size', sizesAllowCustomValues: true, unitSystem: 'CM' });
    expect(dress.sizes).toContain('XL');
    expect(dress.measurements.map((m) => m.label)).toEqual(['Bust', 'Waist', 'Hip']);
    expect(await api.getSizeChartSuggestions({ productType: 'book' })).toMatchObject({ sized: false, sizes: [], measurements: [] });
    await expect(api.getSizeChartSuggestions({})).rejects.toSatisfy((e) => isApiError(e) && e.status === 422);
  });
});

describe('size charts', () => {
  it('come with realistic women’s dress, men’s top and shoe charts', async () => {
    const page = await api.listSizeCharts();
    expect(page.items.map((c) => c.name).sort()).toEqual(['Adire dresses', 'Kaftans and shirts', 'Leather sandals']);
    const dresses = await api.getSizeChart(SIZE_CHART_IDS.dresses);
    expect(dresses.rows.map((r) => r.size)).toContain('UK 12');
    expect(dresses.rows[0]?.alternateValues.bust).toEqual({ min: 31.5, max: 32.7 });
    expect((await api.getSizeChart(SIZE_CHART_IDS.mensTops)).unitSystem).toBe('IN');
    expect((await api.getSizeChart(SIZE_CHART_IDS.shoes)).columns.map((c) => c.key)).toEqual(['foot_length']);
  });

  it('filter by product type and search by name', async () => {
    expect((await api.listSizeCharts({ productType: 'shoes' })).items.map((c) => c.name)).toEqual(['Leather sandals']);
    expect((await api.listSizeCharts({ q: 'KAFTAN' })).items).toHaveLength(1);
  });

  it('match the generated contract types', async () => {
    expectTypeOf<SizeChart>().toExtend<components['schemas']['SizeChart']>();
  });

  it('are created with converted values and a derived measurement key', async () => {
    const made = await api.createSizeChart(chartInput('Wedding dresses', { columns: [{ label: 'Foot length' }, { label: 'Waist' }], rows: [{ size: 'S', values: { foot_length: { min: 24 }, waist: { min: 66, max: 69 } } }] }));
    expect(made).toMatchObject({ name: 'Wedding dresses', version: 0, retired: false, productCount: 0, productTypeCode: 'dress' });
    expect(made.columns.map((c) => c.key)).toEqual(['foot_length', 'waist']);
    expect(made.rows[0]?.alternateValues.foot_length).toEqual({ min: 9.4, max: 9.4 });
    expect((await api.listSizeCharts()).items[0]?.id).toBe(made.id);
  });

  it('answer per-field messages', async () => {
    const errors = await fieldErrors(
      api.createSizeChart(
        chartInput('Adire dresses', {
          rows: [
            { size: 'S', values: { bust: { min: 90, max: 80 } } },
            { size: 's', values: { bust: { min: 84 }, waist: { min: 66 } } }
          ]
        })
      )
    );
    expect(errors.name).toBe('You already have a size chart called Adire dresses');
    expect(errors['rows[0].values.bust']).toMatch(/smallest value \(90\) is above the largest \(80\)/);
    expect(errors['rows[0].values.waist']).toBe('Enter the waist of size S');
    expect(errors['rows[1].size']).toMatch(/Duplicate size s/);
  });

  it('are updated with the loaded version, and a stale version is a conflict', async () => {
    const chart = await api.getSizeChart(SIZE_CHART_IDS.shoes);
    const input = (name: string, version: number): SizeChartInput => ({
      name,
      unitSystem: chart.unitSystem,
      productTypeCode: 'shoes',
      columns: chart.columns.map((c) => ({ key: c.key, label: c.label })),
      rows: chart.rows.map((r) => ({ size: r.size, values: r.values })),
      version
    });
    const saved = await api.updateSizeChart(chart.id, input('Leather sandals 2026', chart.version));
    expect(saved).toMatchObject({ name: 'Leather sandals 2026', version: chart.version + 1 });
    await expect(api.updateSizeChart(chart.id, input('Again', chart.version))).rejects.toSatisfy((e) => isApiError(e) && e.status === 409);
  });

  it('keep the rows of sizes that products still sell', async () => {
    const made = await api.createSizeChart(chartInput('Short run'));
    const draft = await sizedDraft(made.id, [{ Size: 'S' }, { Size: 'UK 12' }]);
    await api.submitProduct(draft);
    const errors = await fieldErrors(api.updateSizeChart(made.id, chartInput('Short run', { rows: [{ size: 'S', values: { bust: { min: 84 }, waist: { min: 66 } } }], version: made.version })));
    expect(errors.rows).toMatch(/still sell size UK 12/);
  });

  it('are retired, not deleted, and can be duplicated under a fresh name', async () => {
    const retired = await api.retireSizeChart(SIZE_CHART_IDS.mensTops);
    expect(retired).toMatchObject({ retired: true });
    expect((await api.listSizeCharts()).items.map((c) => c.id)).not.toContain(SIZE_CHART_IDS.mensTops);
    expect((await api.listSizeCharts({ includeRetired: true })).items.map((c) => c.id)).toContain(SIZE_CHART_IDS.mensTops);
    const copy = await api.duplicateSizeChart(SIZE_CHART_IDS.dresses);
    expect(copy).toMatchObject({ name: 'Adire dresses (copy)', version: 0, productCount: 0 });
    expect((await api.duplicateSizeChart(SIZE_CHART_IDS.dresses)).name).toBe('Adire dresses (copy 2)');
  });

  it('cannot be read by another organisation', async () => {
    await expect(api.getSizeChart('5c1e0000-0000-4000-8000-00000000ffff')).rejects.toSatisfy((e) => isApiError(e) && e.status === 404);
  });
});

async function sizedDraft(chartId: string | null, optionRows: Array<Record<string, string>>, categoryId = 'pt-dresses'): Promise<ProductDraft> {
  return {
    categoryId,
    title: 'Adire wrap dress',
    attributes: { brand: 'HANDMADE', material: 'Cotton', fit: 'Regular', length: 'Midi' },
    images: [{ url: 'https://img.afrotransact.dev/a.webp', width: 1600, height: 1600, bytes: 100_000, contentType: 'image/webp' }],
    variationTheme: 'Size × Color',
    variants: optionRows.map((options, i) => ({ sellerSku: `ADIRE-${i + 1}`, gtinExempt: true, options, priceMinor: 8500, quantity: 5 })),
    listing: { fulfilment: 'SELLER', sizeChartId: chartId, fitNote: 'RUNS_SMALL' }
  } as ProductDraft;
}

describe('products with a size chart', () => {
  it('may skip the chart when the category does not require one', async () => {
    const row = await api.submitProduct(await sizedDraft(null, [{ Size: 'S' }]));
    expect(row.status).toBe('IN_REVIEW');
  });

  it('need a chart when the category requires one', async () => {
    const draft = { ...(await sizedDraft(null, [{ Size: 'EU 38' }], 'pt-shoes')), attributes: { brand: 'HANDMADE', material: 'Leather' }, variationTheme: 'Size' };
    const errors = await fieldErrors(api.submitProduct(draft));
    expect(errors['listing.sizeChartId']).toMatch(/Add a size chart/);
  });

  it('need a row for every size sold, and never a chart of another organisation', async () => {
    const errors = await fieldErrors(api.submitProduct(await sizedDraft(SIZE_CHART_IDS.dresses, [{ Size: 'S' }, { Size: 'XXL' }])));
    expect(errors['variants[1].options.Size']).toMatch(/Size "XXL" has no row in the size chart "Adire dresses"/);
    const foreign = await fieldErrors(api.submitProduct(await sizedDraft('5c1e0000-0000-4000-8000-00000000ffff', [{ Size: 'S' }])));
    expect(foreign['listing.sizeChartId']).toBe('Size chart not found');
  });

  it('keep the chart link and fit note, and count the products of a chart', async () => {
    const row = await api.submitProduct(await sizedDraft(SIZE_CHART_IDS.dresses, [{ Size: 'S' }, { Size: 'uk 12' }]));
    const detail = await api.getProduct(row.productId);
    expect(detail.listing).toMatchObject({ sizeChartId: SIZE_CHART_IDS.dresses, fitNote: 'RUNS_SMALL' });
    expect((await api.getSizeChart(SIZE_CHART_IDS.dresses)).productCount).toBe(4);
  });

  it('cannot take a chart when the category is not sized', async () => {
    const draft = { ...(await sizedDraft(SIZE_CHART_IDS.dresses, [{ Size: 'S' }], 'pt-butters')), attributes: { brand: 'Shea', inci: 'Shea', skin: 'All', mocra: '123' }, variationTheme: 'Size' };
    const errors = await fieldErrors(api.submitProduct(draft));
    expect(errors['listing.sizeChartId']).toMatch(/not used for/);
  });
});

describe('size chart upload', () => {
  const csv = [
    'Size chart name *,Unit,Size *,Bust *,Waist *,Size fit,How to measure',
    'Wrap dresses,cm,S,84-87,66-69,True to size,Measure over light clothing',
    'Wrap dresses,,M,88-92,70-74,,',
    'Boubou,cm,Free size,100-120,abc,,'
  ].join('\r\n');
  const file = (text: string) => new File([text], 'charts.csv', { type: 'text/csv' });

  it('reports row by row and saves nothing on a dry run', async () => {
    const report = await api.importSizeCharts(file(csv), { productType: 'dress', dryRun: true });
    expect(report).toMatchObject({ applied: false, chartCount: 2, created: 1, failed: 1, errorRows: 1 });
    expect(report.rows.find((r) => r.chartName === 'Boubou')?.messages[0]).toMatchObject({ column: 'm:waist', severity: 'ERROR' });
    expect((await api.listSizeCharts()).items.map((c) => c.name)).not.toContain('Wrap dresses');
  });

  it('saves whole charts by name, and the same file again changes nothing', async () => {
    const first = await api.importSizeCharts(file(csv), { productType: 'dress' });
    expect(first).toMatchObject({ applied: true, created: 1, failed: 1 });
    const saved = (await api.listSizeCharts()).items.find((c) => c.name === 'Wrap dresses');
    expect(saved).toMatchObject({ unitSystem: 'CM', fitNote: 'TRUE_TO_SIZE', productTypeCode: 'dress' });
    expect(saved?.rows.map((r) => r.size)).toEqual(['S', 'M']);
    const again = await api.importSizeCharts(file(csv), { productType: 'dress' });
    expect(again).toMatchObject({ created: 0, updated: 0, unchanged: 1, failed: 1 });
    const changed = await api.importSizeCharts(file(csv.replace('88-92', '89-93')), { productType: 'dress' });
    expect(changed).toMatchObject({ updated: 1 });
  });

  it('rejects a file without the required columns', async () => {
    const errors = await fieldErrors(api.importSizeCharts(file('Name,Size\r\nA,S'), {}));
    expect(errors.file).toMatch(/Missing required column/);
  });

  it('serves a template with the category’s measurements', async () => {
    const template = await api.downloadSizeChartTemplate({ productType: 'shoes', format: 'csv' });
    const text = await template.blob.text();
    expect(text).toContain('Size chart name *');
    expect(text).toContain('Foot length *');
  });
});
