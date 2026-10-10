import { type ChartSpec } from '@afrotransact/api-client';
import { resolvedVariables, type TokenName } from '@afrotransact/tokens';

export interface Tabular {
  title: string;
  headers: string[];
  rows: (string | number)[][];
}

/** A chart's rows as a table: first column the category, then one column per series. */
export function tabular(spec: ChartSpec): Tabular {
  return {
    title: spec.title,
    headers: [spec.categoryLabel, ...spec.series.map((s) => s.label)],
    rows: spec.rows.map((r) => [r.category, ...spec.series.map((s) => r.values[s.key] ?? 0)])
  };
}

export function slug(s: string): string {
  return s.replace(/[^a-z0-9]+/gi, '-').replace(/^-|-$/g, '').toLowerCase();
}

const TONE_TOKEN: Record<ChartSpec['series'][number]['tone'], TokenName> = {
  ink: '--at-ink-900',
  gold: '--at-gold-500',
  green: '--at-green-600',
  blue: '--at-ink-400',
  red: '--at-red-600',
  grey: '--at-ink-300'
};

/** Resolved colour (from the design tokens) of a chart tone, for SVG and native Excel charts. */
export function toneColour(tone: ChartSpec['series'][number]['tone']): string {
  return resolvedVariables[TONE_TOKEN[tone]];
}

/** Ordered palette for donut slices. */
export const SLICE_TOKENS: TokenName[] = ['--at-ink-900', '--at-gold-500', '--at-green-600', '--at-ink-400', '--at-red-600', '--at-ink-300'];
export const sliceColour = (i: number): string => resolvedVariables[SLICE_TOKENS[i % SLICE_TOKENS.length] ?? '--at-ink-900'];

/** Triggers a browser download. */
export function download(blob: Blob, fileName: string): void {
  const a = document.createElement('a');
  a.href = URL.createObjectURL(blob);
  a.download = fileName;
  document.body.appendChild(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(a.href), 4000);
}
