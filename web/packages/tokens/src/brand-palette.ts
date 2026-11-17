/**
 * The curated brand colours a seller or service provider can pick for their public page and buttons. Sellers do not know
 * hex codes, so the picker offers these named swatches first (and a "Custom" option for people who do). Every swatch
 * carries AA contrast (4.5:1) against white or near-black text, whichever `readableOn` chooses, and against a white page,
 * so a button or link in it is always legible. Components read this list; they never write a colour of their own.
 */
import { contrastRatio, isHexColour, readableOn } from './storefront-theme';

export interface BrandSwatch {
  /** Stable id; the picker's translated name is looked up by it. */
  id: string;
  /** English name (the fallback of the picker's label). */
  name: string;
  /** #rrggbb, lower case. */
  hex: string;
}

export const BRAND_PALETTE: readonly BrandSwatch[] = [
  { id: 'green', name: 'Green', hex: '#1a7f4b' },
  { id: 'teal', name: 'Teal', hex: '#0f766e' },
  { id: 'blue', name: 'Blue', hex: '#1d4ed8' },
  { id: 'indigo', name: 'Indigo', hex: '#4338ca' },
  { id: 'purple', name: 'Purple', hex: '#7e22ce' },
  { id: 'pink', name: 'Pink', hex: '#be185d' },
  { id: 'red', name: 'Red', hex: '#c4161c' },
  { id: 'orange', name: 'Orange', hex: '#c2410c' },
  { id: 'brown', name: 'Brown', hex: '#7c2d12' },
  { id: 'gold', name: 'Gold', hex: '#f5c542' },
  { id: 'slate', name: 'Slate', hex: '#334155' },
  { id: 'black', name: 'Black', hex: '#111111' }
] as const;

/** Where the native colour picker starts when no colour is chosen yet (it is never saved until the person picks). */
export const FALLBACK_BRAND_HEX = '#1a7f4b';

/**
 * Any reasonable spelling of a colour as #rrggbb in lower case: "#1A7F4B", "1a7f4b", "#0f8", "0F8". Null when it is
 * none of those (names such as "green", wrong lengths, non-hex digits). The seller service applies the same rule.
 */
export function normaliseHex(input: string | null | undefined): string | null {
  const raw = (input ?? '').trim().replace(/^#/, '');
  if (!/^([0-9a-fA-F]{3}|[0-9a-fA-F]{6})$/.test(raw)) return null;
  const full = raw.length === 3 ? [...raw].map((c) => c + c).join('') : raw;
  const hex = `#${full.toLowerCase()}`;
  return isHexColour(hex) ? hex : null;
}

/** The swatch whose colour is `hex` (any spelling), if there is one. */
export function swatchOf(hex: string | null | undefined): BrandSwatch | undefined {
  const n = normaliseHex(hex);
  return n ? BRAND_PALETTE.find((s) => s.hex === n) : undefined;
}

/** Text colour for a button filled with `bg`, and the contrast it gets. */
export function buttonInk(bg: string): { ink: string; ratio: number } {
  const ink = readableOn(bg);
  return { ink, ratio: contrastRatio(bg, ink) };
}
