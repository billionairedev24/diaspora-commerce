import { type ListingRules, type ProductTypeSchema, type SellerProductDetail } from '@afrotransact/api-client';
import { PRODUCT_TYPES } from '@afrotransact/api-client/mocks/seller';
import { describe, expect, it } from 'vitest';

import {
  attributesIn,
  brandOf,
  copyPackageDown,
  emptyForm,
  emptyPackage,
  emptyRow,
  fieldId,
  type FormImage,
  type FormState,
  fromDetail,
  fromDraftPayload,
  isHidden,
  qualityItems,
  rowsOf,
  sectionsFor,
  serverFieldId,
  statusOf,
  toDraftPayload,
  toProductDraft,
  validateForm,
  withCategory
} from '@/components/listings/form-model';
import { useSellingStatus } from '@/lib/selling-status';

import { LISTING_RULE_CATEGORIES, listingRulesFor } from '../../../packages/api-client/src/mocks/seller/listing-rules';

/* The form follows catalog's listing rules (here read from the one source, tools/listing-rules/rules.json, through the mock
   service). One test per kind of product: an otherwise valid listing is refused for exactly what the matrix requires,
   and a listing with everything it asks for is accepted. The expected ids are written out from the approved matrix. */

const rulesOf = (code: string): ListingRules => {
  const r = listingRulesFor(code);
  if (!r) throw new Error(`no rules for ${code}`);
  return r;
};
const typeOf = (identifier: 'gtin' | 'isbn' = 'gtin'): ProductTypeSchema => ({
  code: 't', label: 'Test type', identifierRule: identifier, attributes: [], themes: [], traits: { food: false, perishable: false, regulated: false, preorder: false, sizing: null }
});
const sized = (): ProductTypeSchema => ({ ...typeOf(), themes: [{ name: 'Size', dimensions: [{ name: 'Size', options: ['S', 'M'], allowCustomValues: false }] }] });
const image: FormImage = { id: 'i1', previewUrl: 'https://img/a.webp', url: 'https://img/a.webp', width: 1600, height: 1600, bytes: 1000, contentType: 'image/webp', status: 'ready', alt: '' };
const label: FormImage = { ...image, id: 'img-label', url: 'https://img/label.webp', previewUrl: 'https://img/label.webp' };
const BOX = { length: '10', width: '8', height: '4', weight: '2' };

/** Valid title, one picture, a price and a quantity: only what the matrix requires is missing. */
function bare(): FormState {
  return { ...withCategory(emptyForm(), { id: 'leaf', path: 'x', productType: 't' }), title: 'A valid product title', images: [image], single: { ...emptyRow(), sku: 'SKU-1', price: '9.99', qty: '5' } };
}

const SAMPLE: Record<string, string> = { net_weight: '12', shelf_life: '12', servings: '4', storage: 'Room temperature', allergens: 'None' };

/** Everything the category's rules ask for. */
function complete(rules: ListingRules): FormState {
  let s = bare();
  const attrs: Record<string, string> = {};
  const units: Record<string, string> = {};
  rules.fields.forEach((f) => {
    if (f.attributeKey && f.id !== 'brand' && (f.status === 'required' || f.status === 'required_or_exemption')) {
      attrs[f.attributeKey] = SAMPLE[f.id] ?? (f.id === 'country_of_origin' ? 'Nigeria' : 'Sample');
      if (f.units.length) units[f.attributeKey] = f.units[0] ?? '';
    }
  });
  if (statusOf(rules, 'brand') === 'required') attrs.brand = 'Mama Kano';
  const code = rules.kind === 'BOK' ? '9780306406157' : '4006381333931';
  s = { ...s, attrs, units, description: 'Shopper description' };
  const idStatus = statusOf(rules, 'product_id');
  s = { ...s, single: { ...s.single, gtin: idStatus === 'required' || idStatus === 'required_or_exemption' ? code : '', pkg: statusOf(rules, 'package_size') === 'hidden' ? emptyPackage() : { ...BOX } } };
  if (statusOf(rules, 'label_photo') === 'required' || statusOf(rules, 'label_photo') === 'required_or_exemption') s = { ...s, labelImage: label };
  if (statusOf(rules, 'dangerous_goods') === 'required') s = { ...s, dangerousGoods: 'no' };
  return s;
}

const ids = (p: Record<string, unknown>) => Object.keys(p).sort();
const attr = fieldId.attr;
const GTIN = fieldId.gtin;
const PKG = fieldId.rowPkg('');

/** kind, a category of that kind, the field ids an otherwise valid but empty listing is refused for. */
const KINDS: [string, string, string[]][] = [
  ['PKG', 'beans-pantry-sauces', [fieldId.description, fieldId.labelPhoto, fieldId.brand, attr('origin'), attr('net_weight'), attr('servings'), attr('shelf'), GTIN, PKG]],
  ['PER', 'dairy-eggs', [fieldId.description, fieldId.labelPhoto, fieldId.brand, attr('origin'), attr('net_weight'), attr('shelf'), attr('storage'), PKG]],
  ['SFD', 'prepared-meals', [fieldId.description, attr('net_weight'), attr('servings'), attr('shelf'), attr('storage'), attr('ingredients'), attr('allergens'), PKG]],
  ['BBY', 'baby-bottles', [fieldId.description, fieldId.labelPhoto, fieldId.brand, attr('origin'), attr('net_weight'), attr('servings'), attr('shelf'), attr('age'.concat('_grade')), GTIN, PKG]],
  ['BEA', 'skincare-cosmetics', [fieldId.description, fieldId.labelPhoto, fieldId.dangerous, fieldId.brand, attr('origin'), attr('net_weight'), attr('ingredients'), attr('shelf'), GTIN, PKG]],
  ['HLT', 'dietary-supplements', [fieldId.description, fieldId.labelPhoto, fieldId.dangerous, fieldId.brand, attr('origin'), attr('net_weight'), attr('servings'), attr('shelf'), GTIN, PKG]],
  ['FSH', 'adult-clothing-footwear', [fieldId.description, fieldId.brand, attr('origin'), attr('material'), attr('age_grade'), GTIN, PKG]],
  ['JWL', 'jewelry-accessories', [fieldId.description, fieldId.brand, attr('origin'), attr('material'), GTIN, PKG]],
  ['ELC', 'phones', [fieldId.description, fieldId.dangerous, fieldId.brand, attr('origin'), attr('model'), GTIN, PKG]],
  ['HOM', 'bedding-linens', [fieldId.description, fieldId.dangerous, fieldId.brand, attr('origin'), attr('material'), attr('model'), GTIN, PKG]],
  ['KID', 'toys-games', [fieldId.description, fieldId.dangerous, fieldId.brand, attr('origin'), attr('age_grade'), GTIN, PKG]],
  ['PET', 'pet-food', [fieldId.description, fieldId.labelPhoto, fieldId.brand, attr('origin'), attr('net_weight'), attr('shelf'), GTIN, PKG]],
  ['BOK', 'printed-books', [fieldId.description, attr('origin'), GTIN, PKG]],
  ['DIG', 'downloadable-ebooks', [fieldId.description, fieldId.brand]],
  ['GFT', 'gift-cards', [fieldId.description, fieldId.brand]],
  ['SRV', 'repairs', [fieldId.description]]
];

describe('product form follows the listing rules: one test per kind', () => {
  it.each(KINDS)('%s (%s): an empty listing is refused for exactly what the matrix requires', (kind, category, expected) => {
    const rules = rulesOf(category);
    expect(rules.kind).toBe(kind);
    const problems = validateForm(bare(), typeOf(kind === 'BOK' ? 'isbn' : 'gtin'), { rules });

    expect(ids(problems)).toEqual([...expected].sort());
  });

  it.each(KINDS)('%s (%s): a listing with everything the matrix asks for is accepted and submitted without hidden answers', (kind, category) => {
    const rules = rulesOf(category);
    const type = typeOf(kind === 'BOK' ? 'isbn' : 'gtin');
    const s = complete(rules);

    expect(validateForm(s, type, { rules })).toEqual({});
    const draft = toProductDraft(s, type, null, rules);
    const hiddenKeys = rules.fields.filter((f) => f.status === 'hidden' && f.attributeKey).map((f) => f.attributeKey);
    expect(Object.keys(draft.attributes).filter((k) => hiddenKeys.includes(k))).toEqual([]);
    expect(draft.variants[0]?.packaging != null).toBe(statusOf(rules, 'package_size') !== 'hidden');
    expect(draft.images.some((i) => i.role === 'LABEL')).toBe(statusOf(rules, 'label_photo') === 'required' || statusOf(rules, 'label_photo') === 'required_or_exemption');
    expect(draft.listing?.dangerousGoods).toBe(statusOf(rules, 'dangerous_goods') === 'required' ? false : undefined);
  });

  it('every category of the rules loads with every field and an approval level', () => {
    expect(LISTING_RULE_CATEGORIES).toHaveLength(74);
    LISTING_RULE_CATEGORIES.forEach((code) => {
      const r = rulesOf(code);
      expect(r.fields, code).toHaveLength(31);
      expect(r.approval, code).toBeTruthy();
      expect(validateForm(complete(r), typeOf(r.kind === 'BOK' ? 'isbn' : 'gtin'), { rules: r }), code).toEqual({});
    });
    expect(rulesOf('fabrics-textiles').provisional).toBe(true);
    expect(rulesOf('general').kind).toBe('HOM');
    expect(listingRulesFor('nope')).toBeNull();
  });
});

describe('product form follows the listing rules: specific rules', () => {
  it('condition is shown only for electronics, books, general goods and toys, and sent as New elsewhere', () => {
    const shown = LISTING_RULE_CATEGORIES.filter((c) => !isHidden(rulesOf(c), 'condition')).map((c) => rulesOf(c).kind);
    expect([...new Set(shown)].sort()).toEqual(['BOK', 'ELC', 'HOM', 'KID']);
    expect(isHidden(rulesOf('car-seats'), 'condition')).toBe(true);
    const used = { ...complete(rulesOf('packaged-snacks')), condition: 'USED_GOOD' as const };
    expect(toProductDraft(used, typeOf(), null, rulesOf('packaged-snacks')).listing?.condition).toBe('NEW');
    const phone = { ...complete(rulesOf('phones')), condition: 'RENEWED' as const };
    expect(toProductDraft(phone, typeOf(), null, rulesOf('phones')).listing?.condition).toBe('RENEWED');
  });

  it('a required barcode has no exemption, a required-or-exemption one does, an optional one may be empty, a hidden one is not sent', () => {
    const electronics = rulesOf('phones');
    const exempt = { ...complete(electronics), noGtin: true, single: { ...complete(electronics).single, gtin: '' } };
    expect(validateForm(exempt, typeOf(), { rules: electronics })[GTIN]?.key).toBe('sc.pf.err.gtinMissing');
    expect(toProductDraft(exempt, typeOf(), null, electronics).variants[0]).toMatchObject({ gtin: '', gtinExempt: false });

    const general = rulesOf('bedding-linens');
    const g = { ...complete(general), noGtin: true, single: { ...complete(general).single, gtin: '' } };
    expect(validateForm(g, typeOf(), { rules: general })[GTIN]).toBeUndefined();
    expect(toProductDraft(g, typeOf(), null, general).variants[0]?.gtinExempt).toBe(true);

    const fresh = rulesOf('fresh-produce');
    const f = { ...complete(fresh), single: { ...complete(fresh).single, gtin: '' } };
    expect(validateForm(f, typeOf(), { rules: fresh })[GTIN]).toBeUndefined();
    expect(validateForm({ ...f, single: { ...f.single, gtin: '4006381333932' } }, typeOf(), { rules: fresh })[GTIN]?.key).toBe('sc.pf.err.gtin');

    const service = rulesOf('repairs');
    expect(toProductDraft(complete(service), typeOf(), null, service).variants[0]).toMatchObject({ gtinExempt: true });
  });

  it('a "required or exemption" field is answered or excused with a reason, and the reason is sent', () => {
    const rules = rulesOf('bedding-linens');
    const s = complete(rules);
    const noMaterial = { ...s, attrs: { ...s.attrs, material: '' } };
    expect(validateForm(noMaterial, typeOf(), { rules })[attr('material')]?.key).toBe('sc.pf.err.requiredOrReason');
    const excused = { ...noMaterial, exemptions: { material: ' Mixed handmade fabric ' } };
    expect(validateForm(excused, typeOf(), { rules })[attr('material')]).toBeUndefined();
    expect(toProductDraft(excused, typeOf(), null, rules).listing?.exemptions).toEqual({ material: 'Mixed handmade fabric' });
    // A reason for a field that must be answered is not sent.
    expect(toProductDraft({ ...s, exemptions: { origin: 'n/a', country_of_origin: 'n/a' } }, typeOf(), null, rules).listing?.exemptions).toBeUndefined();
  });

  it('the label photo is its own slot: pictures do not fill it, and a label alone is no picture', () => {
    const rules = rulesOf('packaged-snacks');
    const s = complete(rules);
    expect(validateForm({ ...s, labelImage: null }, typeOf(), { rules })).toHaveProperty([fieldId.labelPhoto]);
    expect(validateForm({ ...s, images: [] }, typeOf(), { rules })).toHaveProperty([fieldId.images]);
    expect(toProductDraft(s, typeOf(), null, rules).images.map((i) => i.role ?? 'photo')).toEqual(['photo', 'LABEL']);
    // Perishable food may give a reason instead of the label photo.
    const perishable = rulesOf('dairy-eggs');
    const p = { ...complete(perishable), labelImage: null, exemptions: { label_photo: 'Sold loose' } };
    expect(validateForm(p, typeOf(), { rules: perishable })[fieldId.labelPhoto]).toBeUndefined();
  });

  it('packaged groceries ask no typed ingredients, food service does; allergens are a tick list', () => {
    expect(statusOf(rulesOf('rice-grains'), 'ingredients')).toBe('hidden');
    expect(statusOf(rulesOf('prepared-meals'), 'ingredients')).toBe('required');
    expect(statusOf(rulesOf('prepared-meals'), 'allergens')).toBe('required');
    expect(statusOf(rulesOf('packaged-snacks'), 'allergens')).toBe('optional');
    const rules = rulesOf('prepared-meals');
    const s = complete(rules);
    expect(validateForm({ ...s, attrs: { ...s.attrs, allergens: 'Contains kryptonite' } }, typeOf(), { rules })[attr('allergens')]?.key).toBe('sc.pf.err.chooseOption');
    expect(validateForm({ ...s, attrs: { ...s.attrs, allergens: 'Contains milk, Contains eggs' } }, typeOf(), { rules })[attr('allergens')]).toBeUndefined();
    expect(validateForm({ ...s, attrs: { ...s.attrs, servings: '2.5' } }, typeOf(), { rules })[attr('servings')]?.key).toBe('sc.pf.err.whole');
  });

  it('the dangerous goods answer is asked where the matrix says, sent when answered, and never sent elsewhere', () => {
    const rules = rulesOf('perfumes-fragrances');
    const s = complete(rules);
    expect(validateForm({ ...s, dangerousGoods: '' }, typeOf(), { rules })[fieldId.dangerous]?.key).toBe('sc.pf.err.dangerous');
    expect(toProductDraft({ ...s, dangerousGoods: 'yes' }, typeOf(), null, rules).listing?.dangerousGoods).toBe(true);
    const snacks = rulesOf('packaged-snacks');
    expect(toProductDraft({ ...complete(snacks), dangerousGoods: 'yes' }, typeOf(), null, snacks).listing?.dangerousGoods).toBeUndefined();
  });

  it('sections follow the kind: details only where facts are asked, documents under compliance, no size guide without sizes', () => {
    const digital = sectionsFor(typeOf(), rulesOf('downloadable-ebooks'));
    expect(digital).toEqual(['basics', 'images', 'offer', 'fulfilment']);
    expect(sectionsFor(typeOf(), rulesOf('prepared-meals'))).toEqual(['basics', 'images', 'offer', 'details', 'fulfilment', 'compliance']);
    expect(sectionsFor(sized(), rulesOf('adult-clothing-footwear'))).not.toContain('sizeGuide');
    const dress = PRODUCT_TYPES.dress as ProductTypeSchema;
    expect(sectionsFor(dress, rulesOf('adult-clothing-footwear'))).toContain('sizeGuide');
    expect(sectionsFor(dress, rulesOf('bedding-linens'))).not.toContain('sizeGuide');
  });

  it('the rules own the questions product types used to ask on their own: those are not asked twice', () => {
    const grocery = PRODUCT_TYPES.grocery as ProductTypeSchema;
    const rules = rulesOf('beans-pantry-sauces');
    expect(attributesIn(grocery, 'FOOD', rules)).toEqual([]);
    expect(attributesIn(grocery, 'FOOD').length).toBeGreaterThan(0);
    // The brand field follows the rules, not the product type: digital goods have a brand, books do not.
    expect(brandOf(PRODUCT_TYPES.book as ProductTypeSchema, rulesOf('printed-books'))).toBeUndefined();
    expect(brandOf(typeOf(), rulesOf('downloadable-ebooks'))).toMatchObject({ required: true, options: ['UNBRANDED', 'HANDMADE'] });
  });

  it('the required summary lists exactly the required items of the category with their state', () => {
    const rules = rulesOf('dietary-supplements');
    const s = bare();
    const problems = validateForm(s, typeOf(), { rules });
    const required = qualityItems(s, typeOf(), problems, rules).filter((i) => i.required);
    const open = required.filter((i) => !i.done).map((i) => i.id);
    expect(open).toEqual(expect.arrayContaining([fieldId.labelPhoto, fieldId.dangerous, fieldId.brand, attr('net_weight'), GTIN, PKG]));
    expect(required.find((i) => i.id === fieldId.title)?.done).toBe(true);
  });
});

describe('product form: the package belongs to the variant', () => {
  const rules = rulesOf('packaged-snacks');
  const base = (): FormState => ({
    ...complete(rules), noGtin: true, hasVariants: true, theme: 'Size', selected: { Size: ['S', 'M'] },
    rows: { S: { ...emptyRow(), sku: 'SKU-S', price: '5', qty: '1', pkg: { ...BOX } }, M: { ...emptyRow(), sku: 'SKU-M', price: '6', qty: '1' } }
  });

  it('a variant without its own package starts from the first variant, and each may differ', () => {
    const s = base();
    expect(validateForm(s, sized(), { rules })).toEqual({});
    const draft = toProductDraft(s, sized(), null, rules);
    expect(draft.variants.map((v) => v.packaging)).toEqual([
      { lengthIn: 10, widthIn: 8, heightIn: 4, weightLb: 2 },
      { lengthIn: 10, widthIn: 8, heightIn: 4, weightLb: 2 }
    ]);
    const own = { ...s, rows: { ...s.rows, M: { ...(s.rows.M ?? emptyRow()), pkg: { length: '20', width: '12', height: '6', weight: '3' } } } };
    expect(toProductDraft(own, sized(), null, rules).variants[1]?.packaging).toEqual({ lengthIn: 20, widthIn: 12, heightIn: 6, weightLb: 3 });
    expect(toProductDraft(own, sized(), null, rules).listing?.packaging).toEqual({ lengthIn: 10, widthIn: 8, heightIn: 4, weightLb: 2 });
  });

  it('every physical variant needs a package; limits apply per variant; the first variant is the default', () => {
    const s = base();
    const none = { ...s, rows: { ...s.rows, S: { ...(s.rows.S ?? emptyRow()), pkg: emptyPackage() } } };
    expect(validateForm(none, sized(), { rules })[fieldId.rowPkg('S')]?.key).toBe('sc.pf.err.packageRequired');
    expect(validateForm(none, sized(), { rules })[fieldId.rowPkg('M')]?.key).toBe('sc.pf.err.packageRequired');
    const tooBig = { ...s, rows: { ...s.rows, M: { ...(s.rows.M ?? emptyRow()), pkg: { ...BOX, length: '109' } } } };
    expect(validateForm(tooBig, sized(), { rules })[fieldId.rowPkg('M')]?.key).toBe('sc.pf.err.packageLimits');
    expect(validateForm(tooBig, sized(), { rules })[fieldId.rowPkg('S')]).toBeUndefined();
    const half = { ...s, rows: { ...s.rows, M: { ...(s.rows.M ?? emptyRow()), pkg: { ...emptyPackage(), length: '4' } } } };
    expect(validateForm(half, sized(), { rules })[fieldId.rowPkg('M')]?.key).toBe('sc.pf.err.package');
  });

  it('"Same package for all variants" copies the first variant down; units convert cm and kg', () => {
    const s = { ...base(), pkgUnits: { size: 'cm' as const, weight: 'kg' as const } };
    const copied = copyPackageDown(sized(), s);
    expect(copied.rows.M?.pkg).toEqual(BOX);
    expect(rowsOf(copied, sized()).map((r) => r.key)).toEqual(['S', 'M']);
    expect(toProductDraft(copied, sized(), null, rules).variants[0]?.packaging).toEqual({ lengthIn: 3.94, widthIn: 3.15, heightIn: 1.57, weightLb: 4.41 });
  });

  it('digital goods and services have no package at all', () => {
    const digital = rulesOf('downloadable-ebooks');
    expect(toProductDraft(complete(digital), typeOf(), null, digital).variants[0]?.packaging).toBeUndefined();
    expect(toProductDraft(complete(digital), typeOf(), null, digital).listing?.packaging).toBeNull();
  });

  it('a listing saved with one package for all starts every variant from it; drafts saved before keep their package', () => {
    const detail = {
      productId: 'p1', atin: 'AT1', status: 'LIVE', statusLabel: 'Live', moderationNote: null, categoryId: 'leaf', categoryRequestId: null, categoryPath: 'x', productType: 't', title: 'A valid product title',
      attributes: {}, images: [{ url: 'https://img/a.webp', contentType: 'image/webp' }, { url: 'https://img/l.webp', contentType: 'image/webp', role: 'LABEL' as const }], variationTheme: 'Size',
      variants: [
        { atin: 'V1', sellerSku: 'S', gtin: null, gtinExempt: true, options: { Size: 'S' }, priceMinor: 500, compareAtMinor: null, quantity: 1, images: [], sellerSkuGenerated: false, taxFacts: {}, taxStatus: 'READY' as const, missingTaxFacts: [], packaging: { lengthIn: 20, widthIn: 12, heightIn: 6, weightLb: 3 } },
        { atin: 'V2', sellerSku: 'M', gtin: null, gtinExempt: true, options: { Size: 'M' }, priceMinor: 600, compareAtMinor: null, quantity: 1, images: [], sellerSkuGenerated: false, taxFacts: {}, taxStatus: 'READY' as const, missingTaxFacts: [], packaging: null }
      ],
      preorderEligible: false, currency: 'USD', version: 1, updatedAt: '2026-11-01T00:00:00Z',
      listing: { description: 'd', condition: 'NEW' as const, keywords: [], fulfilment: 'SELLER' as const, packaging: { lengthIn: 10, widthIn: 8, heightIn: 4, weightLb: 2 }, handlingDays: 2, shipFromId: null, sizeChartId: null, fitNote: null, sellerBehaviour: {}, dangerousGoods: true, documents: [], exemptions: { material: 'mixed' } }
    } as unknown as SellerProductDetail;
    const s = fromDetail(detail, sized());
    expect(s.rows.S?.pkg).toEqual({ length: '20', width: '12', height: '6', weight: '3' });
    expect(s.rows.M?.pkg).toEqual({ length: '10', width: '8', height: '4', weight: '2' });
    expect(s.labelImage?.url).toBe('https://img/l.webp');
    expect(s.images.map((i) => i.url)).toEqual(['https://img/a.webp']);
    expect(s.dangerousGoods).toBe('yes');
    expect(s.exemptions).toEqual({ material: 'mixed' });

    const legacy = fromDraftPayload({ ...toDraftPayload(emptyForm()), pkg: { length: '3', width: '2', height: '1', weight: '0.5' }, single: { sku: 'A', price: '1', qty: '1' }, rows: { S: { sku: 'S' } } });
    expect(legacy.single.pkg).toEqual({ length: '3', width: '2', height: '1', weight: '0.5' });
    expect(legacy.rows.S?.pkg).toEqual({ length: '3', width: '2', height: '1', weight: '0.5' });
  });
});

describe('product form: server errors land on the field they name', () => {
  it('maps variant, package, label photo and dangerous goods errors to their fields', () => {
    expect(serverFieldId('variants[1].packaging', ['S', 'M'])).toBe(fieldId.rowPkg('M'));
    expect(serverFieldId('variants[0].packaging', [''])).toBe(fieldId.rowPkg(''));
    expect(serverFieldId('variants[1].gtin', ['S', 'M'])).toBe(fieldId.variant('M', 'gtin'));
    expect(serverFieldId('variants[0].gtin', [''])).toBe(fieldId.gtin);
    expect(serverFieldId('labelPhoto')).toBe(fieldId.labelPhoto);
    expect(serverFieldId('dangerousGoods')).toBe(fieldId.dangerous);
    expect(serverFieldId('attributes.origin')).toBe(attr('origin'));
    expect(serverFieldId('description')).toBe(fieldId.description);
  });
});

describe('selling status placeholder', () => {
  it('answers Sell this product until the approval engine exposes its endpoint', () => {
    expect(useSellingStatus('dairy-eggs', 'Brand')).toMatchObject({ status: 'SELL', placeholder: true, loading: false });
  });
});
