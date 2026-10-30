import {
  type AttributeDef,
  type CategoryNode,
  type ItemCondition,
  type ListingFulfilment,
  type ProductDraft,
  type ProductTypeSchema,
  type SellerProductDetail,
  type VariantInput
} from '@afrotransact/api-client';

import { type MessageKey } from '@/i18n/t';

/* Pure state and rules of the one-step product form (S307): which sections a product type shows, the variant matrix,
   validation with field ids (the readiness panel links to them), the catalog submission and the draft payload.
   Rules mirror catalog's DefaultProductDraftPolicy and the BFF's ProductDraft checks. Messages are i18n keys. */

export interface FormImage {
  id: string;
  /** What the form shows: a local object URL while uploading, then the stored URL. */
  previewUrl: string;
  url: string | null;
  width: number;
  height: number;
  bytes: number;
  contentType: string;
  status: 'uploading' | 'ready' | 'low' | 'error';
  /** Optional alternative text (what the picture shows), stored with the image. */
  alt: string;
}

export interface VariantRow {
  sku: string;
  gtin: string;
  price: string;
  compareAt: string;
  qty: string;
  /** id of one of the product's images ('' = the product's main image). */
  image: string;
}

export interface PackageInput {
  length: string;
  width: string;
  height: string;
  weight: string;
}

/** Units the seller types the package in; the catalog stores inches and pounds. */
export interface PackageUnits {
  size: 'in' | 'cm';
  weight: 'lb' | 'kg';
}
export const SIZE_UNITS: PackageUnits['size'][] = ['in', 'cm'];
export const WEIGHT_UNITS: PackageUnits['weight'][] = ['lb', 'kg'];

export interface FormState {
  categoryId: string | null;
  categoryPath: string;
  productType: string | null;
  /** A requested category (the product waits for its approval). */
  request: { id: string; path: string; reference: string } | null;
  title: string;
  attrs: Record<string, string>;
  /** Unit of each NUMBER attribute with units ("months"). */
  units: Record<string, string>;
  description: string;
  condition: ItemCondition;
  keywords: string;
  images: FormImage[];
  /** No manufacturer barcode (handmade, private label): every variant is GTIN-exempt. */
  noGtin: boolean;
  hasVariants: boolean;
  theme: string;
  selected: Record<string, string[]>;
  rows: Record<string, VariantRow>;
  single: VariantRow;
  fulfilment: ListingFulfilment;
  pkg: PackageInput;
  pkgUnits: PackageUnits;
  shipFromId: string;
  handlingDays: string;
  preorder: boolean;
}

export const emptyRow = (): VariantRow => ({ sku: '', gtin: '', price: '', compareAt: '', qty: '', image: '' });

export const emptyForm = (): FormState => ({
  categoryId: null, categoryPath: '', productType: null, request: null, title: '', attrs: {}, units: {}, description: '', condition: 'NEW', keywords: '',
  images: [], noGtin: false, hasVariants: false, theme: '', selected: {}, rows: {}, single: emptyRow(), fulfilment: 'SELLER',
  pkg: { length: '', width: '', height: '', weight: '' }, pkgUnits: { size: 'in', weight: 'lb' }, shipFromId: '', handlingDays: '2', preorder: false
});

export const MIN_IMAGE_PX = 1000;
export const MAX_IMAGES = 10;
export const MAX_IMAGE_BYTES = 8 * 1024 * 1024;
export const IMAGE_TYPES = ['image/jpeg', 'image/png', 'image/webp'] as const;
export const MAX_KEYWORDS = 20;
export const MAX_DESCRIPTION = 5000;
export const MAX_VARIANTS = 200;
export const CONDITIONS: ItemCondition[] = ['NEW', 'RENEWED', 'USED_LIKE_NEW', 'USED_VERY_GOOD', 'USED_GOOD', 'USED_ACCEPTABLE'];

/* ── Category ── */

export interface CategoryLeaf {
  id: string;
  name: string;
  path: string;
  productType: string;
}

/** Every product-type leaf of the tree (what the category search finds). */
export function leavesOf(tree: CategoryNode[]): CategoryLeaf[] {
  const out: CategoryLeaf[] = [];
  const walk = (nodes: CategoryNode[]) =>
    nodes.forEach((n) => {
      if (n.level === 'PRODUCT_TYPE' && n.productType) out.push({ id: n.id, name: n.name, path: n.path, productType: n.productType });
      walk(n.children);
    });
  walk(tree);
  return out;
}

/** Leaves whose path contains every word of the query (case- and accent-insensitive). */
export function searchLeaves(leaves: CategoryLeaf[], query: string, limit = 8): CategoryLeaf[] {
  const fold = (s: string) => s.normalize('NFD').replace(/\p{Diacritic}/gu, '').toLowerCase();
  const words = fold(query).split(/\s+/).filter(Boolean);
  if (!words.length) return [];
  return leaves.filter((l) => words.every((w) => fold(l.path).includes(w))).slice(0, limit);
}

/** The brand choice (name, registry id, no-brand reason) carried to another category; the brand field re-validates it there. */
const pickBrand = (attrs: Record<string, string>): Record<string, string> =>
  Object.fromEntries(['brand', 'brand_id', 'brand_reason'].flatMap((k) => (attrs[k] ? [[k, attrs[k]]] : [])));

/** Choosing another category resets what depends on the product type. */
export function withCategory(s: FormState, leaf: { id: string; path: string; productType: string } | null, request: FormState['request'] = null): FormState {
  return { ...s, categoryId: leaf?.id ?? null, categoryPath: request?.path ?? leaf?.path ?? '', productType: request ? 'custom' : (leaf?.productType ?? null), request,
    attrs: pickBrand(s.attrs), units: {}, theme: '', selected: {}, rows: {}, hasVariants: false };
}

/* ── Sections ── */

export type SectionKey = 'basics' | 'images' | 'offer' | 'details' | 'variants' | 'fulfilment' | 'compliance';

/** The sections the form shows for a product type, in page order: Basics, Photos, Price & stock, Details, Variants,
    Shipping & fulfilment, Compliance (the last three only when the product type has them). */
export function sectionsFor(type: ProductTypeSchema | undefined): SectionKey[] {
  if (!type) return ['basics', 'images'];
  const attrs = (section: AttributeDef['section']) => type.attributes.some((a) => a.section === section && a.key !== 'brand');
  const out: SectionKey[] = ['basics', 'images', 'offer'];
  if (attrs('DETAILS')) out.push('details');
  if (type.themes.length) out.push('variants');
  out.push('fulfilment');
  if (type.traits.food || attrs('FOOD') || type.traits.regulated || attrs('REGULATED')) out.push('compliance');
  return out;
}

/** Attributes shown in a section (brand is a basic field). */
export const attributesIn = (type: ProductTypeSchema | undefined, section: AttributeDef['section']) =>
  (type?.attributes ?? []).filter((a) => a.section === section && a.key !== 'brand');

/** The brand attribute of a product type: the Brand field is shown only when the type defines it (a book has an author, not a brand). */
export const brandOf = (type: ProductTypeSchema | undefined) => type?.attributes.find((a) => a.kind === 'BRAND') ?? type?.attributes.find((a) => a.key === 'brand');

/** Up to `limit` filled-in attributes for the shopper preview, brand / author first (what a search result line shows). */
export function previewAttributes(type: ProductTypeSchema | undefined, s: FormState, limit = 4): { label: string; value: string }[] {
  const defs = [...(type?.attributes ?? [])].sort((a, b) => Number(b.key === 'brand' || b.key === 'author') - Number(a.key === 'brand' || a.key === 'author'));
  return defs
    .map((a) => ({ label: a.label, value: attrValue(a, s.attrs[a.key] ?? '', s.units[a.key]) }))
    .filter((x) => x.value !== '' && x.value !== undefined)
    .slice(0, limit);
}

/* ── Variants ── */

/** Variant combinations of the chosen theme (cartesian product of the selected options). */
export function combos(type: ProductTypeSchema | undefined, s: FormState): string[][] {
  const theme = type?.themes.find((t) => t.name === s.theme);
  if (!theme) return [];
  return theme.dimensions
    .reduce<string[][]>((acc, d) => {
      const opts = s.selected[d.name] ?? [];
      return acc.flatMap((c) => opts.map((o) => [...c, o]));
    }, [[]])
    .filter((c) => c.length === theme.dimensions.length);
}

export const comboKey = (c: string[]) => c.join(' / ');

const slug = (v: string) => v.normalize('NFD').replace(/\p{Diacritic}/gu, '').toUpperCase().replace(/[^A-Z0-9]+/g, '-').replace(/^-|-$/g, '');

/** A SKU from the title and a variant's options ("ANKARA-WRAP-M-GOLD"), at most 40 characters. */
export function suggestSku(title: string, options: string[] = []): string {
  const base = slug(title).split('-').filter(Boolean).slice(0, 3).join('-') || 'SKU';
  return [base, ...options.map((o) => slug(o).slice(0, 6))].filter(Boolean).join('-').slice(0, 40);
}

/** Fills the empty SKUs of the matrix (or of the single offer) from the title. */
export function fillSkus(type: ProductTypeSchema | undefined, s: FormState): FormState {
  if (!s.hasVariants) return s.single.sku.trim() ? s : { ...s, single: { ...s.single, sku: suggestSku(s.title) } };
  const rows = { ...s.rows };
  combos(type, s).forEach((c) => {
    const k = comboKey(c);
    const r = rows[k] ?? emptyRow();
    if (!r.sku.trim()) rows[k] = { ...r, sku: suggestSku(s.title, c) };
  });
  return { ...s, rows };
}

/** Copies a price, compare-at price or quantity to every variant. */
export function applyToAll(type: ProductTypeSchema | undefined, s: FormState, field: 'price' | 'compareAt' | 'qty', value: string): FormState {
  const rows = { ...s.rows };
  combos(type, s).forEach((c) => {
    const k = comboKey(c);
    rows[k] = { ...(rows[k] ?? emptyRow()), [field]: value };
  });
  return { ...s, rows };
}

/* ── Validation ── */

/** GS1 mod-10 check digit (GTIN-8/12/13/14; ISBN-13 uses the same rule). */
export function gtinOk(code: string): boolean {
  if (!/^\d{8}$|^\d{12,14}$/.test(code)) return false;
  const d = code.split('').map(Number);
  const check = d.pop() ?? -1;
  const sum = d.reverse().reduce((a, x, i) => a + x * (i % 2 === 0 ? 3 : 1), 0);
  return (10 - (sum % 10)) % 10 === check;
}

/** Minor units of a typed price ("$1,299.50" → 129950); null when empty or not a positive amount. */
export function priceMinor(v: string): number | null {
  const t = v.replace(/[$,\s]/g, '');
  if (!/^\d+(\.\d{1,2})?$/.test(t)) return null;
  const n = Math.round(Number(t) * 100);
  return n > 0 ? n : null;
}

const positive = (v: string) => {
  const n = Number(v.trim());
  return v.trim() !== '' && Number.isFinite(n) && n > 0 ? n : null;
};

const round2 = (n: number) => Math.round(n * 100) / 100;

/** The package in the catalog's inches and pounds, whatever units it was typed in; null unless all four are positive numbers. */
export function packagingOf(s: Pick<FormState, 'pkg' | 'pkgUnits'>): { lengthIn: number; widthIn: number; heightIn: number; weightLb: number } | null {
  const [l, w, h, wt] = [s.pkg.length, s.pkg.width, s.pkg.height, s.pkg.weight].map(positive);
  if (l == null || w == null || h == null || wt == null) return null;
  const inch = (n: number) => round2(s.pkgUnits.size === 'cm' ? n / 2.54 : n);
  return { lengthIn: inch(l), widthIn: inch(w), heightIn: inch(h), weightLb: round2(s.pkgUnits.weight === 'kg' ? wt * 2.2046226 : wt) };
}

export const keywordsOf = (v: string) => v.split(',').map((k) => k.trim()).filter(Boolean);

export interface Problem {
  key: MessageKey;
  vars?: Record<string, string | number>;
  section: SectionKey;
  /** Short name of the field for the readiness panel: a message, or a variant / attribute name (plus a message). */
  label: MessageKey | { text: string; key?: MessageKey };
}

/** Field id → problem. Field ids are DOM ids, so the readiness panel can link to them. */
export type Problems = Record<string, Problem>;

export const fieldId = {
  category: 'pf-category',
  title: 'pf-title',
  brand: 'pf-brand',
  description: 'pf-description',
  keywords: 'pf-keywords',
  images: 'pf-images',
  attr: (key: string) => `pf-attr-${key}`,
  sku: 'pf-sku',
  gtin: 'pf-gtin',
  price: 'pf-price',
  compareAt: 'pf-compare-at',
  qty: 'pf-qty',
  theme: 'pf-theme',
  variant: (k: string, field: keyof VariantRow) => `pf-var-${slug(k).toLowerCase()}-${field}`,
  pkg: 'pf-pkg',
  shipFrom: 'pf-ship-from',
  handling: 'pf-handling'
} as const;

/** Every problem of the form (empty = ready to publish). */
export function validateForm(s: FormState, type: ProductTypeSchema | undefined, opts: { requireShipFrom?: boolean; lockedSkus?: string[] } = {}): Problems {
  const p: Problems = {};
  const add = (id: string, section: SectionKey, label: Problem['label'], key: MessageKey, vars?: Problem['vars']) => {
    if (!p[id]) p[id] = { key, vars, section, label };
  };
  if (!s.request && (!s.categoryId || !s.productType)) add(fieldId.category, 'basics', 'sc.pf.field.category', 'sc.pf.err.category');
  const title = s.title.trim();
  if (title.length < 10) add(fieldId.title, 'basics', 'sc.pf.field.title', title ? 'sc.pf.err.titleShort' : 'sc.pf.err.required');
  else if (title.length > 200) add(fieldId.title, 'basics', 'sc.pf.field.title', 'sc.pf.err.titleLong');
  const brandDef = brandOf(type);
  if (brandDef?.required && !(s.attrs.brand ?? '').trim() && !(s.attrs.brand_reason ?? '').trim()) add(fieldId.brand, 'basics', 'sc.pf.field.brand', 'sc.pf.err.required');
  if (s.description.length > MAX_DESCRIPTION) add(fieldId.description, 'basics', 'sc.pf.field.description', 'sc.pf.err.descriptionLong');
  const kws = keywordsOf(s.keywords);
  if (kws.length > MAX_KEYWORDS) add(fieldId.keywords, 'basics', 'sc.pf.field.keywords', 'sc.pf.err.keywordsMany');
  else if (kws.some((k) => k.length > 40)) add(fieldId.keywords, 'basics', 'sc.pf.field.keywords', 'sc.pf.err.keywordLong');

  if (!s.images.length) add(fieldId.images, 'images', 'sc.pf.field.images', 'sc.pf.err.imagesNone');
  else if (s.images.some((i) => i.status === 'uploading')) add(fieldId.images, 'images', 'sc.pf.field.images', 'sc.pf.err.imagesUploading');
  else if (s.images.some((i) => i.status === 'error')) add(fieldId.images, 'images', 'sc.pf.field.images', 'sc.pf.err.imagesFailed');
  else if (s.images[0]?.status !== 'ready') add(fieldId.images, 'images', 'sc.pf.field.images', 'sc.pf.err.imagesMainSmall', { px: MIN_IMAGE_PX });

  type?.attributes.forEach((a) => {
    if (a.key === 'brand') return;
    const v = (s.attrs[a.key] ?? '').trim();
    const section: SectionKey = a.section === 'DETAILS' ? 'details' : 'compliance';
    if (!v) {
      if (a.required) add(fieldId.attr(a.key), section, { text: a.label }, 'sc.pf.err.required');
      return;
    }
    if (a.kind === 'ENUM' && a.options.length && !a.options.includes(v)) add(fieldId.attr(a.key), section, { text: a.label }, 'sc.pf.err.chooseOption');
    if (a.kind === 'NUMBER' && positive(v) === null) add(fieldId.attr(a.key), section, { text: a.label }, 'sc.pf.err.number');
  });

  // Price, stock, identifiers and fulfilment appear once the product type is known.
  if (!type) return p;
  const gtinRule = type?.identifierRule === 'isbn' ? 'sc.pf.err.isbn' : 'sc.pf.err.gtin';
  const checkRow = (r: VariantRow, ids: Record<'sku' | 'gtin' | 'price' | 'compareAt' | 'qty', string>, section: SectionKey, name: string | null, skus: Set<string>) => {
    const lbl = (k: MessageKey): Problem['label'] => (name ? { text: name, key: k } : k);
    const sku = r.sku.trim().toUpperCase();
    if (!sku) add(ids.sku, section, lbl('sc.pf.field.sku'), 'sc.pf.err.skuRequired');
    else if (!/^[A-Z0-9._-]{2,40}$/.test(sku)) add(ids.sku, section, lbl('sc.pf.field.sku'), 'sc.pf.err.skuFormat');
    else if (skus.has(sku)) add(ids.sku, section, lbl('sc.pf.field.sku'), 'sc.pf.err.skuDuplicate');
    skus.add(sku);
    if (!s.noGtin && !gtinOk(r.gtin.trim())) add(ids.gtin, section, lbl('sc.pf.field.gtin'), r.gtin.trim() ? gtinRule : 'sc.pf.err.gtinMissing');
    const price = priceMinor(r.price);
    if (price === null) add(ids.price, section, lbl('sc.pf.field.price'), r.price.trim() ? 'sc.pf.err.price' : 'sc.pf.err.priceRequired');
    if (r.compareAt.trim()) {
      const cmp = priceMinor(r.compareAt);
      if (cmp === null) add(ids.compareAt, section, lbl('sc.pf.field.compareAt'), 'sc.pf.err.price');
      else if (price !== null && cmp <= price) add(ids.compareAt, section, lbl('sc.pf.field.compareAt'), 'sc.pf.err.compareAt');
    }
    if (!/^\d+$/.test(r.qty.trim()) || Number(r.qty) > 100_000) add(ids.qty, section, lbl('sc.pf.field.qty'), 'sc.pf.err.qty');
  };
  const skus = new Set<string>();
  if (s.hasVariants && type?.themes.length) {
    const list = combos(type, s);
    if (!s.theme) add(fieldId.theme, 'variants', 'sc.pf.field.theme', 'sc.pf.err.theme');
    else if (!list.length) add(fieldId.theme, 'variants', 'sc.pf.field.theme', 'sc.pf.err.options');
    else if (list.length > MAX_VARIANTS) add(fieldId.theme, 'variants', 'sc.pf.field.theme', 'sc.pf.err.tooManyVariants', { max: MAX_VARIANTS });
    list.slice(0, MAX_VARIANTS).forEach((c) => {
      const k = comboKey(c);
      const ids = { sku: fieldId.variant(k, 'sku'), gtin: fieldId.variant(k, 'gtin'), price: fieldId.variant(k, 'price'), compareAt: fieldId.variant(k, 'compareAt'), qty: fieldId.variant(k, 'qty') };
      checkRow(s.rows[k] ?? emptyRow(), ids, 'variants', k, skus);
    });
    const kept = new Set(list.map((c) => (s.rows[comboKey(c)]?.sku ?? '').trim().toUpperCase()));
    if ((opts.lockedSkus ?? []).some((sku) => !kept.has(sku.toUpperCase()))) add(fieldId.theme, 'variants', 'sc.pf.field.theme', 'sc.pf.err.liveVariants');
  } else {
    checkRow(s.single, { sku: fieldId.sku, gtin: fieldId.gtin, price: fieldId.price, compareAt: fieldId.compareAt, qty: fieldId.qty }, 'offer', null, skus);
    if ((opts.lockedSkus ?? []).length > 1) add(fieldId.theme, 'offer', 'sc.pf.field.theme', 'sc.pf.err.liveVariants');
  }

  const dims = [s.pkg.length, s.pkg.width, s.pkg.height, s.pkg.weight];
  const anyDim = dims.some((d) => d.trim() !== '');
  if (s.fulfilment === 'AFROTRANSACT' || anyDim) {
    const pkg = packagingOf(s);
    if (!pkg) add(fieldId.pkg, 'fulfilment', 'sc.pf.field.package', s.fulfilment === 'AFROTRANSACT' ? 'sc.pf.err.packageFba' : 'sc.pf.err.package');
    else if (pkg.lengthIn > 108 || pkg.widthIn > 108 || pkg.heightIn > 108 || pkg.weightLb > 150) add(fieldId.pkg, 'fulfilment', 'sc.pf.field.package', 'sc.pf.err.packageLimits');
  }
  if (s.fulfilment === 'SELLER') {
    if (!/^\d+$/.test(s.handlingDays.trim()) || Number(s.handlingDays) < 1 || Number(s.handlingDays) > 30) add(fieldId.handling, 'fulfilment', 'sc.pf.field.handling', 'sc.pf.err.handling');
    if (opts.requireShipFrom && !s.shipFromId) add(fieldId.shipFrom, 'fulfilment', 'sc.pf.field.shipFrom', 'sc.pf.err.shipFrom');
  }
  return p;
}

/** Order of the readiness panel: the order of the sections on the page. */
export function orderedProblems(p: Problems, sections: SectionKey[]): [string, Problem][] {
  const rank = (k: SectionKey) => {
    const i = sections.indexOf(k);
    return i < 0 ? 99 : i;
  };
  return Object.entries(p).sort((a, b) => rank(a[1].section) - rank(b[1].section));
}

/* ── Completion and listing quality ── */

export interface SectionStatus {
  /** Required items still open in the section. */
  missing: number;
  state: 'complete' | 'incomplete';
}

/** Per section: how many required things are still open (the navigator shows a check or "N to do"). */
export function sectionStatus(sections: SectionKey[], problems: Problems): Record<SectionKey, SectionStatus> {
  const out = {} as Record<SectionKey, SectionStatus>;
  sections.forEach((k) => {
    const missing = Object.values(problems).filter((p) => p.section === k).length;
    out[k] = { missing, state: missing === 0 ? 'complete' : 'incomplete' };
  });
  return out;
}

export interface QualityItem {
  id: string;
  section: SectionKey;
  label: Problem['label'];
  /** Required items block submission; recommended ones only raise the score. */
  required: boolean;
  done: boolean;
  /** Shown for recommended items that are not done. */
  tip?: MessageKey;
}

/** The checklist behind the quality score: every required field of the form (done = no problem) plus two recommendations
    (a description, three or more images). */
export function qualityItems(s: FormState, type: ProductTypeSchema | undefined, problems: Problems): QualityItem[] {
  const items: QualityItem[] = [];
  const req = (id: string, section: SectionKey, label: Problem['label'], done = !problems[id]) => items.push({ id, section, label, required: true, done });
  req(fieldId.category, 'basics', 'sc.pf.field.category');
  req(fieldId.title, 'basics', 'sc.pf.field.title');
  const brand = brandOf(type);
  if (brand?.required) req(fieldId.brand, 'basics', { text: brand.label });
  req(fieldId.images, 'images', 'sc.pf.field.images');
  type?.attributes.forEach((a) => {
    if (a.key === 'brand' || !a.required) return;
    req(fieldId.attr(a.key), a.section === 'DETAILS' ? 'details' : 'compliance', { text: a.label });
  });
  if (type) {
    if (s.hasVariants && type.themes.length) {
      const bad = Object.keys(problems).some((id) => id === fieldId.theme || id.startsWith('pf-var-'));
      req(fieldId.theme, 'variants', 'sc.pf.field.theme', !bad);
    } else {
      req(fieldId.sku, 'offer', 'sc.pf.field.sku');
      if (!s.noGtin) req(fieldId.gtin, 'offer', type.identifierRule === 'isbn' ? 'sc.pf.field.isbn' : 'sc.pf.field.gtin');
      req(fieldId.price, 'offer', 'sc.pf.field.price');
      req(fieldId.qty, 'offer', 'sc.pf.field.qty');
    }
    if (s.fulfilment === 'AFROTRANSACT') req(fieldId.pkg, 'fulfilment', 'sc.pf.field.package');
    else req(fieldId.handling, 'fulfilment', 'sc.pf.field.handling');
  }
  items.push({ id: fieldId.description, section: 'basics', label: 'sc.pf.field.description', required: false, done: s.description.trim().length >= 40, tip: 'sc.pf.tip.description' });
  items.push({ id: fieldId.images, section: 'images', label: 'sc.pf.field.images', required: false, done: s.images.filter((i) => i.url).length >= 3, tip: 'sc.pf.tip.images' });
  return items;
}

/** 0–100: the share of checklist items that are done. */
export const qualityScore = (items: QualityItem[]) => (items.length ? Math.round((100 * items.filter((i) => i.done).length) / items.length) : 0);

/** Server field errors ("title", "listing.packaging", "attributes.shelf", "variants") → form field ids. */
export function serverFieldId(field: string): string {
  if (field.startsWith('attributes.')) return fieldId.attr(field.slice('attributes.'.length));
  const map: Record<string, string> = {
    title: fieldId.title, images: fieldId.images, categoryId: fieldId.category, category: fieldId.category, variationTheme: fieldId.theme, variants: fieldId.theme,
    'listing.packaging': fieldId.pkg, 'listing.description': fieldId.description, 'listing.keywords': fieldId.keywords, 'listing.handlingDays': fieldId.handling,
    'listing.shipFromId': fieldId.shipFrom, description: fieldId.description, keywords: fieldId.keywords, handlingDays: fieldId.handling, packaging: fieldId.pkg
  };
  return map[field] ?? fieldId.title;
}

/* ── Submission ── */

function attrValue(a: AttributeDef | undefined, v: string, unit: string | undefined): string {
  const text = v.trim();
  return text && a?.kind === 'NUMBER' && a.units.length ? `${text} ${unit || a.units[0]}` : text;
}

/** The catalog submission (call when validateForm is empty). */
export function toProductDraft(s: FormState, type: ProductTypeSchema | undefined, draftId?: string | null): ProductDraft {
  const image = (id: string) => s.images.find((i) => i.id === id && i.url);
  const imageInput = (i: FormImage) => ({ url: i.url ?? '', width: i.width, height: i.height, bytes: i.bytes, contentType: i.contentType, ...(i.alt.trim() ? { alt: i.alt.trim() } : {}) });
  const variant = (r: VariantRow, options: Record<string, string>): VariantInput => {
    const img = r.image ? image(r.image) : undefined;
    const compare = r.compareAt.trim() ? priceMinor(r.compareAt) : null;
    return {
      options,
      sellerSku: r.sku.trim().toUpperCase(),
      ...(s.noGtin ? { gtinExempt: true } : { gtin: r.gtin.trim(), gtinExempt: false }),
      priceMinor: priceMinor(r.price) ?? 0,
      ...(compare ? { compareAtMinor: compare } : {}),
      quantity: Number(r.qty || '0'),
      ...(img?.url ? { images: [imageInput(img)] } : {})
    };
  };
  const theme = type?.themes.find((t) => t.name === s.theme);
  const variants =
    s.hasVariants && theme
      ? combos(type, s).map((c) => variant(s.rows[comboKey(c)] ?? emptyRow(), Object.fromEntries(theme.dimensions.map((d, i) => [d.name, c[i] ?? '']))))
      : [variant(s.single, {})];
  const packaging = packagingOf(s);
  return {
    ...(s.request ? { categoryRequestId: s.request.id } : { categoryId: s.categoryId ?? '' }),
    title: s.title.trim(),
    attributes: Object.fromEntries(
      Object.entries(s.attrs)
        .filter(([k, v]) => v.trim() !== '' && (!type || type.attributes.some((a) => a.key === k) || (Boolean(brandOf(type)) && (k === 'brand_id' || k === 'brand_reason'))))
        .map(([k, v]) => [k, attrValue(type?.attributes.find((a) => a.key === k), v, s.units[k])])
    ),
    images: s.images.filter((i) => i.url).map(imageInput),
    variationTheme: s.hasVariants && theme ? theme.name : '',
    variants,
    ...(type?.traits.preorder ? { preorderEligible: s.preorder } : {}),
    listing: {
      ...(s.description.trim() ? { description: s.description.trim() } : {}),
      condition: s.condition,
      keywords: keywordsOf(s.keywords),
      fulfilment: s.fulfilment,
      packaging,
      ...(s.fulfilment === 'SELLER' ? { handlingDays: Number(s.handlingDays) || 2, shipFromId: s.shipFromId || null } : {})
    },
    ...(draftId ? { draftId } : {})
  };
}

/* ── Edit and drafts ── */

const money = (minor: number | null) => (minor ? (minor / 100).toFixed(2) : '');

/** The form of an existing listing (edit). */
export function fromDetail(d: SellerProductDetail, type: ProductTypeSchema | undefined): FormState {
  const attrs: Record<string, string> = {};
  const units: Record<string, string> = {};
  Object.entries(d.attributes).forEach(([k, v]) => {
    const def = type?.attributes.find((a) => a.key === k);
    const m = def?.kind === 'NUMBER' && def.units.length ? /^(\S+)\s+(\S+)$/.exec(v) : null;
    if (m) {
      attrs[k] = m[1] ?? '';
      units[k] = m[2] ?? '';
    } else attrs[k] = v;
  });
  const images: FormImage[] = d.images.map((im, i) => ({ id: `img-${i}`, previewUrl: im.url, url: im.url, width: im.width ?? 0, height: im.height ?? 0, bytes: im.bytes ?? 0,
    contentType: im.contentType, status: (im.width ?? MIN_IMAGE_PX) < MIN_IMAGE_PX && i === 0 ? 'low' : 'ready', alt: im.alt ?? '' }));
  const rowOf = (v: SellerProductDetail['variants'][number]): VariantRow => {
    const img = v.images[0] ? images.find((i) => i.url === v.images[0]?.url) : undefined;
    let image = img?.id ?? '';
    if (v.images[0] && !img) {
      const extra: FormImage = { id: `img-${images.length}`, previewUrl: v.images[0].url, url: v.images[0].url, width: v.images[0].width ?? 0, height: v.images[0].height ?? 0,
        bytes: v.images[0].bytes ?? 0, contentType: v.images[0].contentType, status: 'ready', alt: v.images[0].alt ?? '' };
      images.push(extra);
      image = extra.id;
    }
    return { sku: v.sellerSku, gtin: v.gtin ?? '', price: money(v.priceMinor), compareAt: money(v.compareAtMinor), qty: String(v.quantity), image };
  };
  const theme = type?.themes.find((t) => t.name === d.variationTheme);
  const hasVariants = Boolean(d.variationTheme && theme);
  const selected: Record<string, string[]> = {};
  const rows: Record<string, VariantRow> = {};
  if (hasVariants && theme) {
    theme.dimensions.forEach((dim) => {
      selected[dim.name] = dim.options.filter((o) => d.variants.some((v) => v.options[dim.name] === o));
    });
    d.variants.forEach((v) => {
      rows[comboKey(theme.dimensions.map((dim) => v.options[dim.name] ?? ''))] = rowOf(v);
    });
  }
  const first = d.variants[0];
  const pkg = d.listing.packaging;
  return {
    categoryId: d.categoryId, categoryPath: d.categoryPath, productType: d.productType,
    request: d.categoryRequestId ? { id: d.categoryRequestId, path: d.categoryPath, reference: '' } : null,
    title: d.title, attrs, units, description: d.listing.description ?? '', condition: d.listing.condition, keywords: d.listing.keywords.join(', '), images,
    noGtin: d.variants.every((v) => v.gtinExempt), hasVariants, theme: hasVariants ? d.variationTheme : '', selected, rows,
    single: !hasVariants && first ? rowOf(first) : emptyRow(), fulfilment: d.listing.fulfilment,
    pkg: pkg ? { length: String(pkg.lengthIn), width: String(pkg.widthIn), height: String(pkg.heightIn), weight: String(pkg.weightLb) } : { length: '', width: '', height: '', weight: '' },
    pkgUnits: { size: 'in', weight: 'lb' },
    shipFromId: d.listing.shipFromId ?? '', handlingDays: String(d.listing.handlingDays || 2), preorder: d.preorderEligible
  };
}

/** What a draft stores: the form without upload state (images that never finished uploading are dropped). */
export function toDraftPayload(s: FormState): Record<string, unknown> {
  return { v: 1, ...s, images: s.images.filter((i) => i.url).map((i) => ({ ...i, previewUrl: i.url ?? '', status: i.status === 'low' ? 'low' : 'ready' })) };
}

const isRecord = (v: unknown): v is Record<string, unknown> => typeof v === 'object' && v !== null && !Array.isArray(v);
const str = (v: unknown, d = '') => (typeof v === 'string' ? v : d);
const strMap = (v: unknown): Record<string, string> => (isRecord(v) ? Object.fromEntries(Object.entries(v).filter((e): e is [string, string] => typeof e[1] === 'string')) : {});
const rowFrom = (v: unknown): VariantRow => {
  const r = isRecord(v) ? v : {};
  return { sku: str(r.sku), gtin: str(r.gtin), price: str(r.price), compareAt: str(r.compareAt), qty: str(r.qty), image: str(r.image) };
};

/** A saved draft back into the form (unknown or damaged fields fall back to empty). */
export function fromDraftPayload(payload: Record<string, unknown>): FormState {
  const e = emptyForm();
  const p = payload;
  const images = Array.isArray(p.images)
    ? p.images.filter(isRecord).filter((i) => typeof i.url === 'string').slice(0, MAX_IMAGES).map((i, n): FormImage => ({
        id: str(i.id, `img-${n}`), previewUrl: str(i.url), url: str(i.url), width: Number(i.width) || 0, height: Number(i.height) || 0, bytes: Number(i.bytes) || 0,
        contentType: str(i.contentType, 'image/jpeg'), status: i.status === 'low' ? 'low' : 'ready', alt: str(i.alt) }))
    : [];
  const req = isRecord(p.request) ? { id: str(p.request.id), path: str(p.request.path), reference: str(p.request.reference) } : null;
  const pkg = isRecord(p.pkg) ? p.pkg : {};
  const units = isRecord(p.pkgUnits) ? p.pkgUnits : {};
  const selected: Record<string, string[]> = {};
  if (isRecord(p.selected)) Object.entries(p.selected).forEach(([k, v]) => {
    if (Array.isArray(v)) selected[k] = v.filter((x): x is string => typeof x === 'string');
  });
  const rows: Record<string, VariantRow> = {};
  if (isRecord(p.rows)) Object.entries(p.rows).forEach(([k, v]) => {
    rows[k] = rowFrom(v);
  });
  return {
    ...e,
    categoryId: typeof p.categoryId === 'string' ? p.categoryId : null,
    categoryPath: str(p.categoryPath),
    productType: typeof p.productType === 'string' ? p.productType : null,
    request: req?.id ? req : null,
    title: str(p.title),
    attrs: strMap(p.attrs),
    units: strMap(p.units),
    description: str(p.description),
    condition: CONDITIONS.includes(p.condition as ItemCondition) ? (p.condition as ItemCondition) : 'NEW',
    keywords: str(p.keywords),
    images,
    noGtin: p.noGtin === true,
    hasVariants: p.hasVariants === true,
    theme: str(p.theme),
    selected,
    rows,
    single: rowFrom(p.single),
    fulfilment: p.fulfilment === 'AFROTRANSACT' ? 'AFROTRANSACT' : 'SELLER',
    pkg: { length: str(pkg.length), width: str(pkg.width), height: str(pkg.height), weight: str(pkg.weight) },
    pkgUnits: { size: units.size === 'cm' ? 'cm' : 'in', weight: units.weight === 'kg' ? 'kg' : 'lb' },
    shipFromId: str(p.shipFromId),
    handlingDays: str(p.handlingDays, '2'),
    preorder: p.preorder === true
  };
}

/** Whether the form holds anything worth autosaving. */
export const worthSaving = (s: FormState) => Boolean(s.categoryId || s.request || s.title.trim() || s.images.some((i) => i.url));
