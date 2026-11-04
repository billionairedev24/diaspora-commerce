/* React Native view of the design tokens (additive; generated values come from src/generated/tokens.ts).
   React Native styles need unitless numbers and concrete colours, so this module converts the resolved CSS values:
   "16px" → 16, "rgb(0 0 0 / .55)" → "rgba(0,0,0,0.55)", "-0.015em" at a font size → letterSpacing in points,
   CSS box-shadows → iOS shadow* props + Android elevation. Import with `@afrotransact/tokens/native`. */
import { fonts } from './generated/fonts';
import { tokens } from './generated/tokens';

type Tree = typeof tokens;
type NumericGroup<G extends Record<string, string>> = { readonly [K in keyof G]: number };

/** "16px" → 16, "0" → 0, "1.45" → 1.45. Throws on values that are not plain numbers (clamp(), em…). */
export function toNumber(value: string): number {
  const m = /^(-?\d*\.?\d+)(px)?$/.exec(value.trim());
  if (!m) throw new Error(`Not a numeric token value: ${value}`);
  return Number(m[1]);
}

/** CSS Color 4 space syntax ("rgb(0 0 0 / .55)") → "rgba(0,0,0,0.55)"; hex and named colours pass through. */
export function toNativeColor(value: string): string {
  const m = /^rgba?\(\s*(\d+)\s+(\d+)\s+(\d+)\s*(?:\/\s*([\d.]+%?))?\s*\)$/.exec(value.trim());
  if (!m) return value;
  const alphaRaw = m[4] ?? '1';
  const alpha = alphaRaw.endsWith('%') ? Number(alphaRaw.slice(0, -1)) / 100 : Number(alphaRaw);
  return `rgba(${m[1]},${m[2]},${m[3]},${alpha})`;
}

function numeric<G extends Record<string, string>>(group: G): NumericGroup<G> {
  const out: Record<string, number> = {};
  for (const [k, v] of Object.entries(group)) out[k] = toNumber(v);
  return out as NumericGroup<G>;
}

function colours<G extends Record<string, string>>(group: G): { readonly [K in keyof G]: string } {
  const out: Record<string, string> = {};
  for (const [k, v] of Object.entries(group)) out[k] = toNativeColor(v);
  return out as { readonly [K in keyof G]: string };
}

export interface NativeShadow {
  shadowColor: string;
  shadowOffset: { width: number; height: number };
  shadowOpacity: number;
  shadowRadius: number;
  elevation: number;
}

/** "0 4px 14px rgb(25 24 22 / .12)" → RN shadow props (blur radius halved, as iOS shadowRadius is a radius). */
export function toNativeShadow(value: string): NativeShadow {
  const m = /^(-?\d+)(?:px)?\s+(-?\d+)(?:px)?\s+(\d+)(?:px)?\s+rgba?\(\s*(\d+)\s+(\d+)\s+(\d+)\s*\/\s*([\d.]+)\s*\)$/.exec(value.trim());
  if (!m) throw new Error(`Unsupported shadow token: ${value}`);
  const blur = Number(m[3]);
  return {
    shadowColor: `rgb(${m[4]},${m[5]},${m[6]})`,
    shadowOffset: { width: Number(m[1]), height: Number(m[2]) },
    shadowOpacity: Number(m[7]),
    shadowRadius: blur / 2,
    elevation: Math.max(1, Math.round(blur / 3))
  };
}

type FontWeight = '400' | '500' | '600' | '700' | '800';

const fontSize = numeric(tokens.text);

/** Letter spacing in points for an em-based tracking token at a font size ("-0.015em" at 20 → -0.3). */
export function tracking(name: keyof Tree['tracking'], size: number): number {
  const em = Number(tokens.tracking[name].replace('em', ''));
  return Math.round(em * size * 100) / 100;
}

export const nativeTokens = {
  gold: tokens.gold,
  green: tokens.green,
  ink: tokens.ink,
  red: tokens.red,
  sand: tokens.sand,
  brand: tokens.brand,
  /** Semantic colours (what components use). */
  color: colours(tokens.color),
  /** Font sizes in points. */
  fontSize,
  fontWeight: tokens.weight as { readonly [K in keyof Tree['weight']]: FontWeight },
  /** Unitless line-height multipliers; multiply by the font size for RN lineHeight. */
  leading: numeric(tokens.leading),
  space: numeric(tokens.space),
  size: numeric(tokens.size),
  radius: numeric(tokens.radius),
  shadow: {
    sm: toNativeShadow(tokens.shadow.sm),
    md: toNativeShadow(tokens.shadow.md),
    lg: toNativeShadow(tokens.shadow.lg)
  },
  /** Durations in milliseconds. */
  duration: { fast: toNumber(tokens.duration.fast.replace('ms', '')), base: toNumber(tokens.duration.value.replace('ms', '')) },
  fontFamily: { sans: fonts.sans.family, display: fonts.display.family, mono: 'Menlo' }
} as const;

export type NativeTokens = typeof nativeTokens;
