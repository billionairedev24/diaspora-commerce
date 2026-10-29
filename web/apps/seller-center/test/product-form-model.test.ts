import { type ProductTypeSchema, type SellerProductDetail } from '@afrotransact/api-client';
import { PRODUCT_TYPES } from '@afrotransact/api-client/mocks/seller';
import { describe, expect, it } from 'vitest';

import { cellProblems, fileProblem, importable, phaseOf, previewColumns, progress } from '@/components/listings/bulk/bulk-model';
import {
  applyToAll,
  brandOf,
  comboKey,
  combos,
  emptyForm,
  fieldId,
  fillSkus,
  type FormState,
  fromDetail,
  fromDraftPayload,
  gtinOk,
  leavesOf,
  packagingOf,
  previewAttributes,
  priceMinor,
  qualityItems,
  qualityScore,
  searchLeaves,
  sectionsFor,
  sectionStatus,
  suggestSku,
  toDraftPayload,
  toProductDraft,
  validateForm,
  withCategory
} from '@/components/listings/form-model';

const grocery = PRODUCT_TYPES.grocery as ProductTypeSchema;
const dress = PRODUCT_TYPES.dress as ProductTypeSchema;
const book = PRODUCT_TYPES.book as ProductTypeSchema;
const perishable = PRODUCT_TYPES.perishable as ProductTypeSchema;

const image = { id: 'i1', previewUrl: 'https://img/a.webp', url: 'https://img/a.webp', width: 1600, height: 1600, bytes: 1000, contentType: 'image/webp', status: 'ready' as const, alt: '' };

/** A complete simple grocery product. */
function suya(): FormState {
  return {
    ...withCategory(emptyForm(), { id: 'pt-spices', path: 'Groceries › Pantry › Spices & seasonings', productType: 'grocery' }),
    title: 'Suya Pepper Spice Blend from Kano',
    attrs: { brand: 'Mama Kano', ingredients: 'Groundnut, chilli', allergens: 'Contains peanuts', shelf: '12', storage: 'Room temperature', origin: 'Nigeria' },
    units: { shelf: 'months' },
    images: [image],
    noGtin: true,
    single: { sku: 'suya-1', gtin: '', price: '8.99', compareAt: '11.99', qty: '40', image: '' }
  };
}

describe('one-step product form: sections follow the product type', () => {
  it('shows food details for food, permits for regulated items, shipping only when the seller ships, pre-order when eligible', () => {
    expect(sectionsFor(undefined)).toEqual(['basics', 'images']);
    // Page order: Basics, Photos, Price & stock, Details, Variants, Shipping & fulfilment, Compliance.
    expect(sectionsFor(grocery)).toEqual(['basics', 'images', 'offer', 'details', 'variants', 'fulfilment', 'compliance']);
    expect(sectionsFor(dress)).toEqual(['basics', 'images', 'offer', 'details', 'variants', 'fulfilment']);
    expect(sectionsFor(perishable)).toContain('compliance');
    expect(sectionsFor(book)).not.toContain('compliance');
  });

  it('searches categories by every word, accent-insensitively', () => {
    const leaves = leavesOf([{ id: 'd', name: 'Beauty', level: 'DEPARTMENT', productType: null, path: 'Beauty', children: [
      { id: 'b', name: 'Butters & oils', level: 'PRODUCT_TYPE', productType: 'beauty', path: 'Beauty › Skin & body › Butters & oils', children: [] }] }]);
    expect(searchLeaves(leaves, 'skin butters').map((l) => l.id)).toEqual(['b']);
    expect(searchLeaves(leaves, 'BÉAUTY')).toHaveLength(1);
    expect(searchLeaves(leaves, 'dress')).toEqual([]);
  });
});

describe('one-step product form: validation and readiness', () => {
  it('lists what is missing with field ids the readiness panel links to', () => {
    const p = validateForm(withCategory(emptyForm(), { id: 'pt-spices', path: 'x', productType: 'grocery' }), grocery);
    expect(Object.keys(p)).toEqual(expect.arrayContaining([fieldId.title, fieldId.brand, fieldId.images, fieldId.attr('ingredients'), fieldId.attr('shelf'), fieldId.sku, fieldId.gtin, fieldId.price, fieldId.qty]));
    expect(p[fieldId.attr('ingredients')]?.section).toBe('compliance');
    expect(validateForm(emptyForm(), undefined)[fieldId.category]?.key).toBe('sc.pf.err.category');
  });

  it('accepts a complete simple product and checks prices, compare-at, GTIN and package', () => {
    expect(validateForm(suya(), grocery)).toEqual({});
    expect(validateForm({ ...suya(), single: { ...suya().single, compareAt: '5' } }, grocery)[fieldId.compareAt]?.key).toBe('sc.pf.err.compareAt');
    expect(validateForm({ ...suya(), noGtin: false, single: { ...suya().single, gtin: '4006381333932' } }, grocery)[fieldId.gtin]?.key).toBe('sc.pf.err.gtin');
    expect(validateForm({ ...suya(), noGtin: false, single: { ...suya().single, gtin: '4006381333931' } }, grocery)).toEqual({});
    expect(validateForm({ ...suya(), fulfilment: 'AFROTRANSACT' }, grocery)[fieldId.pkg]?.key).toBe('sc.pf.err.packageFba');
    expect(validateForm({ ...suya(), fulfilment: 'AFROTRANSACT', pkg: { length: '6', width: '4', height: '2', weight: '0.5' } }, grocery)).toEqual({});
    // The package can be typed in cm and kg: limits and the submission use inches and pounds.
    const metric = { ...suya(), fulfilment: 'AFROTRANSACT' as const, pkgUnits: { size: 'cm' as const, weight: 'kg' as const }, pkg: { length: '30', width: '20', height: '10', weight: '1' } };
    expect(packagingOf(metric)).toEqual({ lengthIn: 11.81, widthIn: 7.87, heightIn: 3.94, weightLb: 2.2 });
    expect(validateForm(metric, grocery)).toEqual({});
    expect(validateForm({ ...metric, pkg: { ...metric.pkg, length: '300' } }, grocery)[fieldId.pkg]?.key).toBe('sc.pf.err.packageLimits');
    expect(toProductDraft(metric, grocery).listing?.packaging).toEqual({ lengthIn: 11.81, widthIn: 7.87, heightIn: 3.94, weightLb: 2.2 });
    expect(validateForm({ ...suya(), attrs: { ...suya().attrs, shelf: 'a year' } }, grocery)[fieldId.attr('shelf')]?.key).toBe('sc.pf.err.number');
    expect(validateForm({ ...suya(), images: [{ ...image, status: 'low' }] }, grocery)[fieldId.images]?.key).toBe('sc.pf.err.imagesMainSmall');
    expect(priceMinor('$1,299.50')).toBe(129950);
    expect(priceMinor('12.5O')).toBeNull();
    expect(gtinOk('9780141439518')).toBe(true);
  });

  it('builds the variant matrix, fills SKUs and prices, and flags duplicates per row', () => {
    let s: FormState = { ...suya(), ...withCategory(suya(), { id: 'pt-dresses', path: 'Fashion › Women › Dresses', productType: 'dress' }), title: 'Ankara Wrap Dress', images: [image], noGtin: true,
      attrs: { brand: 'Adire', material: 'Cotton', fit: 'Regular', length: 'Midi' }, hasVariants: true, theme: 'Size × Color', selected: { Size: ['M', 'L'], Color: ['Gold'] } };
    expect(combos(dress, s).map(comboKey)).toEqual(['M / Gold', 'L / Gold']);
    expect(validateForm(s, dress)[fieldId.variant('M / Gold', 'sku')]?.key).toBe('sc.pf.err.skuRequired');
    s = applyToAll(dress, applyToAll(dress, fillSkus(dress, s), 'price', '49'), 'qty', '5');
    expect(s.rows['M / Gold']?.sku).toBe('ANKARA-WRAP-DRESS-M-GOLD');
    expect(validateForm(s, dress)).toEqual({});
    s = { ...s, rows: { ...s.rows, 'L / Gold': { ...(s.rows['L / Gold'] ?? s.single), sku: 'ANKARA-WRAP-DRESS-M-GOLD' } } };
    expect(validateForm(s, dress)[fieldId.variant('L / Gold', 'sku')]?.key).toBe('sc.pf.err.skuDuplicate');
    expect(suggestSku('Ọ̀gẹ̀dẹ̀ chips, sweet', ['6-pack'])).toBe('OGEDE-CHIPS-SWEET-6-PACK');
  });

  it('keeps live variants: removing one is a problem', () => {
    const s: FormState = { ...suya(), ...withCategory(suya(), { id: 'pt-dresses', path: 'x', productType: 'dress' }), title: 'Ankara Wrap Dress', images: [image], noGtin: true,
      attrs: { brand: 'A', material: 'Cotton', fit: 'Regular', length: 'Midi' }, hasVariants: true, theme: 'Size', selected: { Size: ['M'] },
      rows: { M: { sku: 'ANK-M', gtin: '', price: '49', compareAt: '', qty: '1', image: '' } } };
    expect(validateForm(s, dress, { lockedSkus: ['ANK-M', 'ANK-L'] })[fieldId.theme]?.key).toBe('sc.pf.err.liveVariants');
  });
});

describe('one-step product form: submission, edit and drafts', () => {
  it('submits listing details, units on number attributes and an uppercase SKU', () => {
    const d = toProductDraft({ ...suya(), keywords: 'suya, yaji ,', preorder: true }, grocery, 'draft-1');
    expect(d).toMatchObject({ categoryId: 'pt-spices', variationTheme: '', draftId: 'draft-1', preorderEligible: true });
    expect(d.attributes.shelf).toBe('12 months');
    expect(d.variants).toEqual([{ options: {}, sellerSku: 'SUYA-1', gtinExempt: true, priceMinor: 899, compareAtMinor: 1199, quantity: 40 }]);
    expect(d.listing).toMatchObject({ condition: 'NEW', keywords: ['suya', 'yaji'], fulfilment: 'SELLER', handlingDays: 2, packaging: null });
    expect(toProductDraft(suya(), book).preorderEligible).toBeUndefined();
  });

  it('stores the optional alt text with each image and drops it when empty', () => {
    const s = { ...suya(), images: [{ ...image, alt: ' Jar of suya spice on a white table ' }, { ...image, id: 'i2', url: 'https://img/b.webp', alt: '' }] };
    const images = toProductDraft(s, grocery).images;
    expect(images[0]).toMatchObject({ url: 'https://img/a.webp', alt: 'Jar of suya spice on a white table' });
    expect(images[1]).not.toHaveProperty('alt');
    const back = fromDraftPayload(JSON.parse(JSON.stringify(toDraftPayload(s))) as Record<string, unknown>);
    expect(back.images.map((i) => i.alt)).toEqual([' Jar of suya spice on a white table ', '']);
  });

  it('round-trips an existing listing and a saved draft', () => {
    const detail: SellerProductDetail = {
      productId: 'p1', atin: 'AT1', status: 'LIVE', statusLabel: 'Live', moderationNote: null, categoryId: 'pt-dresses', categoryRequestId: null, categoryPath: 'Fashion › Women › Dresses',
      productType: 'dress', title: 'Ankara Wrap Dress', attributes: { brand: 'Adire', material: 'Cotton' }, images: [{ url: 'https://img/a.webp', width: 1600, height: 1600, contentType: 'image/webp', alt: 'Front of the dress' }],
      variationTheme: 'Size', variants: [
        { atin: 'AT1', sellerSku: 'ANK-M', gtin: null, gtinExempt: true, options: { Size: 'M' }, priceMinor: 4900, compareAtMinor: null, quantity: 3, images: [] },
        { atin: 'AT1-2', sellerSku: 'ANK-L', gtin: null, gtinExempt: true, options: { Size: 'L' }, priceMinor: 5100, compareAtMinor: 6000, quantity: 0, images: [{ url: 'https://img/l.webp', contentType: 'image/webp' }] }
      ],
      preorderEligible: false, listing: { description: 'Wax print', condition: 'NEW', keywords: ['ankara'], fulfilment: 'SELLER', packaging: null, handlingDays: 3, shipFromId: null },
      currency: 'USD', version: 4, updatedAt: '2026-10-10T10:00:00Z'
    };
    const s = fromDetail(detail, dress);
    expect(s).toMatchObject({ hasVariants: true, theme: 'Size', selected: { Size: ['M', 'L'] }, noGtin: true, keywords: 'ankara', handlingDays: '3' });
    expect(s.rows.L).toMatchObject({ sku: 'ANK-L', price: '51.00', compareAt: '60.00', qty: '0' });
    expect(s.images).toHaveLength(2);
    expect(s.images[0]?.alt).toBe('Front of the dress');
    expect(toProductDraft(s, dress).variants[1]?.images?.[0]?.url).toBe('https://img/l.webp');

    const uploading = { ...suya(), images: [image, { ...image, id: 'i2', url: null, status: 'uploading' as const, previewUrl: 'blob:x' }] };
    const back = fromDraftPayload(JSON.parse(JSON.stringify(toDraftPayload(uploading))) as Record<string, unknown>);
    expect(back.images.map((i) => i.id)).toEqual(['i1']);
    expect({ ...back, images: [] }).toEqual({ ...suya(), images: [] });
    expect(fromDraftPayload({ title: 5, condition: 'BROKEN', rows: 'x' })).toMatchObject({ title: '', condition: 'NEW', rows: {} });
  });
});

describe('one-step product form: attributes of the type, completion and quality', () => {
  const cookbooks = { id: 'pt-cook', path: 'Books › Cookbooks', productType: 'book' };

  it('defines a brand only for types that have one: a book has none', () => {
    expect(brandOf(book)).toBeUndefined();
    expect(brandOf(dress)?.key).toBe('brand');
    expect(brandOf(undefined)).toBeUndefined();
    expect(validateForm({ ...withCategory(emptyForm(), cookbooks), title: 'Jollof Rice and Other Stories' }, book)[fieldId.brand]).toBeUndefined();
  });

  it('submits only attributes the product type defines (a brand typed for a previous category is dropped)', () => {
    const s = { ...withCategory(suya(), cookbooks), attrs: { brand: 'Mama Kano', author: 'Ade Bello', pages: '240' } };
    expect(toProductDraft(s, book).attributes).toEqual({ author: 'Ade Bello', pages: '240' });
  });

  it('counts the open required items per section', () => {
    const s = { ...withCategory(emptyForm(), cookbooks), title: 'Jollof Rice and Other Stories' };
    const status = sectionStatus(sectionsFor(book), validateForm(s, book));
    expect(status.basics).toEqual({ missing: 0, state: 'complete' });
    expect(status.images).toEqual({ missing: 1, state: 'incomplete' });
    expect(status.details).toEqual({ missing: 4, state: 'incomplete' });
    expect(status.fulfilment.state).toBe('complete');
    const ready = sectionStatus(sectionsFor(grocery), validateForm(suya(), grocery));
    expect(Object.values(ready).every((x) => x.state === 'complete')).toBe(true);
  });

  it('scores the checklist: required items plus a description and three images', () => {
    const s = suya();
    const items = qualityItems(s, grocery, validateForm(s, grocery));
    expect(items.filter((i) => i.required).every((i) => i.done)).toBe(true);
    expect(items.filter((i) => !i.required && !i.done).map((i) => i.tip)).toEqual(['sc.pf.tip.description', 'sc.pf.tip.images']);
    expect(qualityScore(items)).toBeGreaterThan(80);
    expect(qualityScore(items)).toBeLessThan(100);
    const full = { ...s, description: 'Hand-ground in Kano from groundnut, chilli and ginger, packed fresh.', images: [image, { ...image, id: 'i2' }, { ...image, id: 'i3' }] };
    expect(qualityScore(qualityItems(full, grocery, validateForm(full, grocery)))).toBe(100);
    expect(qualityScore(qualityItems(emptyForm(), undefined, validateForm(emptyForm(), undefined)))).toBeLessThan(20);
  });

  it('picks the key attributes for the storefront preview, brand or author first, with units', () => {
    expect(previewAttributes(grocery, suya()).map((a) => `${a.label}: ${a.value}`)).toEqual(['Brand: Mama Kano', 'Ingredients: Groundnut, chilli', 'Allergens (FALCPA): Contains peanuts', 'Shelf life: 12 months']);
    expect(previewAttributes(book, { ...emptyForm(), attrs: { pages: '240', author: 'Ade Bello' } }).map((a) => a.label)).toEqual(['Author', 'Pages']);
  });
});

describe('bulk upload rules', () => {
  it('checks files before uploading', () => {
    expect(fileProblem({ name: 'products.xls', size: 10 })?.key).toBe('sc.bulk.err.xls');
    expect(fileProblem({ name: 'products.numbers', size: 10 })?.key).toBe('sc.bulk.err.type');
    expect(fileProblem({ name: 'products.csv', size: 11 * 1024 * 1024 })).toEqual({ key: 'sc.bulk.err.size', vars: { mb: 10 } });
    expect(fileProblem({ name: 'Products.XLSX', size: 1000 })).toBeNull();
  });

  it('puts SKU, title, price and quantity first and maps messages to cells', () => {
    expect(previewColumns(['Parent SKU', 'SKU *', 'Category', 'Title *', 'Price (USD) *', 'Quantity *']).map((c) => c.label)).toEqual(['SKU', 'Title', 'Price (USD)', 'Quantity', 'Category', 'Parent SKU']);
    const row = { rowNumber: 3, groupKey: 'A', sku: 'A', status: 'ERROR' as const, outcome: 'PENDING' as const, outcomeNote: null, productId: null, cells: [], images: [],
      messages: [{ column: 'Price (USD) *', columnIndex: 4, severity: 'ERROR' as const, message: 'not a price' }, { column: null, columnIndex: -1, severity: 'ERROR' as const, message: 'sibling' }] };
    expect(cellProblems(row).get(4)?.[0]?.message).toBe('not a price');
    expect(cellProblems(row).get(-1)).toHaveLength(1);
  });

  it('follows the import through its phases', () => {
    const base = { id: 'i', reference: 'UPL-1', fileName: 'p.csv', format: 'CSV' as const, mode: 'CREATE' as const, productType: null, validOnly: true, totalRows: 10, validRows: 6, warningRows: 2,
      errorRows: 2, productCount: 5, processedRows: 4, createdProducts: 2, updatedProducts: 0, failedRows: 0, header: [], fileWarnings: [], uploadedByName: null, createdAt: '', startedAt: null,
      finishedAt: null, reportUrl: '' };
    expect(phaseOf({ ...base, status: 'VALIDATED' })).toBe('review');
    expect(phaseOf({ ...base, status: 'RUNNING' })).toBe('running');
    expect(phaseOf({ ...base, status: 'COMPLETED' })).toBe('done');
    expect(importable({ ...base, status: 'RUNNING' })).toBe(8);
    expect(progress({ ...base, status: 'RUNNING' })).toBe(50);
  });
});
