/* Mock state and rules of the one-step product form (S307) and the bulk product upload (S308): product details for
   the edit form, server-side drafts, and a small CSV importer that validates like catalog-service (required columns,
   numbers, GTIN check digits, duplicate SKUs, https image links) so the preview, error report and import journey
   can be exercised without the services. Excel files are not parsed in the mock: an .xlsx upload yields a fixed
   sample with two errors. */
import {
  type ImportMode,
  type ImportRowMessage,
  type ListingRow,
  type ProductDraft,
  type ProductImport,
  type ProductImportRow,
  type SavedProductDraft,
  type SellerProductDetail
} from '../../schemas/seller';
import { CATEGORY_TREE, type MockSellerDb, PRODUCT_TYPES } from './db';

export type MockListing = MockSellerDb['listings'][number];

export interface MockImport extends ProductImport {
  orgId: string;
  rows: ProductImportRow[];
}

export interface MockDraft extends SavedProductDraft {
  orgId: string;
}

/** Bulk upload limits (catalog ImportLimits). */
export const IMPORT_LIMITS = { maxFileBytes: 10 * 1024 * 1024, maxRows: 5000, maxImagesPerProduct: 10, maxVariantsPerProduct: 200 };

interface Leaf {
  id: string;
  path: string;
  productType: string;
}

export function categoryLeaves(): Leaf[] {
  const out: Leaf[] = [];
  const walk = (nodes: typeof CATEGORY_TREE) =>
    nodes.forEach((n) => {
      if (n.productType) out.push({ id: n.id, path: n.path, productType: n.productType });
      walk(n.children);
    });
  walk(CATEGORY_TREE);
  return out;
}

/* ── Product detail (edit form) ── */

function guessLeaf(title: string): Leaf {
  const leaves = categoryLeaves();
  const pick = (id: string) => leaves.find((l) => l.id === id) ?? (leaves[0] as Leaf);
  if (/dress|stole|scarf/i.test(title)) return pick('pt-dresses');
  if (/butter|soap/i.test(title)) return pick('pt-butters');
  if (/frozen|leaves/i.test(title)) return pick('pt-frozen');
  if (/seeds|crayfish|leaf|beans/i.test(title)) return pick('pt-spices');
  return pick('pt-grains');
}

/** The detail of a listing: stored by the form, or derived from a seeded listing row. */
export function productDetail(db: MockSellerDb, l: MockListing): SellerProductDetail {
  const stored = db.productDetails[l.productId];
  if (stored) return { ...stored, status: l.status, statusLabel: l.statusLabel, moderationNote: l.moderationNote };
  const leaf = guessLeaf(l.title);
  const type = PRODUCT_TYPES[leaf.productType];
  const theme = l.variants.length > 1 ? (type?.themes[0] ?? null) : null;
  const dim = theme?.dimensions[0];
  const attributes: Record<string, string> = {};
  type?.attributes.forEach((a) => {
    if (!a.required) return;
    attributes[a.key] = a.key === 'brand' ? 'Ade’s Kitchen' : a.kind === 'ENUM' ? (a.options[0] ?? '') : a.kind === 'NUMBER' ? `12 ${a.units[0] ?? ''}`.trim() : 'See the pack';
  });
  return {
    productId: l.productId,
    atin: l.atin ?? '',
    status: l.status,
    statusLabel: l.statusLabel,
    moderationNote: l.moderationNote,
    categoryId: leaf.id,
    categoryRequestId: null,
    categoryPath: leaf.path,
    productType: leaf.productType,
    title: l.title,
    attributes,
    images: [{ url: l.imageUrl ?? `https://img.afrotransact.dev/${l.productId}.webp`, width: 1600, height: 1600, bytes: 180_000, contentType: 'image/webp' }],
    variationTheme: theme?.name ?? '',
    variants: l.variants.map((v, i) => ({
      atin: l.atin ? `${l.atin}${i ? `-${i + 1}` : ''}` : '',
      sellerSku: v.sku,
      gtin: null,
      gtinExempt: true,
      options: theme && dim ? { [dim.name]: dim.options[i] ?? `Option ${i + 1}` } : {},
      priceMinor: l.priceMinor + i * 200,
      compareAtMinor: null,
      quantity: v.quantity,
      images: []
    })),
    preorderEligible: type?.traits.preorder ?? false,
    listing: { description: null, condition: 'NEW', keywords: [], fulfilment: l.fulfilment === 'AFROTRANSACT' ? 'AFROTRANSACT' : 'SELLER',
      packaging: l.fulfilment === 'AFROTRANSACT' ? { lengthIn: 8, widthIn: 6, heightIn: 3, weightLb: 1.2 } : null, handlingDays: 2, shipFromId: null },
    currency: 'USD',
    version: 1,
    updatedAt: l.updatedAt
  };
}

/** The detail the form stores for a created or edited product. */
export function detailOf(productId: string, d: ProductDraft, row: ListingRow, version: number, leafPath: string, productType: string): SellerProductDetail {
  return {
    productId,
    atin: row.atin ?? '',
    status: row.status,
    statusLabel: row.statusLabel,
    moderationNote: row.moderationNote,
    categoryId: d.categoryId ?? null,
    categoryRequestId: d.categoryRequestId ?? null,
    categoryPath: leafPath,
    productType,
    title: d.title,
    attributes: d.attributes,
    images: d.images,
    variationTheme: d.variationTheme,
    variants: d.variants.map((v) => ({
      atin: '',
      sellerSku: v.sellerSku.toUpperCase(),
      gtin: v.gtin ?? null,
      gtinExempt: v.gtinExempt ?? !v.gtin,
      options: v.options,
      priceMinor: v.priceMinor,
      compareAtMinor: v.compareAtMinor ?? null,
      quantity: v.quantity,
      images: v.images ?? []
    })),
    preorderEligible: d.preorderEligible ?? false,
    listing: {
      description: d.listing?.description ?? null,
      condition: d.listing?.condition ?? 'NEW',
      keywords: d.listing?.keywords ?? [],
      fulfilment: d.listing?.fulfilment ?? 'SELLER',
      packaging: d.listing?.packaging ?? null,
      handlingDays: d.listing?.handlingDays ?? 2,
      shipFromId: d.listing?.shipFromId ?? null
    },
    currency: 'USD',
    version,
    updatedAt: row.updatedAt
  };
}

/** Field errors of the listing details (catalog DefaultProductDraftPolicy). */
export function listingProblems(d: ProductDraft): Record<string, string> {
  const errs: Record<string, string> = {};
  const l = d.listing;
  if (l?.fulfilment === 'AFROTRANSACT' && !l.packaging) errs['listing.packaging'] = 'Package dimensions and weight are required for Fulfilled by AfroTransact';
  d.variants.forEach((v, i) => {
    if (v.compareAtMinor != null && v.compareAtMinor <= v.priceMinor) errs[`variants[${i}].compareAtMinor`] = 'The compare-at price must be higher than the price';
    if (v.gtin && !gtinCheck(v.gtin)) errs[`variants[${i}].gtin`] = 'The check digit doesn’t match';
  });
  return errs;
}

/* ── Bulk upload ── */

export function gtinCheck(code: string): boolean {
  if (!/^\d{8}$|^\d{12,14}$/.test(code)) return false;
  const digits = code.split('').map(Number);
  const check = digits.pop() ?? 0;
  const sum = digits.reverse().reduce((a, d, i) => a + d * (i % 2 === 0 ? 3 : 1), 0);
  return (10 - (sum % 10)) % 10 === check;
}

/** RFC 4180 CSV (comma or semicolon), skipping # comment lines before the header. */
export function parseCsv(text: string): string[][] {
  const clean = text.replace(/^\uFEFF/, '');
  const lines = clean.split(/\r?\n/);
  const firstData = lines.find((l) => l.trim() !== '' && !l.startsWith('#')) ?? '';
  const sep = (firstData.match(/;/g)?.length ?? 0) > (firstData.match(/,/g)?.length ?? 0) ? ';' : ',';
  const rows: string[][] = [];
  let row: string[] = [];
  let cell = '';
  let quoted = false;
  let atLineStart = true;
  for (let i = 0; i < clean.length; i++) {
    const c = clean[i] as string;
    if (atLineStart && !quoted && c === '#') {
      const nl = clean.indexOf('\n', i);
      i = nl < 0 ? clean.length : nl;
      continue;
    }
    atLineStart = false;
    if (quoted) {
      if (c === '"' && clean[i + 1] === '"') {
        cell += '"';
        i++;
      } else if (c === '"') quoted = false;
      else cell += c;
    } else if (c === '"') quoted = true;
    else if (c === sep) {
      row.push(cell);
      cell = '';
    } else if (c === '\n' || c === '\r') {
      if (c === '\r' && clean[i + 1] === '\n') i++;
      row.push(cell);
      if (row.some((x) => x.trim() !== '')) rows.push(row);
      row = [];
      cell = '';
      atLineStart = true;
    } else cell += c;
  }
  row.push(cell);
  if (row.some((x) => x.trim() !== '')) rows.push(row);
  return rows;
}

const csvCell = (v: string) => {
  const safe = /^[=+\-@]/.test(v) ? `'${v}` : v;
  return /[",\n\r]/.test(safe) ? `"${safe.replace(/"/g, '""')}"` : safe;
};
export const toCsv = (rows: string[][]) => rows.map((r) => r.map(csvCell).join(',')).join('\r\n') + '\r\n';

interface Col {
  key: string;
  label: string;
  required: boolean;
  example: string;
}

/** The generic template's columns (labels as catalog writes them; required headers end with " *"). */
export const GENERIC_COLUMNS: Col[] = [
  { key: 'parent_sku', label: 'Parent SKU', required: false, example: 'ANKARA-WRAP' },
  { key: 'sku', label: 'SKU', required: true, example: 'ANKARA-WRAP-M-GLD' },
  { key: 'category', label: 'Category', required: true, example: 'Fashion › Women › Dresses' },
  { key: 'title', label: 'Title', required: true, example: 'Ankara Wrap Dress, Puff Sleeve' },
  { key: 'brand', label: 'Brand', required: false, example: 'Adire Lagos' },
  { key: 'description', label: 'Description', required: false, example: 'Hand-finished wax print wrap dress with puff sleeves.' },
  { key: 'condition', label: 'Condition', required: false, example: 'New' },
  { key: 'image_urls', label: 'Image URLs', required: true, example: 'https://example.com/ankara-front.jpg' },
  { key: 'keywords', label: 'Search keywords', required: false, example: 'ankara, wax print, owambe' },
  { key: 'price', label: 'Price (USD)', required: true, example: '49.00' },
  { key: 'compare_at_price', label: 'Compare-at price (USD)', required: false, example: '' },
  { key: 'quantity', label: 'Quantity', required: true, example: '12' },
  { key: 'gtin', label: 'Barcode (GTIN/UPC/EAN)', required: false, example: '' },
  { key: 'gtin_exempt', label: 'No barcode', required: false, example: 'Yes' },
  { key: 'variation_theme', label: 'Variation', required: false, example: 'Size × Color' },
  { key: 'options', label: 'Options', required: false, example: 'Size: M; Color: Gold' },
  { key: 'attributes', label: 'Attributes', required: false, example: 'material: Cotton wax print; fit: Regular' },
  { key: 'fulfilment', label: 'Fulfilment', required: false, example: 'Ships from you' },
  { key: 'handling_days', label: 'Handling time (days)', required: false, example: '2' }
];

export function templateCsv(productType: string | undefined): string {
  const typeLabel = productType ? (PRODUCT_TYPES[productType]?.label ?? productType) : 'any category';
  const comments = [
    `# AfroTransact product upload template (${typeLabel}). Columns marked * are required.`,
    '# One row per product, or one row per variant with the same Parent SKU. Up to 5,000 rows and 10 MB.',
    '# Delete these lines or leave them: lines starting with # are ignored.'
  ];
  return `${comments.join('\r\n')}\r\n${toCsv([GENERIC_COLUMNS.map((c) => (c.required ? `${c.label} *` : c.label)), GENERIC_COLUMNS.map((c) => c.example)])}`;
}

/** The sample an .xlsx upload yields in the mock (three products, two rows with errors). */
export const SAMPLE_UPLOAD: string[][] = [
  GENERIC_COLUMNS.map((c) => (c.required ? `${c.label} *` : c.label)),
  ['', 'JOLLOF-SPICE-4', 'Groceries › Pantry › Spices & seasonings', 'Jollof Rice Seasoning, Smoky, 4 oz', 'Ade’s Kitchen', 'Our house blend.', 'New', 'https://example.com/jollof.jpg', 'jollof, spice', '6.99', '', '40', '', 'Yes', '', '', 'ingredients: Paprika, thyme, curry; allergens: None; shelf: 12 months; storage: Room temperature; origin: Nigeria', 'Ships from you', '2'],
  ['', 'EGUSI-GROUND-1', 'Groceries › Pantry › Rice, grains & flours', 'Egusi, Ground, 1 lb', 'Ade’s Kitchen', '', 'New', 'https://example.com/egusi.jpg', '', '12.5O', '', '25', '4006381333932', 'No', '', '', '', 'Ships from you', '2'],
  ['ANKARA', 'ANKARA-M', 'Fashion › Women › Dresses', 'Ankara Wrap Dress, Puff Sleeve', 'Adire Lagos', '', 'New', 'https://example.com/ankara.jpg', 'ankara', '49.00', '65.00', '6', '', 'Yes', 'Size', 'Size: M', 'material: Cotton wax print; fit: Regular; length: Midi', 'Ships from you', '3'],
  ['ANKARA', 'ANKARA-L', 'Fashion › Women › Dresses', 'Ankara Wrap Dress, Puff Sleeve', 'Adire Lagos', '', 'New', 'https://example.com/ankara.jpg', 'ankara', '49.00', '65.00', '4', '', 'Yes', 'Size', 'Size: L', 'material: Cotton wax print; fit: Regular; length: Midi', 'Ships from you', '3'],
  ['', 'ANKARA-M', 'Fashion › Women › Dresses', 'Kente Graduation Stole, Gold', 'Bonwire', '', 'New', 'http://example.com/kente.jpg', '', '35.00', '', '10', '', 'Yes', '', '', 'material: Cotton', 'Ships from you', '2']
];

const normaliseHeader = (h: string) => h.replace(/\s*\*$/, '').trim().toLowerCase();

/** Validates an uploaded sheet like catalog's ImportValidator (a subset). */
export function validateSheet(sheet: string[][], mode: ImportMode, existingSkus: Set<string>): { header: string[]; rows: ProductImportRow[]; fileProblem: string | null } {
  const [header = [], ...data] = sheet;
  const index = new Map<string, number>();
  header.forEach((h, i) => {
    const col = GENERIC_COLUMNS.find((c) => c.key === normaliseHeader(h) || c.label.toLowerCase() === normaliseHeader(h));
    if (col) index.set(col.key, i);
  });
  const missing = GENERIC_COLUMNS.filter((c) => c.required && !index.has(c.key) && (mode === 'CREATE' || c.key === 'sku'));
  if (missing.length) return { header, rows: [], fileProblem: `The header row is missing ${missing.map((c) => `"${c.label}"`).join(', ')}. Download the template and keep its first row.` };
  if (data.length === 0) return { header, rows: [], fileProblem: 'The file has no product rows under the header.' };
  if (data.length > IMPORT_LIMITS.maxRows) return { header, rows: [], fileProblem: `The file has ${data.length} rows; upload at most 5,000 at a time.` };
  const leaves = categoryLeaves();
  const seen = new Map<string, number>();
  data.forEach((cells) => {
    const sku = (cells[index.get('sku') ?? -1] ?? '').trim().toUpperCase();
    if (sku) seen.set(sku, (seen.get(sku) ?? 0) + 1);
  });
  const rows = data.map((cells, i): ProductImportRow => {
    const get = (k: string) => (cells[index.get(k) ?? -1] ?? '').trim();
    const messages: ImportRowMessage[] = [];
    const err = (k: string, message: string, severity: 'ERROR' | 'WARNING' = 'ERROR') =>
      messages.push({ column: header[index.get(k) ?? -1] ?? k, columnIndex: index.get(k) ?? -1, severity, message });
    const sku = get('sku').toUpperCase();
    if (mode === 'CREATE') {
      GENERIC_COLUMNS.filter((c) => c.required && c.key !== 'sku').forEach((c) => {
        if (!get(c.key)) err(c.key, `${c.label} is required`);
      });
    }
    if (!sku) err('sku', 'SKU is required');
    else if (!/^[A-Z0-9._-]{2,40}$/.test(sku)) err('sku', 'Use 2–40 letters, digits, dot, dash or underscore');
    else if ((seen.get(sku) ?? 0) > 1) err('sku', 'This SKU appears more than once in the file');
    else if (mode === 'CREATE' && existingSkus.has(sku)) err('sku', 'You already have a listing with this SKU; use Update mode to change it');
    else if (mode === 'UPDATE' && !existingSkus.has(sku)) err('sku', 'No listing of yours has this SKU');
    const category = get('category');
    if (category && !leaves.some((l) => l.path === category)) err('category', 'Choose a category from the list');
    const title = get('title');
    if (title && (title.length < 10 || title.length > 200)) err('title', 'Use 10–200 characters');
    const price = get('price');
    if (price && !/^\d+(\.\d{1,2})?$/.test(price.replace(/^\$/, ''))) err('price', `"${price}" is not a price; use digits like 24.99`);
    const compare = get('compare_at_price');
    if (compare && price && Number(compare) <= Number(price)) err('compare_at_price', 'The compare-at price must be higher than the price');
    const qty = get('quantity');
    if (qty && (!/^\d+$/.test(qty) || Number(qty) > 100_000)) err('quantity', 'Use a whole number from 0 to 100,000');
    const gtin = get('gtin');
    if (gtin && !gtinCheck(gtin)) err('gtin', 'The barcode’s check digit doesn’t match; check the number on the pack');
    if (!gtin && mode === 'CREATE' && !/^(yes|y|true|1)$/i.test(get('gtin_exempt'))) err('gtin_exempt', 'Add the barcode, or answer Yes to "No barcode"');
    const images = get('image_urls').split(/[\s|]+/).filter(Boolean);
    if (images.length > IMPORT_LIMITS.maxImagesPerProduct) err('image_urls', 'Up to 10 images');
    images.forEach((u) => {
      if (!/^https:\/\/[^\s/]+\.[^\s/]+\/\S+/.test(u)) err('image_urls', `"${u}" is not an https image link`);
    });
    if (!get('description') && mode === 'CREATE') err('description', 'Add a description so shoppers know what they’re buying', 'WARNING');
    const status = messages.some((m) => m.severity === 'ERROR') ? 'ERROR' : messages.length ? 'WARNING' : 'VALID';
    return { rowNumber: i + 2, groupKey: get('parent_sku').toUpperCase() || sku || `row-${i + 2}`, sku, status, outcome: 'PENDING', outcomeNote: null, productId: null, messages, cells };
  });
  // A variant with errors holds back its siblings (catalog imports a product whole).
  const badGroups = new Set(rows.filter((r) => r.status === 'ERROR').map((r) => r.groupKey));
  rows.forEach((r) => {
    if (r.status !== 'ERROR' && badGroups.has(r.groupKey)) {
      r.status = 'ERROR';
      r.messages.push({ column: null, columnIndex: -1, severity: 'ERROR', message: 'Another variant of this product has errors; fix them to import the product' });
    }
  });
  return { header, rows, fileProblem: null };
}

export function importSummary(rows: ProductImportRow[]) {
  return {
    totalRows: rows.length,
    validRows: rows.filter((r) => r.status === 'VALID').length,
    warningRows: rows.filter((r) => r.status === 'WARNING').length,
    errorRows: rows.filter((r) => r.status === 'ERROR').length,
    productCount: new Set(rows.filter((r) => r.status !== 'ERROR').map((r) => r.groupKey)).size
  };
}

/** Advances a running import by one step (the mock's background job): two products per poll. */
export function advanceImport(imp: MockImport, now: Date, create: (rows: ProductImportRow[]) => string): void {
  if (imp.status !== 'RUNNING' && imp.status !== 'QUEUED') return;
  imp.status = 'RUNNING';
  const pending = imp.rows.filter((r) => r.outcome === 'PENDING' && r.status !== 'ERROR');
  const groups = [...new Set(pending.map((r) => r.groupKey))].slice(0, 2);
  groups.forEach((g) => {
    const rows = pending.filter((r) => r.groupKey === g);
    const productId = create(rows);
    rows.forEach((r) => {
      r.outcome = imp.mode === 'UPDATE' ? 'UPDATED' : 'CREATED';
      r.productId = productId;
    });
    if (imp.mode === 'UPDATE') imp.updatedProducts++;
    else imp.createdProducts++;
  });
  imp.processedRows = imp.rows.filter((r) => r.outcome !== 'PENDING').length;
  if (!imp.rows.some((r) => r.outcome === 'PENDING' && r.status !== 'ERROR')) {
    imp.rows.filter((r) => r.status === 'ERROR').forEach((r) => {
      r.outcome = 'SKIPPED';
      r.outcomeNote = 'Not imported: the row has errors';
    });
    imp.processedRows = imp.rows.length;
    imp.status = 'COMPLETED';
    imp.finishedAt = now.toISOString();
  }
}

/** The error report: the uploaded columns plus Row status and Errors (formula-like cells are prefixed). */
export function reportCsv(imp: MockImport): string {
  return toCsv([
    ['Row', 'Row status', ...imp.header, 'Errors'],
    ...imp.rows.map((r) => [String(r.rowNumber), r.status, ...r.cells, r.messages.map((m) => (m.column ? `${m.column}: ${m.message}` : m.message)).join(' | ')])
  ]);
}

export const draftView = ({ orgId: _o, ...d }: MockDraft): SavedProductDraft => d;
export const importView = ({ orgId: _o, rows: _r, ...i }: MockImport): ProductImport => i;
