import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

import { describe, expect, it } from 'vitest';

// @ts-expect-error -- plain ESM script without type declarations
import { render } from '../scripts/generate.mjs';
import { cssVar, cssVariables, tokens } from '../src';

const css = readFileSync(resolve(__dirname, '../src/tokens.css'), 'utf8');
const design = readFileSync(resolve(__dirname, '../../../../design/afrotransact-tokens.css'), 'utf8');

describe('tokens', () => {
  it('generated TypeScript is up to date with tokens.css', () => {
    const current = readFileSync(resolve(__dirname, '../src/generated/tokens.ts'), 'utf8');
    expect(current).toBe(render(css));
  });

  it('keeps every variable of the design token file with the same value', () => {
    const declared = [...design.matchAll(/(--[a-z0-9-]+)\s*:\s*([^;]+);/gi)];
    expect(declared.length).toBeGreaterThan(100);
    for (const [, name, value] of declared) {
      const ours = (cssVariables as Record<string, string>)[name as string];
      expect(ours, name).toBeDefined();
      expect((ours ?? '').replace(/\s+/g, ''), name).toBe((value as string).replace(/\s*\/\*.*$/, '').trim().replace(/\s+/g, ''));
    }
  });

  it('resolves semantic tokens to primitives', () => {
    expect(tokens.color.canvas).toBe('#FFFFFF');
    expect(tokens.color.actionPrimary).toBe('#FFD400');
    expect(tokens.color.rating).toBe('#FFD400');
    expect(tokens.gold['500']).toBe('#FFD400');
  });

  it('includes the global rules: white canvas, Figtree, hidden scrollbars, no horizontal scroll', () => {
    expect(css).toContain('background: var(--at-color-canvas)');
    expect(css).toContain('font-family: var(--at-font-sans)');
    expect(css).toMatch(/scrollbar-width:\s*none/);
    expect(css).toMatch(/::-webkit-scrollbar\s*\{\s*display:\s*none/);
    expect(css).toMatch(/overflow-x:\s*hidden/);
    expect(cssVariables['--at-font-sans']).toMatch(/^"Figtree"/);
  });

  it('builds typed var() references', () => {
    expect(cssVar('--at-color-link')).toBe('var(--at-color-link)');
  });
});
