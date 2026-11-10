import { type ProductTypeSchema } from '@afrotransact/api-client';
import { TAX_PRODUCT_TYPES, TAX_TREE } from '@afrotransact/api-client/mocks/seller';
import { describe, expect, it } from 'vitest';

import {
  behaviourQuestions,
  emptyForm,
  fieldId,
  type FormState,
  fromDetail,
  leavesOf,
  sectionsFor,
  serverFieldId,
  toProductDraft,
  validateForm,
  variantCount,
  variantQuestions,
  withCategory
} from '@/components/listings/form-model';
import { EN_MESSAGES } from '@/i18n/t';

const snacks = TAX_PRODUCT_TYPES['packaged-snacks'] as ProductTypeSchema;
const bakery = TAX_PRODUCT_TYPES['bakery-products'] as ProductTypeSchema;
const image = { id: 'i1', previewUrl: 'https://img/a.webp', url: 'https://img/a.webp', width: 1600, height: 1600, bytes: 1000, contentType: 'image/webp', status: 'ready' as const, alt: '' };

const base = (type: ProductTypeSchema, extra: Partial<FormState> = {}): FormState => ({
  ...withCategory(emptyForm(), { id: 'x', path: 'x', productType: type.code }),
  title: 'Crispy plantain chips, 8 oz bag', images: [image], noGtin: true,
  attrs: { ingredients: 'Plantain, palm oil, salt', allergens: 'None' },
  single: { sku: '', gtin: '', price: '6.50', compareAt: '', qty: '20', image: '' }, ...extra
});

describe('tax questions in the product form', () => {
  it('adds a section for the product questions and keeps them out of the details', () => {
    expect(sectionsFor(snacks)).toContain('facts');
    expect(variantQuestions(snacks).map((q) => q.key)).toEqual(['is_candy', 'net_weight_oz', 'servings_per_package', 'individual_serving']);
    expect(behaviourQuestions(bakery).map((q) => q.key)).toEqual(['sold_heated', 'heated_by_seller', 'utensils_provided', 'seller_is_bakery']);
    expect(sectionsFor(TAX_PRODUCT_TYPES['rice-grains'])).not.toContain('facts');
  });

  it('asks for the answers a category needs and shows the same plain label as the server', () => {
    const p = validateForm(base(snacks, { facts: { '0': { is_candy: 'no' } } }), snacks);
    expect(Object.keys(p)).toEqual(expect.arrayContaining([fieldId.fact(0, 'net_weight_oz'), fieldId.fact(0, 'servings_per_package'), fieldId.fact(0, 'individual_serving')]));
    expect(p[fieldId.fact(0, 'net_weight_oz')]?.section).toBe('facts');
    expect(validateForm(base(snacks, { facts: { '0': { is_candy: 'yes' } } }), snacks)).toEqual({});
  });

  it('refuses wrong types and ranges with the server wording keys', () => {
    const p = validateForm(base(snacks, { facts: { '0': { is_candy: 'no', net_weight_oz: '0', servings_per_package: '1.5', individual_serving: 'maybe' } } }), snacks);
    expect(p[fieldId.fact(0, 'net_weight_oz')]?.key).toBe('sc.pf.err.number');
    expect(p[fieldId.fact(0, 'servings_per_package')]?.key).toBe('sc.pf.err.factWhole');
    expect(p[fieldId.fact(0, 'individual_serving')]?.key).toBe('sc.pf.err.factYesNo');
  });

  it('puts the seller behaviour questions on Price & stock and hides the ones that do not apply', () => {
    const none = validateForm(base(bakery), bakery);
    expect(none[fieldId.behaviour('sold_heated')]?.section).toBe('offer');
    const answered = validateForm(base(bakery, { behaviour: { sold_heated: 'no', utensils_provided: 'no' } }), bakery);
    expect(answered).toEqual({});
    const heated = validateForm(base(bakery, { behaviour: { sold_heated: 'yes', utensils_provided: 'no' } }), bakery);
    expect(Object.keys(heated)).toEqual([fieldId.behaviour('heated_by_seller')]);
  });

  it('sends typed answers per variant and behaviour with the listing, and leaves an empty SKU out', () => {
    const d = toProductDraft(base(bakery, { behaviour: { sold_heated: 'yes', heated_by_seller: 'no', utensils_provided: 'no', stale: 'x' } }), bakery);
    expect(d.variants[0]?.sellerSku).toBeUndefined();
    expect(d.listing?.sellerBehaviour).toEqual({ sold_heated: true, heated_by_seller: false, utensils_provided: false });
    const s = toProductDraft(base(snacks, { facts: { '0': { is_candy: 'no', net_weight_oz: '8.5', servings_per_package: '4', individual_serving: 'no' } } }), snacks);
    expect(s.variants[0]?.taxFacts).toEqual({ is_candy: false, net_weight_oz: 8.5, servings_per_package: 4, individual_serving: false });
  });

  it('a hidden answer never reaches the server', () => {
    const d = toProductDraft(base(snacks, { facts: { '0': { is_candy: 'yes', net_weight_oz: '8', servings_per_package: '3' } } }), snacks);
    expect(d.variants[0]?.taxFacts).toEqual({ is_candy: true });
  });

  it('maps server field errors to the question fields', () => {
    expect(serverFieldId('variants[2].taxFacts.net_weight_oz')).toBe(fieldId.fact(2, 'net_weight_oz'));
    expect(serverFieldId('sellerBehaviour.sold_heated')).toBe(fieldId.behaviour('sold_heated'));
    expect(variantCount(base(snacks), snacks)).toBe(1);
  });

  it('reads the stored answers back into the form', () => {
    const s = fromDetail({
      productId: 'p', atin: 'AT1', status: 'IN_REVIEW', statusLabel: 'In review', moderationNote: null, categoryId: 'c', categoryRequestId: null, categoryPath: 'x', productType: 'packaged-snacks', title: 'Crispy plantain chips',
      attributes: {}, images: [], variationTheme: '', variants: [{ atin: 'AT1', sellerSku: 'MOCK-1', gtin: null, gtinExempt: true, options: {}, priceMinor: 650, compareAtMinor: null, quantity: 3, images: [], sellerSkuGenerated: true, taxFacts: { is_candy: false, net_weight_oz: 8.5 }, taxStatus: 'MISSING_FACTS', missingTaxFacts: [] }],
      preorderEligible: false, listing: { description: null, condition: 'NEW', keywords: [], fulfilment: 'SELLER', packaging: null, handlingDays: 2, shipFromId: null, sizeChartId: null, fitNote: null, sellerBehaviour: { sold_heated: true } }, currency: 'USD', version: 1, updatedAt: '2026-11-09T10:00:00Z'
    }, snacks);
    expect(s.facts['0']).toEqual({ is_candy: 'no', net_weight_oz: '8.5' });
    expect(s.behaviour).toEqual({ sold_heated: 'yes' });
  });
});

describe('category gates and the new tree in the picker', () => {
  const leaves = leavesOf(TAX_TREE);
  it('lists a category once, with its tax gate, and fashion keeps its product types', () => {
    expect(leaves.find((l) => l.path === 'Groceries & Food › Nuts & seeds')?.taxStatus).toBe('ACTIVE');
    expect(leaves.filter((l) => l.path.startsWith('Fashion & Accessories › Adult clothing & footwear'))).toHaveLength(4);
    expect(leaves.find((l) => l.path === 'Other & Specialty › Gift baskets / bundles')?.taxStatus).toBe('BLOCKED_BUNDLE');
  });

  it('refuses a submission into a closed category, with the plain explanation, but not a REFINE_CODE one', () => {
    const closed = validateForm(base(snacks), snacks, { categoryStatus: 'NEEDS_APPROVAL' });
    expect(closed[fieldId.category]?.key).toBe('sc.pf.categoryGate.NEEDS_APPROVAL');
    expect(EN_MESSAGES['sc.pf.categoryGate.NEEDS_APPROVAL']).toContain('Save a draft');
    expect(validateForm(base(snacks, { facts: { '0': { is_candy: 'yes' } } }), snacks, { categoryStatus: 'REFINE_CODE' })).toEqual({});
  });
});

describe('Seller Center wording', () => {
  it('says Price & stock and Your listing, never "offer"', () => {
    expect(EN_MESSAGES['sc.pf.s.offer']).toBe('Price & stock');
    expect(EN_MESSAGES['sc.pf.facts.listing']).toBe('Your listing');
    const bad = Object.entries(EN_MESSAGES).filter(([k, v]) => /\boffers?\b/i.test(v) && !['sc.portalShell.offerYourServices', 'sc.cpickup.toggleHelp', 'sc.fees.deliveryOff'].includes(k));
    expect(bad.map(([k]) => k)).toEqual([]);
  });
});
