import { beforeEach, describe, expect, expectTypeOf, it } from 'vitest';

import { type BrandList, type CreatedBrand, createSellerApiClient, isApiError } from '../src';
import type { components } from '../src/generated/gateway-bff-seller';
import { resetSellerDb } from '../src/mocks/seller';
import { handleSellerMockRequest } from '../src/mocks/seller-runtime';

/* The product form's brands: scoped to the chosen category's department, a typed brand is pending and the author's
   alone, names match ignoring case, accents and punctuation, and the type's own definition says whether a brand is asked. */

const api = createSellerApiClient({ baseUrl: 'http://seller.test/api/bff', fetch: (req) => handleSellerMockRequest(req) });
const names = (l: BrandList) => l.brands.map((b) => b.name);

beforeEach(() => resetSellerDb());

describe('brand choices', () => {
  it('match the generated contract types', () => {
    expectTypeOf<BrandList>().toExtend<components['schemas']['BrandList']>();
    expectTypeOf<CreatedBrand>().toExtend<components['schemas']['CreatedBrand']>();
  });

  it('never offer a brand of an unrelated department', async () => {
    const fashion = await api.listBrands({ category: 'pt-dresses' }, '');
    expect(fashion.departmentName).toBe('Fashion');
    expect(names(fashion)).toEqual(expect.arrayContaining(['Nike', 'Zara']));
    expect(names(fashion)).not.toEqual(expect.arrayContaining(['Maggi']));
    const groceries = await api.listBrands({ productType: 'grocery' }, '');
    expect(groceries.departmentName).toBe('Groceries');
    expect(names(groceries)).toContain('Maggi');
    expect(names(groceries)).not.toContain('Nike');
    expect(names(await api.listBrands({ category: 'pt-butters' }, ''))).not.toContain('Nike');
  });

  it('need a category or a product type, and filter by what was typed', async () => {
    await expect(api.listBrands({}, '')).rejects.toSatisfy((e) => isApiError(e) && e.status === 422);
    expect(names(await api.listBrands({ category: 'pt-dresses' }, 'LEVIS'))).toEqual(['Levi’s']);
  });
});

describe('typing a brand in', () => {
  it('creates a pending brand under its department only, usable and visible to its author', async () => {
    const made = await api.createBrand('Cotedivoire', { category: 'pt-dresses' });
    expect(made).toMatchObject({ created: true, brand: { name: 'Cotedivoire', status: 'PENDING', source: 'OWN' } });
    const fashion = await api.listBrands({ category: 'pt-dresses' }, '');
    expect(fashion.brands.find((b) => b.id === made.brand.id)).toMatchObject({ status: 'PENDING', source: 'OWN' });
    expect(names(await api.listBrands({ category: 'pt-spices' }, ''))).not.toContain('Cotedivoire');
    expect(names(await api.listBrands({ category: 'pt-butters' }, ''))).not.toContain('Cotedivoire');
  });

  it('returns the existing brand instead of a duplicate', async () => {
    const nike = await api.createBrand('NIKE', { category: 'pt-dresses' });
    expect(nike).toMatchObject({ created: false, brand: { name: 'Nike', status: 'APPROVED' } });
    const first = await api.createBrand('Adire Lagos', { category: 'pt-dresses' });
    const again = await api.createBrand(' adire   lagos ', { category: 'pt-dresses' });
    expect(again).toMatchObject({ created: false });
    expect(again.brand.id).toBe(first.brand.id);
    expect((await api.createBrand('Levis', { category: 'pt-dresses' })).brand.name).toBe('Levi’s');
  });

  it('refuses a name without letters and a missing category', async () => {
    await expect(api.createBrand('!!', { category: 'pt-dresses' })).rejects.toSatisfy((e) => isApiError(e) && e.status === 422);
    await expect(api.createBrand('Nike', {})).rejects.toSatisfy((e) => isApiError(e) && e.status === 422);
  });
});

describe('the brand attribute of a product type', () => {
  it('comes from the type definition: label, required, help and the no-brand choices; books have none', async () => {
    const dress = await api.getProductType('dress');
    expect(dress.attributes.find((a) => a.kind === 'BRAND')).toMatchObject({ key: 'brand', label: 'Brand or label', required: true, options: ['UNBRANDED', 'HANDMADE'] });
    const beauty = await api.getProductType('beauty');
    expect(beauty.attributes.find((a) => a.kind === 'BRAND')).toMatchObject({ required: true, options: [] });
    const grocery = await api.getProductType('grocery');
    expect(grocery.attributes.find((a) => a.kind === 'BRAND')).toMatchObject({ label: 'Brand', required: false });
    const book = await api.getProductType('book');
    expect(book.attributes.some((a) => a.kind === 'BRAND' || a.key === 'brand')).toBe(false);
  });
});
