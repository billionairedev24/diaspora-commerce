import { type ProductDraft, type ProductTypeSchema } from '@afrotransact/api-client';

/* Pure state and rules of the 4-step product builder (category → images → details → variations), shared by the
   component and its tests. Rules mirror catalog's CreateProductRequest checks. */

export type StepKey = 'category' | 'images' | 'details' | 'variations';
export const STEPS: { key: StepKey; label: string }[] = [
  { key: 'category', label: 'Category' },
  { key: 'images', label: 'Images' },
  { key: 'details', label: 'Details' },
  { key: 'variations', label: 'Variations, price & stock' }
];

export interface BuilderImage {
  id: string;
  previewUrl: string;
  url: string | null;
  width: number;
  height: number;
  bytes: number;
  contentType: string;
  status: 'uploading' | 'ready' | 'low' | 'error';
}

export interface VariantRow {
  sku: string;
  gtin: string;
  price: string;
  qty: string;
}

export interface BuilderState {
  categoryId: string | null;
  categoryPath: string;
  productType: string | null;
  /** Set once the seller requests a new category: the form switches to the requested category. */
  request: { id: string; path: string; reference: string } | null;
  images: BuilderImage[];
  title: string;
  attrs: Record<string, string>;
  theme: string;
  selected: Record<string, string[]>;
  rows: Record<string, VariantRow>;
  exempt: boolean;
}

export const emptyBuilder = (): BuilderState => ({
  categoryId: null, categoryPath: '', productType: null, request: null, images: [], title: '', attrs: {}, theme: '', selected: {}, rows: {}, exempt: false
});

export const MIN_IMAGE_PX = 1000;
export const MAX_IMAGES = 10;
export const MAX_IMAGE_BYTES = 8 * 1024 * 1024;
export const IMAGE_TYPES = ['image/jpeg', 'image/png', 'image/webp'] as const;

/** Variant combinations of the chosen theme (cartesian product of the selected options). */
export function combos(type: ProductTypeSchema | undefined, s: BuilderState): string[][] {
  const theme = type?.themes.find((t) => t.name === s.theme);
  if (!theme) return [];
  return theme.dimensions.reduce<string[][]>((acc, d) => {
    const opts = s.selected[d.name] ?? [];
    return acc.flatMap((c) => opts.map((o) => [...c, o]));
  }, [[]]).filter((c) => c.length === theme.dimensions.length);
}

export const comboKey = (c: string[]) => c.join(' / ');

/** GS1 mod-10 check digit (GTIN-8/12/13/14; ISBN-13 uses the same rule). */
export function gtinOk(code: string): boolean {
  if (!/^\d{8}$|^\d{12,14}$/.test(code)) return false;
  const d = code.split('').map(Number);
  const check = d.pop() ?? -1;
  const sum = d.reverse().reduce((a, x, i) => a + x * (i % 2 === 0 ? 3 : 1), 0);
  return (10 - (sum % 10)) % 10 === check;
}

const price = (v: string) => {
  const n = Number(v.replace(/[$,\s]/g, ''));
  return v.trim() !== '' && Number.isFinite(n) && n > 0 ? Math.round(n * 100) : null;
};

/** Field errors that block Continue on a step (empty = the step is complete). */
export function validateStep(step: StepKey, s: BuilderState, type: ProductTypeSchema | undefined): Record<string, string> {
  const e: Record<string, string> = {};
  if (step === 'category') {
    if (!s.request && (!s.categoryId || !s.productType)) e.category = 'Choose a product type, or request a new category.';
  }
  if (step === 'images') {
    if (!s.images.length) e.images = 'Add at least one image.';
    else if (s.images.some((i) => i.status === 'uploading')) e.images = 'Wait for uploads to finish.';
    else if (s.images[0]?.status !== 'ready') e.images = `The main image must be at least ${MIN_IMAGE_PX} × ${MIN_IMAGE_PX} px.`;
    else if (s.images.some((i) => i.status === 'error')) e.images = 'Remove the images that failed to upload.';
  }
  if (step === 'details') {
    if (s.title.trim().length < 10) e.title = 'At least 10 characters';
    if (s.title.trim().length > 200) e.title = 'At most 200 characters';
    type?.attributes.forEach((a) => {
      if (a.required && !(s.attrs[a.key] ?? '').trim()) e[a.key] = 'Required';
    });
  }
  if (step === 'variations') {
    const list = combos(type, s);
    if (!s.theme) e.theme = 'Choose how this product varies.';
    else if (!list.length) e.theme = 'Pick at least one option for each variation.';
    const skus = new Set<string>();
    list.forEach((c) => {
      const k = comboKey(c);
      const r = s.rows[k] ?? { sku: '', gtin: '', price: '', qty: '' };
      if (!r.sku.trim()) e[`sku:${k}`] = 'SKU required';
      else if (skus.has(r.sku.trim())) e[`sku:${k}`] = 'SKUs must be unique';
      skus.add(r.sku.trim());
      if (!s.exempt && !gtinOk(r.gtin.trim())) e[`gtin:${k}`] = type?.identifierRule === 'isbn' ? 'Enter a valid ISBN-13' : 'Enter a valid barcode (GTIN)';
      if (price(r.price) === null) e[`price:${k}`] = 'Price required';
      if (!/^\d+$/.test(r.qty.trim())) e[`qty:${k}`] = 'Whole number';
    });
  }
  return e;
}

/** The catalog submission (call only after every step validates). */
export function toDraft(s: BuilderState, type: ProductTypeSchema | undefined): ProductDraft {
  return {
    ...(s.request ? { categoryRequestId: s.request.id } : { categoryId: s.categoryId ?? '' }),
    title: s.title.trim(),
    attributes: Object.fromEntries(Object.entries(s.attrs).filter(([, v]) => v.trim() !== '')),
    images: s.images.filter((i) => i.url).map((i) => ({ url: i.url ?? '', width: i.width, height: i.height, bytes: i.bytes, contentType: i.contentType })),
    variationTheme: s.theme,
    variants: combos(type, s).map((c) => {
      const theme = type?.themes.find((t) => t.name === s.theme);
      const r = s.rows[comboKey(c)] ?? { sku: '', gtin: '', price: '', qty: '0' };
      return {
        options: Object.fromEntries((theme?.dimensions ?? []).map((d, i) => [d.name, c[i] ?? ''])),
        sellerSku: r.sku.trim(),
        ...(s.exempt ? { gtinExempt: true } : { gtin: r.gtin.trim() }),
        priceMinor: price(r.price) ?? 0,
        quantity: Number(r.qty)
      };
    })
  };
}
