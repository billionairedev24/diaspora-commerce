/**
 * Seller storefront themes (/b/{slug}). A seller chooses one of six curated looks and, optionally, an accent colour.
 * Everything a look needs is a TOKEN SET here (palette, radius, type pairing, hero and grid defaults); the colour a seller
 * picks is DATA, applied through CSS variables after a WCAG AA check. Nothing here ever reaches a component as a raw colour:
 * components read `var(--sf-*)`.
 *
 * The canvas and surface of each preset are mirrored in go/services/gateway-bff/internal/domain/storefront_config.go (the BFF
 * rejects an accent that fails AA on either); test/storefront-theme.test.ts fails when the two drift apart.
 */

export const THEME_PRESETS = ['clean', 'warm-market', 'bold', 'midnight', 'heritage', 'fresh'] as const;
export type ThemePreset = (typeof THEME_PRESETS)[number];

export type HeroStyle = 'image' | 'colour' | 'split';
export type GridColumns = 3 | 4;
/** The two families the platform allows (fonts.config.json): headings are Fraunces ("display") or Inter ("sans"). */
export type HeadingFont = 'display' | 'sans';

export interface PresetDefinition {
  id: ThemePreset;
  /** Shown in the editor. */
  name: string;
  description: string;
  tone: 'light' | 'dark';
  canvas: string;
  /** Raised areas: quick view, inputs, the footer band. */
  surface: string;
  text: string;
  muted: string;
  border: string;
  /** The accent when the seller picks none. */
  accent: string;
  /** Corner radius in px for buttons, inputs and images. */
  radius: number;
  headingFont: HeadingFont;
  /** Heading weight and tracking: the "type pairing" besides the family. */
  headingWeight: 500 | 600 | 700;
  headingTracking: string;
  /** Suggested layout (the seller can change both). */
  heroStyle: HeroStyle;
  gridColumns: GridColumns;
  /** Image aspect ratio of a product tile (w / h). */
  tileRatio: string;
}

export const PRESETS: Readonly<Record<ThemePreset, PresetDefinition>> = {
  clean: {
    id: 'clean', name: 'Clean', description: 'White, quiet and precise. Lets the photos lead.', tone: 'light',
    canvas: '#ffffff', surface: '#f6f4ef', text: '#191816', muted: '#5d5951', border: '#e0dcd2', accent: '#0a6c52',
    radius: 8, headingFont: 'display', headingWeight: 500, headingTracking: '-0.015em', heroStyle: 'image', gridColumns: 4, tileRatio: '4 / 5'
  },
  'warm-market': {
    id: 'warm-market', name: 'Warm market', description: 'Sun-warmed paper and terracotta. Friendly, like a market stall.', tone: 'light',
    canvas: '#fffaf2', surface: '#f7ecda', text: '#2b1d12', muted: '#6b5646', border: '#eadfcb', accent: '#aa4f09',
    radius: 14, headingFont: 'display', headingWeight: 600, headingTracking: '-0.01em', heroStyle: 'split', gridColumns: 3, tileRatio: '1 / 1'
  },
  bold: {
    id: 'bold', name: 'Bold', description: 'High contrast, sharp corners and big type. For loud, confident brands.', tone: 'light',
    canvas: '#ffffff', surface: '#f0f0f0', text: '#0b0b0b', muted: '#474747', border: '#0b0b0b', accent: '#c4161c',
    radius: 2, headingFont: 'sans', headingWeight: 700, headingTracking: '-0.03em', heroStyle: 'colour', gridColumns: 4, tileRatio: '1 / 1'
  },
  midnight: {
    id: 'midnight', name: 'Midnight', description: 'Dark canvas, gold accents. Makes jewellery, beauty and tech glow.', tone: 'dark',
    canvas: '#0f1115', surface: '#1a1e26', text: '#f3f4f6', muted: '#a8aebb', border: '#2c323d', accent: '#f5c542',
    radius: 10, headingFont: 'display', headingWeight: 500, headingTracking: '-0.01em', heroStyle: 'image', gridColumns: 3, tileRatio: '4 / 5'
  },
  heritage: {
    id: 'heritage', name: 'Heritage', description: 'Cream, deep red-brown and serif type. Crafted, rooted, timeless.', tone: 'light',
    canvas: '#fbf7ef', surface: '#f1e8d6', text: '#2a2118', muted: '#66584a', border: '#e3d7bf', accent: '#7c2d12',
    radius: 4, headingFont: 'display', headingWeight: 600, headingTracking: '0', heroStyle: 'split', gridColumns: 3, tileRatio: '3 / 4'
  },
  fresh: {
    id: 'fresh', name: 'Fresh', description: 'Airy mint and teal with rounded shapes. Light, modern, easy.', tone: 'light',
    canvas: '#f6faf7', surface: '#e6f1ea', text: '#14231b', muted: '#4d6256', border: '#d5e4da', accent: '#0f766e',
    radius: 16, headingFont: 'sans', headingWeight: 600, headingTracking: '-0.02em', heroStyle: 'colour', gridColumns: 4, tileRatio: '1 / 1'
  }
};

export const isPreset = (v: unknown): v is ThemePreset => typeof v === 'string' && (THEME_PRESETS as readonly string[]).includes(v);

/* ── colour maths (WCAG 2.x) ── */

const HEX = /^#[0-9a-f]{6}$/;
export const isHexColour = (v: unknown): v is string => typeof v === 'string' && HEX.test(v);

function channels(hex: string): [number, number, number] {
  return [parseInt(hex.slice(1, 3), 16), parseInt(hex.slice(3, 5), 16), parseInt(hex.slice(5, 7), 16)];
}

const toHex = (r: number, g: number, b: number): string =>
  '#' + [r, g, b].map((v) => Math.max(0, Math.min(255, Math.round(v))).toString(16).padStart(2, '0')).join('');

export function luminance(hex: string): number {
  const lin = channels(hex).map((v) => {
    const c = v / 255;
    return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
  }) as [number, number, number];
  return 0.2126 * lin[0] + 0.7152 * lin[1] + 0.0722 * lin[2];
}

/** WCAG contrast ratio of two #rrggbb colours, 1 to 21. */
export function contrastRatio(a: string, b: string): number {
  const la = luminance(a);
  const lb = luminance(b);
  return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
}

export const AA_TEXT = 4.5;

/** Whichever of the two inks reads better on `bg` (never a chosen colour: computed). */
export function readableOn(bg: string, light = '#ffffff', dark = '#111111'): string {
  return contrastRatio(bg, light) >= contrastRatio(bg, dark) ? light : dark;
}

/** Can the accent be used as text or a link on every background it sits on (canvas, and the raised surface) at AA? The BFF enforces the same rule. */
export function accentIsSafe(accent: string, ...backgrounds: string[]): boolean {
  return isHexColour(accent) && backgrounds.length > 0 && backgrounds.every((bg) => contrastRatio(accent, bg) >= AA_TEXT);
}

function toHsl(hex: string): [number, number, number] {
  const [r, g, b] = channels(hex).map((v) => v / 255) as [number, number, number];
  const max = Math.max(r, g, b);
  const min = Math.min(r, g, b);
  const l = (max + min) / 2;
  const d = max - min;
  if (d === 0) return [0, 0, l];
  const s = d / (1 - Math.abs(2 * l - 1));
  const h = max === r ? ((g - b) / d + (g < b ? 6 : 0)) : max === g ? (b - r) / d + 2 : (r - g) / d + 4;
  return [h * 60, s, l];
}

function fromHsl(h: number, s: number, l: number): string {
  const c = (1 - Math.abs(2 * l - 1)) * s;
  const x = c * (1 - Math.abs(((h / 60) % 2) - 1));
  const m = l - c / 2;
  const [r, g, b] = h < 60 ? [c, x, 0] : h < 120 ? [x, c, 0] : h < 180 ? [0, c, x] : h < 240 ? [0, x, c] : h < 300 ? [x, 0, c] : [c, 0, x];
  return toHex((r + m) * 255, (g + m) * 255, (b + m) * 255);
}

/**
 * The closest colour to `hex` (same hue and saturation) that passes AA on the canvas: darker on a light canvas, lighter on
 * a dark one. Returns `hex` itself when it already passes. This is what the colour picker outputs, so a seller can never
 * save an unreadable accent.
 */
export function nearestSafeAccent(hex: string, ...backgrounds: string[]): string {
  const colour = hex.toLowerCase();
  const canvas = backgrounds[0] ?? '#ffffff';
  if (!isHexColour(colour)) return colour;
  if (accentIsSafe(colour, ...backgrounds)) return colour;
  const [h, s, l0] = toHsl(colour);
  const darker = luminance(canvas) > 0.4;
  for (let i = 1; i <= 100; i++) {
    const l = darker ? l0 - i * 0.01 : l0 + i * 0.01;
    if (l < 0 || l > 1) break;
    const candidate = fromHsl(h, s, l);
    if (accentIsSafe(candidate, ...backgrounds)) return candidate;
  }
  return darker ? '#000000' : '#ffffff';
}

/* ── resolved theme ── */

export interface ThemeInput {
  preset: ThemePreset;
  accent?: string | null;
  /** The brand colour from the business profile: the accent when none is chosen (made AA-safe). */
  brandColour?: string | null;
}

export interface ResolvedTheme {
  preset: ThemePreset;
  tone: 'light' | 'dark';
  canvas: string;
  surface: string;
  text: string;
  muted: string;
  border: string;
  /** Used for links, prices and rules: AA on the canvas. */
  accent: string;
  /** Text on an accent-filled button or hero: computed. */
  onAccent: string;
  /** A hover/pressed shade of the accent that keeps `onAccent` readable. */
  accentStrong: string;
  radius: number;
  headingFont: HeadingFont;
  headingWeight: number;
  headingTracking: string;
  heroStyle: HeroStyle;
  gridColumns: GridColumns;
  tileRatio: string;
}

export function resolveTheme(input: ThemeInput): ResolvedTheme {
  const p = PRESETS[isPreset(input.preset) ? input.preset : 'clean'];
  const wanted = [input.accent, input.brandColour].find((c): c is string => isHexColour(c)) ?? p.accent;
  const accent = nearestSafeAccent(wanted, p.canvas, p.surface);
  const onAccent = readableOn(accent, '#ffffff', '#111111');
  const [h, s, l] = toHsl(accent);
  const accentStrong = fromHsl(h, s, p.tone === 'dark' ? Math.min(1, l + 0.08) : Math.max(0, l - 0.08));
  return {
    preset: p.id, tone: p.tone, canvas: p.canvas, surface: p.surface, text: p.text, muted: p.muted, border: p.border, accent, onAccent, accentStrong,
    radius: p.radius, headingFont: p.headingFont, headingWeight: p.headingWeight, headingTracking: p.headingTracking,
    heroStyle: p.heroStyle, gridColumns: p.gridColumns, tileRatio: p.tileRatio
  };
}

/**
 * CSS custom properties for the storefront root element. Besides the `--sf-*` set the storefront uses, the platform
 * variables product cards, buttons and prices read (`--at-color-*`) are re-pointed, so shared components re-theme
 * without knowing about sellers.
 */
export function themeCssVars(t: ResolvedTheme): Record<string, string> {
  return {
    '--sf-canvas': t.canvas,
    '--sf-surface': t.surface,
    '--sf-text': t.text,
    '--sf-muted': t.muted,
    '--sf-border': t.border,
    '--sf-accent': t.accent,
    '--sf-accent-strong': t.accentStrong,
    '--sf-on-accent': t.onAccent,
    '--sf-radius': `${t.radius}px`,
    '--sf-heading-font': t.headingFont === 'display' ? 'var(--at-font-display)' : 'var(--at-font-sans)',
    '--sf-heading-weight': String(t.headingWeight),
    '--sf-heading-tracking': t.headingTracking,
    '--sf-tile-ratio': t.tileRatio,
    '--at-color-canvas': t.canvas,
    '--at-color-surface': t.canvas,
    '--at-color-surface-muted': t.surface,
    '--at-color-surface-sunken': t.surface,
    '--at-color-surface-hover': t.surface,
    '--at-color-text': t.text,
    '--at-color-text-muted': t.muted,
    '--at-color-text-subtle': t.muted,
    '--at-color-border': t.border,
    '--at-color-border-strong': t.muted,
    '--at-color-action-primary': t.accent,
    '--at-color-action-primary-hover': t.accentStrong,
    '--at-color-action-primary-press': t.accentStrong,
    '--at-color-action-primary-text': t.onAccent,
    '--at-color-action-secondary': t.text,
    '--at-color-action-secondary-hover': t.muted,
    '--at-color-action-secondary-text': t.canvas,
    '--at-color-link': t.accent,
    '--at-color-link-hover': t.accentStrong,
    '--at-color-focus': t.accent,
    '--at-color-price': t.text,
    '--at-radius-sm': `${Math.max(2, t.radius - 2)}px`,
    '--at-radius-md': `${t.radius}px`,
    '--at-radius-lg': `${t.radius + 4}px`
  };
}

/** Every combination the storefront renders as text, with the pair's minimum ratio (used by the contrast test and the editor's check). */
export function themeContrastReport(t: ResolvedTheme): { pair: string; ratio: number; min: number }[] {
  return [
    { pair: 'text on canvas', ratio: contrastRatio(t.text, t.canvas), min: AA_TEXT },
    { pair: 'muted on canvas', ratio: contrastRatio(t.muted, t.canvas), min: AA_TEXT },
    { pair: 'muted on surface', ratio: contrastRatio(t.muted, t.surface), min: AA_TEXT },
    { pair: 'text on surface', ratio: contrastRatio(t.text, t.surface), min: AA_TEXT },
    { pair: 'accent on canvas', ratio: contrastRatio(t.accent, t.canvas), min: AA_TEXT },
    { pair: 'accent on surface', ratio: contrastRatio(t.accent, t.surface), min: AA_TEXT },
    { pair: 'on-accent on accent', ratio: contrastRatio(t.onAccent, t.accent), min: AA_TEXT },
    { pair: 'on-accent on accent-strong', ratio: contrastRatio(t.onAccent, t.accentStrong), min: AA_TEXT }
  ];
}
