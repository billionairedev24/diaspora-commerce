/* Mock of the offer upload, the price and quantity feed (catalog imports, modes OFFER and PRICE_QTY, S393 and S394) and the
   stock CSV import (inventory, S395). It validates the way the services do for the cases a seller meets (barcode check digit,
   a page that exists, a SKU the seller owns, held rows, a count below the reserved units) so the journey and the plain
   messages can be built and tested without the services. Real rules live in catalog and inventory. */
import { type ImportRowMessage, type ProductImportRow, type StockImport, type StockImportMode, type StockImportRow } from '../../schemas/seller';

const csvCell = (v: string) => {
  const safe = /^[=+\-@]/.test(v) ? `'${v}` : v;
  return /[",\n\r]/.test(safe) ? `"${safe.replace(/"/g, '""')}"` : safe;
};
const toCsv = (rows: string[][]) => rows.map((r) => r.map(csvCell).join(',')).join('\r\n') + '\r\n';

export const OFFER_HEADER = ['Barcode (UPC, EAN, ISBN)', 'ATIN', 'Brand', 'Part number (MPN)', 'Seller SKU', 'Condition', 'Condition note', 'Price *', 'List price', 'Quantity', 'Shipping charge', 'Fulfilment', 'Handling days'];
export const PRICE_QTY_HEADER = ['Seller SKU', 'ATIN', 'Price', 'List price', 'Quantity'];
export const STOCK_HEADER: Record<StockImportMode, string[]> = {
  ABSOLUTE: ['Seller SKU', 'Location', 'On hand', 'Lot', 'Expiry'],
  DELTA: ['Seller SKU', 'Location', 'Change', 'Lot', 'Expiry']
};

export const offerTemplateCsv = () =>
  `# AfroTransact offer upload. Sell products that are already on AfroTransact: no photos, titles or details needed.\r\n# Give the barcode, or the ATIN, or the brand and part number.\r\n${toCsv([OFFER_HEADER, ['4006381333931', '', '', '', 'MY-SKU-1', 'New', '', '24.99', '', '10', '0', 'Ships from you', '2']])}`;
export const priceQtyTemplateCsv = () =>
  `# AfroTransact price and quantity feed. A blank cell leaves that value unchanged.\r\n${toCsv([PRICE_QTY_HEADER, ['MY-SKU-1', '', '24.99', '', '10']])}`;
export const stockTemplateCsv = (mode: StockImportMode) => toCsv([STOCK_HEADER[mode], ['MY-SKU-1', 'HOU-FC', '120', '', '']]);

/** The sample an .xlsx offer upload yields in the mock: a page that exists, one held for tax, a bad barcode and an unknown page. */
export const OFFER_SAMPLE: string[][] = [
  OFFER_HEADER,
  ['4006381333931', '', '', '', 'OFADA-OFFER-1', 'New', '', '24.99', '', '10', '0', 'Ships from you', '2'],
  ['4006381333948', '', '', '', 'OFADA-OFFER-2', 'New', '', '41.00', '', '4', '0', 'Ships from you', '2'],
  ['4006381333932', '', '', '', 'OFADA-OFFER-3', 'New', '', '9.00', '', '1', '0', 'Ships from you', '2'],
  ['4006381333955', '', '', '', 'OFADA-OFFER-4', 'New', '', '12.50', '', '3', '0', 'Ships from you', '2']
];
/** The sample an .xlsx feed yields: two SKUs of the seller and one that is not. */
export const FEED_SAMPLE: string[][] = [PRICE_QTY_HEADER, ['OFADA-5LB', '', '24.99', '', '40'], ['OFADA-10LB', '', '', '', '12'], ['NOT-MINE-1', '', '5.00', '', '1']];

/** Pages the mock catalog knows by barcode: the second one has no tax decision yet, so an offer on it is held. */
export const KNOWN_PAGES: Record<string, { atin: string; heldForTax: boolean }> = {
  '4006381333931': { atin: 'ATADEV0001', heldForTax: false },
  '4006381333948': { atin: 'ATADEV0002', heldForTax: true }
};

export const gtinOk = (code: string): boolean => {
  const d = code.replace(/[\s-]/g, '');
  if (!/^\d{8}$|^\d{12,14}$/.test(d)) return false;
  const sum = [...d.slice(0, -1)].reverse().reduce((s, c, i) => s + Number(c) * (i % 2 === 0 ? 3 : 1), 0);
  return (10 - (sum % 10)) % 10 === Number(d.at(-1));
};

const norm = (h: string) => h.replace(/\s*\*$/, '').trim().toLowerCase();
const indexOf = (header: string[], ...names: string[]) => header.findIndex((h) => names.includes(norm(h)));

export interface FeedValidation {
  header: string[];
  rows: ProductImportRow[];
  fileProblem: string | null;
}

const row = (n: number, sku: string, cells: string[], messages: ImportRowMessage[], extra: Partial<ProductImportRow> = {}): ProductImportRow => ({
  rowNumber: n, groupKey: sku || `row-${n}`, sku, status: messages.some((m) => m.severity === 'ERROR') ? 'ERROR' : messages.length ? 'WARNING' : 'VALID', outcome: 'PENDING',
  outcomeNote: null, productId: null, atin: null, offerId: null, messages, cells, images: [], ...extra
});

/** OFFER mode: every row names a page (barcode, ATIN or brand and part number) and gives a price. */
export function validateOfferSheet(sheet: string[][], maxRows = 5000): FeedValidation {
  const [header = [], ...data] = sheet;
  const gtin = indexOf(header, 'barcode (upc, ean, isbn)', 'barcode', 'gtin');
  const atin = indexOf(header, 'atin');
  const brand = indexOf(header, 'brand');
  const mpn = indexOf(header, 'part number (mpn)', 'part number', 'mpn');
  const sku = indexOf(header, 'seller sku', 'sku', 'seller_sku');
  const price = indexOf(header, 'price');
  if (price < 0) return { header, rows: [], fileProblem: 'The header row is missing "Price". Download the template and keep its first row.' };
  if (gtin < 0 && atin < 0 && (brand < 0 || mpn < 0)) return { header, rows: [], fileProblem: 'The file needs a Barcode, ATIN, or Brand and Part number column. Download the template and keep its first row.' };
  if (data.length === 0) return { header, rows: [], fileProblem: 'The file has no rows under the header.' };
  if (data.length > maxRows) return { header, rows: [], fileProblem: `The file has ${data.length.toLocaleString()} rows; upload at most ${maxRows.toLocaleString()} at a time.` };
  const seen = new Set<string>();
  const rows = data.map((cells, i) => {
    const get = (k: number) => (k >= 0 ? (cells[k] ?? '').trim() : '');
    const messages: ImportRowMessage[] = [];
    const msg = (k: number, message: string, severity: 'ERROR' | 'WARNING' = 'ERROR') => messages.push({ column: header[k] ?? null, columnIndex: k, severity, message });
    const code = get(gtin).replace(/[\s-]/g, '');
    let page: { atin: string; heldForTax: boolean } | undefined;
    if (code) {
      if (!gtinOk(code)) msg(gtin, 'That barcode does not look right. Check the number on the pack.');
      else if (seen.has(code)) msg(gtin, 'This barcode appears twice in your file.');
      else {
        seen.add(code);
        page = KNOWN_PAGES[code];
        if (!page) msg(gtin, 'We could not find this product on AfroTransact. Add it as a new product instead.');
      }
    } else if (get(atin)) {
      page = Object.values(KNOWN_PAGES).find((p) => p.atin === get(atin).toUpperCase());
      if (!page) msg(atin, 'We could not find this product on AfroTransact.');
    } else if (!(get(brand) && get(mpn))) msg(gtin >= 0 ? gtin : atin, 'Give the barcode, the ATIN, or the brand and part number.');
    const p = get(price);
    if (!/^\d+(\.\d{1,2})?$/.test(p.replace(/^\$/, ''))) msg(price, p ? `"${p}" is not a price; use digits like 24.99` : 'Price is required');
    if (page?.heldForTax) messages.push({ column: null, columnIndex: -1, severity: 'WARNING', message: 'We are checking the tax details for this item.' });
    return row(i + 2, get(sku).toUpperCase(), cells, messages, { atin: page?.atin ?? null });
  });
  return { header, rows, fileProblem: null };
}

/** PRICE_QTY mode: change price and stock of the seller's own SKUs; a blank cell means unchanged. */
export function validateFeedSheet(sheet: string[][], mySkus: Set<string>, maxRows = 50_000): FeedValidation {
  const [header = [], ...data] = sheet;
  const sku = indexOf(header, 'seller sku', 'sku', 'seller_sku');
  const atin = indexOf(header, 'atin');
  const price = indexOf(header, 'price');
  const qty = indexOf(header, 'quantity');
  if (sku < 0 && atin < 0) return { header, rows: [], fileProblem: 'The file needs a Seller SKU or ATIN column. Download the template and keep its first row.' };
  if (data.length === 0) return { header, rows: [], fileProblem: 'The file has no rows under the header.' };
  if (data.length > maxRows) return { header, rows: [], fileProblem: `The file has ${data.length.toLocaleString()} rows; upload at most ${maxRows.toLocaleString()} at a time.` };
  const rows = data.map((cells, i) => {
    const get = (k: number) => (k >= 0 ? (cells[k] ?? '').trim() : '');
    const messages: ImportRowMessage[] = [];
    const msg = (k: number, message: string) => messages.push({ column: header[k] ?? null, columnIndex: k, severity: 'ERROR', message });
    const s = get(sku).toUpperCase();
    // A SKU of another seller looks exactly like an unknown one.
    if (s && !mySkus.has(s)) msg(sku, 'No listing of yours has this SKU.');
    if (!s && !get(atin)) msg(sku >= 0 ? sku : atin, 'Give the Seller SKU or the ATIN.');
    if (get(price) && !/^\d+(\.\d{1,2})?$/.test(get(price).replace(/^\$/, ''))) msg(price, `"${get(price)}" is not a price; use digits like 24.99`);
    if (get(qty) && (!/^\d+$/.test(get(qty)) || Number(get(qty)) > 99_999)) msg(qty, 'Use a whole number from 0 to 99,999');
    if (!get(price) && !get(qty)) messages.push({ column: null, columnIndex: -1, severity: 'WARNING', message: 'Price and quantity are both blank, so nothing changes.' });
    return row(i + 2, s, cells, messages);
  });
  return { header, rows, fileProblem: null };
}

/** The mock's background job for OFFER and PRICE_QTY: every importable row ends in an outcome at once. */
export function finishFeedRows(mode: 'OFFER' | 'PRICE_QTY', rows: ProductImportRow[]): void {
  rows.forEach((r) => {
    if (r.status === 'ERROR') {
      r.outcome = 'SKIPPED';
      r.outcomeNote = 'Not imported: the row has errors';
    } else if (mode === 'OFFER') {
      const held = r.messages.some((m) => m.message === 'We are checking the tax details for this item.');
      r.outcome = held ? 'HELD' : 'OFFER_CREATED';
      r.outcomeNote = held ? 'We are checking the tax details for this item.' : null;
      r.offerId = `off-${r.rowNumber}`;
    } else if (r.messages.some((m) => m.severity === 'WARNING')) {
      r.outcome = 'SKIPPED';
      r.outcomeNote = 'No change';
    } else r.outcome = 'UPDATED';
  });
}

/* ── Stock CSV import ── */

export interface MockStockImport extends StockImport {
  orgId: string;
  rows: StockImportRow[];
}

export const stockImportView = ({ orgId: _o, rows: _r, ...i }: MockStockImport): StockImport => i;

/** What the seller holds: SKU|LOCATION → on hand and reserved units. */
export interface MockHolding {
  onHand: number;
  reserved: number;
}

export function validateStockCsv(sheet: string[][], mode: StockImportMode, holdings: Map<string, MockHolding>, maxRows = 50_000): { header: string[]; rows: StockImportRow[]; fileProblem: string | null } {
  const [header = [], ...data] = sheet;
  const sku = indexOf(header, 'seller sku', 'sku', 'seller_sku');
  const loc = indexOf(header, 'location', 'node');
  const qty = indexOf(header, 'on hand', 'on_hand', 'count', 'quantity', 'change', 'delta');
  const lot = indexOf(header, 'lot', 'lot number');
  const expiry = indexOf(header, 'expiry', 'expiry date');
  for (const [k, label] of [[sku, 'Seller SKU'], [loc, 'Location'], [qty, mode === 'DELTA' ? 'Change' : 'On hand']] as const) {
    if (k < 0) return { header, rows: [], fileProblem: `The file has no ${label} column. Download the template and keep its header row` };
  }
  if (data.length === 0) return { header, rows: [], fileProblem: 'The file has no rows under the header. Download the template and fill it in' };
  if (data.length > maxRows) return { header, rows: [], fileProblem: `The file has ${data.length.toLocaleString()} rows; upload at most ${maxRows.toLocaleString()} rows per file` };
  const seen = new Set<string>();
  const rows = data.map((cells, i): StockImportRow => {
    const get = (k: number) => (k >= 0 ? (cells[k] ?? '').trim() : '');
    const messages: StockImportRow['messages'] = [];
    const err = (k: number, message: string) => messages.push({ column: header[k] ?? null, severity: 'ERROR', message });
    const s = get(sku).toUpperCase();
    const l = get(loc).toUpperCase();
    const key = `${s}|${l}`;
    const held = holdings.get(key);
    if (!s || !held) err(sku, 'No stock of yours has this SKU at this location.');
    if (seen.has(key)) err(sku, 'This SKU and location appear twice in your file.');
    seen.add(key);
    const raw = get(qty);
    const n = /^[+-]?\d{1,9}$/.test(raw) ? Number(raw) : null;
    if (n === null) err(qty, `"${raw}" is not a whole number.`);
    let after: number | null = null;
    if (n !== null && held) {
      after = mode === 'DELTA' ? held.onHand + n : n;
      if (after < 0) err(qty, 'The stock cannot go below zero.');
      else if (after > 99_999) err(qty, 'Use at most 99,999 units.');
      else if (after < held.reserved) err(qty, `${held.reserved} units are reserved for orders, so the count cannot be lower than ${held.reserved}.`);
    }
    const status = messages.some((m) => m.severity === 'ERROR') ? 'ERROR' : 'VALID';
    return {
      rowNumber: i + 2, sku: s, location: l, lot: get(lot) || null, quantity: n, expiry: get(expiry) || null, status, outcome: 'PENDING', outcomeNote: null,
      before: held?.onHand ?? null, after: status === 'ERROR' ? null : after, messages, cells
    };
  });
  return { header, rows, fileProblem: null };
}

/** Applies the importable rows at once and returns the new on-hand per SKU|LOCATION (the mock's ledger entries with the reason BULK_COUNT). */
export function applyStockRows(imp: MockStockImport, holdings: Map<string, MockHolding>): void {
  imp.rows.forEach((r) => {
    if (r.status === 'ERROR') {
      r.outcome = 'SKIPPED';
      r.outcomeNote = 'Not imported: the row has errors';
      return;
    }
    const h = holdings.get(`${r.sku}|${r.location}`);
    if (!h || r.after === null) return;
    if (h.onHand === r.after) r.outcome = 'UNCHANGED';
    else {
      h.onHand = r.after;
      r.outcome = 'APPLIED';
    }
  });
  imp.appliedRows = imp.rows.filter((r) => r.outcome === 'APPLIED').length;
  imp.unchangedRows = imp.rows.filter((r) => r.outcome === 'UNCHANGED').length;
  imp.processedRows = imp.rows.length;
  imp.status = 'COMPLETED';
}

export const stockReportCsv = (imp: MockStockImport): string =>
  toCsv([
    [...imp.header, 'Row', 'Result', 'Severity', 'Column', 'Message', 'Before', 'After'],
    ...imp.rows.map((r) => [...r.cells, String(r.rowNumber), r.outcome === 'PENDING' ? r.status : r.outcome, r.messages[0]?.severity ?? '', r.messages[0]?.column ?? '', r.messages[0]?.message ?? '', r.before === null ? '' : String(r.before), r.after === null ? '' : String(r.after)])
  ]);
