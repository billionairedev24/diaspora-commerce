/*
 * Barcode encoders (design/at-barcode.js, made production-grade): Code 128 with automatic code set B / C switching,
 * GS1-128 (FNC1 start, FNC1 after variable-length AIs), EAN-13 / UPC-A, GTIN and SSCC check digits. Output is a list
 * of bars (module widths) that <Barcode> renders as SVG; nothing here touches the DOM.
 */

/** One bar or space, `w` modules wide. */
export interface Bar {
  w: number;
  on: boolean;
}

/** Code 128 symbol patterns (bar/space module widths), values 0–105; STOP is separate. */
const C128 =
  '212222 222122 222221 121223 121322 131222 122213 122312 132212 221213 221312 231212 112232 122132 122231 113222 123122 123221 223211 221132 221231 213212 223112 312131 311222 321122 321221 312212 322112 322211 212123 212321 232121 111323 131123 131321 112313 132113 132311 211313 231113 231311 112133 112331 132131 113123 113321 133121 313121 211331 231131 213113 213311 213131 311123 311321 331121 312113 312311 332111 314111 221411 431111 111224 111422 121124 121421 141122 141221 112214 112412 122114 122411 142112 142211 241211 221114 413111 241112 134111 111242 121142 121241 114212 124112 124211 411212 421112 421211 212141 214121 412121 111143 111341 131141 114113 114311 411113 411311 113141 114131 311141 411131 211412 211214 211232'.split(
    ' '
  );
const STOP = '2331112';
const START_B = 104;
const START_C = 105;
const CODE_B = 100;
const CODE_C = 99;
/** FNC1 (GS1 field separator and the GS1-128 start). */
export const FNC1 = 102;
/** Quiet zone in modules on each side. */
const QUIET = 10;

export class BarcodeError extends Error {}

function widthsToBars(widths: string): Bar[] {
  return widths.split('').map((n, i) => ({ w: Number(n), on: i % 2 === 0 }));
}

/** A run of `n` digits starting at `i` (code set C packs them in pairs). */
function digitRun(s: (string | typeof FNC1)[], i: number): number {
  let n = 0;
  while (i + n < s.length && typeof s[i + n] === 'string' && /[0-9]/.test(s[i + n] as string)) n++;
  return n;
}

/**
 * Code 128 symbol values (start, data, check) for a sequence of characters and FNC1 markers. Code set C is used for
 * runs of 4+ digits (2+ at the start or end), code set B for everything else (printable ASCII 32–126).
 */
export function code128Values(input: (string | typeof FNC1)[]): number[] {
  const chars = input.flatMap<string | typeof FNC1>((c) => (typeof c === 'string' ? [...c] : [c]));
  if (chars.length === 0) throw new BarcodeError('Nothing to encode');
  const values: number[] = [];
  let set: 'B' | 'C';
  const lead = digitRun(chars, 0);
  const startC = lead >= 4 || (lead >= 2 && lead === chars.length) || (chars[0] === FNC1 && digitRun(chars, 1) >= 2);
  set = startC ? 'C' : 'B';
  values.push(startC ? START_C : START_B);
  let i = 0;
  while (i < chars.length) {
    const c = chars[i];
    if (c === FNC1) {
      values.push(FNC1);
      i++;
      continue;
    }
    const run = digitRun(chars, i);
    if (set === 'B' && (run >= 4 && (run % 2 === 0 || i + run === chars.length || chars[i + run] === FNC1 || run >= 6))) {
      values.push(CODE_C);
      set = 'C';
    }
    if (set === 'C') {
      if (run >= 2) {
        values.push(Number((c as string) + (chars[i + 1] as string)));
        i += 2;
        continue;
      }
      values.push(CODE_B);
      set = 'B';
    }
    const v = (c as string).charCodeAt(0) - 32;
    if (v < 0 || v > 94) throw new BarcodeError(`Unsupported character “${c as string}”`);
    values.push(v);
    i++;
  }
  const check = ((values[0] ?? 0) + values.slice(1).reduce((a, v, k) => a + v * (k + 1), 0)) % 103;
  return [...values, check];
}

function valuesToBars(values: number[]): Bar[] {
  const seq = values.map((v) => C128[v]).join('') + STOP;
  return [{ w: QUIET, on: false }, ...widthsToBars(seq), { w: QUIET, on: false }];
}

/** Code 128 bars for a plain value (AfroTransact SKUs, ATINs, LPNs, bins). */
export function code128(text: string): Bar[] {
  return valuesToBars(code128Values([text]));
}

/** GS1 Application Identifiers with a fixed data length (no FNC1 needed after them). */
const FIXED_AI: Record<string, number> = { '00': 18, '01': 14, '02': 14, '03': 14, '04': 16, '11': 6, '12': 6, '13': 6, '15': 6, '16': 6, '17': 6, '20': 2, '31': 10, '32': 10, '33': 10, '34': 10, '35': 10, '36': 10, '41': 16 };

export interface Gs1Element {
  ai: string;
  value: string;
}

/** "(01)00860012000613(17)271001(10)B-77" → elements. */
export function parseGs1(text: string): Gs1Element[] {
  const out: Gs1Element[] = [];
  const re = /\((\d{2,4})\)([^(]*)/g;
  let m: RegExpExecArray | null;
  while ((m = re.exec(text)) !== null) out.push({ ai: m[1] ?? '', value: m[2] ?? '' });
  if (out.length === 0 || out.map((e) => `(${e.ai})${e.value}`).join('') !== text) throw new BarcodeError('Write GS1 data as (AI)value(AI)value…');
  for (const e of out) {
    const fixed = FIXED_AI[e.ai.slice(0, 2)];
    if (fixed !== undefined && e.value.length !== fixed) throw new BarcodeError(`AI (${e.ai}) needs ${fixed} characters`);
    if (fixed === undefined && (e.value.length === 0 || e.value.length > 30)) throw new BarcodeError(`AI (${e.ai}) needs 1 to 30 characters`);
  }
  return out;
}

/** Human-readable interpretation printed under a GS1-128 symbol: "(01) 0086… (17) 271001 (10) B-77". */
export function gs1Hri(elements: Gs1Element[]): string {
  return elements.map((e) => `(${e.ai}) ${e.value}`).join(' ');
}

/** GS1-128 symbol values: FNC1 start, the elements, FNC1 after each variable-length element that is not last. */
export function gs1Values(elements: Gs1Element[]): number[] {
  const seq: (string | typeof FNC1)[] = [FNC1];
  elements.forEach((e, k) => {
    seq.push(e.ai + e.value);
    if (FIXED_AI[e.ai.slice(0, 2)] === undefined && k < elements.length - 1) seq.push(FNC1);
  });
  return code128Values(seq);
}

export function gs1_128(text: string): Bar[] {
  return valuesToBars(gs1Values(parseGs1(text)));
}

/** GS1 mod-10 check digit of the digits before it (GTIN-8/12/13/14, SSCC, GLN). */
export function gs1CheckDigit(body: string): number {
  const d = body.replace(/\D/g, '').split('').reverse().map(Number);
  const sum = d.reduce((a, n, i) => a + n * (i % 2 === 0 ? 3 : 1), 0);
  return (10 - (sum % 10)) % 10;
}

export function gtinValid(code: string): boolean {
  const c = code.replace(/\D/g, '');
  if (![8, 12, 13, 14].includes(c.length) || c.length !== code.trim().length) return false;
  return gs1CheckDigit(c.slice(0, -1)) === Number(c.slice(-1));
}

/** GTIN-12/13 → GTIN-14 (packaging indicator 0), for AI (01). */
export function gtin14(gtin: string): string {
  const c = gtin.replace(/\D/g, '');
  if (!gtinValid(c)) throw new BarcodeError('Not a valid GTIN');
  return c.padStart(14, '0');
}

/** SSCC (AI 00): extension digit + company prefix + serial, 17 digits, plus the check digit. */
export function sscc(body17: string): string {
  if (!/^\d{17}$/.test(body17)) throw new BarcodeError('SSCC needs 17 digits before the check digit');
  return body17 + String(gs1CheckDigit(body17));
}

/** YYYY-MM-DD → YYMMDD (AIs 11, 15, 17). */
export function gs1Date(iso: string): string {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(iso)) throw new BarcodeError('Dates are YYYY-MM-DD');
  return iso.slice(2).replace(/-/g, '');
}

/**
 * The case label of a batch: GTIN (01), best before / expiry (17), made (11) and lot (10) last — the variable-length
 * lot needs no trailing FNC1.
 */
export function caseLabelData(opts: { gtin: string; lotNo?: string | null; expDate?: string | null; mfgDate?: string | null; count?: number | null }): string {
  let s = `(01)${gtin14(opts.gtin)}`;
  if (opts.expDate) s += `(17)${gs1Date(opts.expDate)}`;
  if (opts.mfgDate) s += `(11)${gs1Date(opts.mfgDate)}`;
  if (opts.count) s += `(30)${opts.count}`;
  if (opts.lotNo) s += `(10)${opts.lotNo}`;
  return s;
}

const L = ['0001101', '0011001', '0010011', '0111101', '0100011', '0110001', '0101111', '0111011', '0110111', '0001011'];
const G = ['0100111', '0110011', '0011011', '0100001', '0011101', '0111001', '0000101', '0010001', '0001001', '0010111'];
const R = L.map((p) => p.split('').map((b) => (b === '1' ? '0' : '1')).join(''));
const PARITY = ['LLLLLL', 'LLGLGG', 'LLGGLG', 'LLGGGL', 'LGLLGG', 'LGGLLG', 'LGGGLL', 'LGLGLG', 'LGLGGL', 'LGGLGL'];

/** EAN-13 (UPC-A as EAN-13 with a leading 0): 12 digits get their check digit, 13 must be valid. */
export function ean13(code: string): { digits: string; bars: Bar[] } {
  let c = code.replace(/\D/g, '');
  if (c.length === 12 && gtinValid(c)) c = `0${c}`;
  else if (c.length === 12) c += String(gs1CheckDigit(c));
  if (c.length !== 13 || !gtinValid(c)) throw new BarcodeError('EAN-13 needs 12 digits or 13 with a valid check digit');
  const first = Number(c[0]);
  let m = '101';
  for (let i = 1; i <= 6; i++) m += (PARITY[first]?.[i - 1] === 'L' ? L : G)[Number(c[i])];
  m += '01010';
  for (let i = 7; i <= 12; i++) m += R[Number(c[i])];
  m += '101';
  const bars: Bar[] = [];
  let run = 1;
  for (let i = 1; i <= m.length; i++) {
    if (m[i] === m[i - 1]) run++;
    else {
      bars.push({ w: run, on: m[i - 1] === '1' });
      run = 1;
    }
  }
  return { digits: c, bars: [{ w: 9, on: false }, ...bars, { w: 9, on: false }] };
}

export type Symbology = 'code128' | 'gs1-128' | 'ean13';

/** Bars and the text printed under them for any supported symbology. */
export function encode(symbology: Symbology, value: string): { bars: Bar[]; text: string } {
  switch (symbology) {
    case 'ean13': {
      const e = ean13(value);
      return { bars: e.bars, text: e.digits };
    }
    case 'gs1-128':
      return { bars: gs1_128(value), text: gs1Hri(parseGs1(value)) };
    default:
      return { bars: code128(value), text: value };
  }
}

/** Total width in modules. */
export function modules(bars: Bar[]): number {
  return bars.reduce((a, b) => a + b.w, 0);
}
