import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

import { describe, expect, it } from 'vitest';

// @ts-expect-error -- plain ESM script without type declarations
import { fontDeclarations, render, renderFontsCss, renderFontsTs, withFonts } from '../scripts/generate.mjs';
import { cssVar, cssVariables, fonts, resolvedVariables, tokens } from '../src';
import fontsConfig from '../src/fonts.config.json';

const css = readFileSync(resolve(__dirname, '../src/tokens.css'), 'utf8');
const design = readFileSync(resolve(__dirname, '../../../../design/afrotransact-tokens.css'), 'utf8');

/*
 * Deliberate deviations from the design token file, each for WCAG 2.2 AA (S194). The design values fail contrast:
 * ink-500 is 4.45:1 on white (needs 4.5:1) and gold-500 text is 1.4:1. Keep this list short and justified.
 */
const A11Y_OVERRIDES: Record<string, { design: string; ours: string; why: string }> = {
  '--at-color-text-subtle': { design: 'var(--at-ink-500)', ours: 'var(--at-ink-550)', why: '1.4.3 contrast: 4.45:1 → 5.9:1 on white' },
  '--at-color-rating-text': { design: 'var(--at-gold-500)', ours: 'var(--at-gold-800)', why: '1.4.3 contrast: 1.4:1 → 5.8:1 on white' }
};

/* The fonts are set in src/fonts.config.json, not in the design token file: the legacy production app's Inter (body and UI)
   and Fraunces (display). The prototype's Figtree is deliberately gone. */
const FONT_VARIABLES = ['--at-font-sans', '--at-font-display', '--at-font-mono'];

describe('fonts: one place', () => {
  it('the generated files match fonts.config.json', () => {
    expect(readFileSync(resolve(__dirname, '../src/fonts.css'), 'utf8')).toBe(renderFontsCss(fontsConfig));
    expect(readFileSync(resolve(__dirname, '../src/generated/fonts.ts'), 'utf8')).toBe(renderFontsTs(fontsConfig));
    expect(withFonts(css, fontsConfig)).toBe(css);
  });

  it('is Inter for the UI and Fraunces for headings, with the legacy fallback stacks', () => {
    expect(cssVariables['--at-font-sans']).toBe('"Inter", ui-sans-serif, system-ui, -apple-system, "Segoe UI", Roboto, sans-serif');
    expect(cssVariables['--at-font-display']).toBe('"Fraunces", Georgia, "Times New Roman", serif');
    expect(fontDeclarations(fontsConfig)).toContain(cssVariables['--at-font-sans']);
    expect(fonts.sans.family).toBe('Inter');
    expect(fonts.display.family).toBe('Fraunces');
    expect(fonts.googleFontsUrl).toContain('family=Inter');
    expect(fonts.googleFontsUrl).toContain('family=Fraunces');
  });

  it('only headings and the display class use the display face', () => {
    expect(css).toMatch(/h1, h2, h3, \.at-display \{ font-family: var\(--at-font-display\); \}/);
  });
});

describe('tokens', () => {
  it('generated TypeScript is up to date with tokens.css', () => {
    const current = readFileSync(resolve(__dirname, '../src/generated/tokens.ts'), 'utf8');
    expect(current).toBe(render(css));
  });

  it('keeps every variable of the design token file with the same value', () => {
    const declared = [...design.matchAll(/(--[a-z0-9-]+)\s*:\s*([^;]+);/gi)];
    expect(declared.length).toBeGreaterThan(100);
    for (const [, name, value] of declared) {
      if (FONT_VARIABLES.includes(name as string)) continue; // set in fonts.config.json
      const ours = (cssVariables as Record<string, string>)[name as string];
      expect(ours, name).toBeDefined();
      const designValue = (value as string).replace(/\s*\/\*.*$/, '').trim().replace(/\s+/g, '');
      const override = A11Y_OVERRIDES[name as string];
      if (override) {
        expect(designValue, `${name}: the design changed; revisit the a11y override`).toBe(override.design);
        expect(ours, name).toBe(override.ours);
        continue;
      }
      expect((ours ?? '').replace(/\s+/g, ''), name).toBe(designValue);
    }
  });

  it('resolves semantic tokens to primitives', () => {
    expect(tokens.color.canvas).toBe('#FFFFFF');
    expect(tokens.color.actionPrimary).toBe('#FFD400');
    expect(tokens.color.rating).toBe('#FFD400');
    expect(tokens.gold['500']).toBe('#FFD400');
  });

  it('includes the global rules: white canvas, Inter, hidden scrollbars, no horizontal scroll', () => {
    expect(css).toContain('background: var(--at-color-canvas)');
    expect(css).toContain('font-family: var(--at-font-sans)');
    expect(css).toMatch(/scrollbar-width:\s*none/);
    expect(css).toMatch(/::-webkit-scrollbar\s*\{\s*display:\s*none/);
    // clip, not hidden: hidden turns <body> into a scroll container and breaks position: sticky headers.
    expect(css).toMatch(/html, body \{ overflow-x:\s*clip; \}/);
    expect(cssVariables['--at-font-sans']).toMatch(/^"Inter"/);
  });

  it('builds typed var() references', () => {
    expect(cssVar('--at-color-link')).toBe('var(--at-color-link)');
  });
});

describe('alert tokens: WCAG AA on the tint (light theme)', () => {
  const channel = (hex: string, i: number): number => {
    const v = parseInt(hex.slice(1 + i * 2, 3 + i * 2), 16) / 255;
    return v <= 0.03928 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4;
  };
  const luminance = (hex: string): number => 0.2126 * channel(hex, 0) + 0.7152 * channel(hex, 1) + 0.0722 * channel(hex, 2);
  const contrast = (a: string, b: string): number => {
    const [hi, lo] = [luminance(a), luminance(b)].sort((x, y) => y - x) as [number, number];
    return (hi + 0.05) / (lo + 0.05);
  };
  const resolved = resolvedVariables as Record<string, string>;

  it.each(['info', 'success', 'warning', 'danger', 'neutral'])('%s: text 4.5:1 and icon 3:1 on its tint', (variant) => {
    const bg = resolved[`--at-color-alert-${variant}-bg`] as string;
    expect(contrast(resolved[`--at-color-alert-${variant}-text`] as string, bg)).toBeGreaterThanOrEqual(4.5);
    expect(contrast(resolved[`--at-color-alert-${variant}-icon`] as string, bg)).toBeGreaterThanOrEqual(3);
    for (const part of ['border', 'accent']) expect(resolved[`--at-color-alert-${variant}-${part}`], part).toMatch(/^#[0-9A-F]{6}$/i);
  });
});
