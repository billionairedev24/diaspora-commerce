import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

import { describe, expect, it } from 'vitest';

import {
  accentIsSafe,
  contrastRatio,
  isHexColour,
  nearestSafeAccent,
  PRESETS,
  readableOn,
  resolveTheme,
  THEME_PRESETS,
  themeContrastReport,
  themeCssVars
} from '../src';

describe('storefront theme presets', () => {
  it('has the six curated looks', () => {
    expect([...THEME_PRESETS]).toEqual(['clean', 'warm-market', 'bold', 'midnight', 'heritage', 'fresh']);
  });

  it.each(THEME_PRESETS)('%s: every pair the page renders is WCAG AA with its own accent', (id) => {
    const t = resolveTheme({ preset: id });
    for (const row of themeContrastReport(t)) {
      expect(row.ratio, `${id}: ${row.pair}`).toBeGreaterThanOrEqual(row.min);
    }
  });

  it.each(THEME_PRESETS)('%s: the default accent is already safe (nothing is silently adjusted)', (id) => {
    expect(resolveTheme({ preset: id }).accent).toBe(PRESETS[id].accent);
  });

  it('only allows the two platform fonts', () => {
    for (const p of Object.values(PRESETS)) expect(['display', 'sans']).toContain(p.headingFont);
    const vars = themeCssVars(resolveTheme({ preset: 'bold' }));
    expect(vars['--sf-heading-font']).toBe('var(--at-font-sans)');
    expect(themeCssVars(resolveTheme({ preset: 'clean' }))['--sf-heading-font']).toBe('var(--at-font-display)');
  });

  it('keeps the canvas in step with the BFF (Go) so its accent check agrees', () => {
    const go = readFileSync(resolve(__dirname, '../../../../go/services/gateway-bff/internal/domain/storefront_config.go'), 'utf8');
    for (const [variable, field] of [['presetCanvas', 'canvas'], ['presetSurface', 'surface']] as const) {
      const block = new RegExp(`var ${variable} = map\\[string\\]string\\{([\\s\\S]*?)\\n\\}`).exec(go)?.[1] ?? '';
      const pairs = [...block.matchAll(/Preset(\w+):\s*"(#[0-9a-f]{6})"/g)].map((m) => [m[1]!.toLowerCase(), m[2]!]);
      expect(pairs.length, variable).toBe(6);
      for (const [name, colour] of pairs) {
        const id = THEME_PRESETS.find((p) => p.replace('-', '') === name);
        expect(id, name).toBeDefined();
        expect(PRESETS[id!][field], `${variable} ${name}`).toBe(colour);
      }
    }
  });
});

describe('contrast maths', () => {
  it('matches WCAG reference values', () => {
    expect(contrastRatio('#000000', '#ffffff')).toBeCloseTo(21, 1);
    expect(contrastRatio('#ffffff', '#ffffff')).toBe(1);
    expect(contrastRatio('#767676', '#ffffff')).toBeCloseTo(4.54, 1);
    expect(contrastRatio('#ffd400', '#ffffff')).toBeLessThan(1.5);
  });

  it('chooses the readable text colour for any accent', () => {
    expect(readableOn('#ffd400')).toBe('#111111');
    expect(readableOn('#0a6c52')).toBe('#ffffff');
    for (let i = 0; i < 360; i += 15) {
      const bg = nearestSafeAccent(`#${((i + 1) * 7919 % 0xffffff).toString(16).padStart(6, '0')}`, '#ffffff');
      expect(contrastRatio(readableOn(bg), bg)).toBeGreaterThanOrEqual(4.5);
    }
  });
});

describe('accent enforcement', () => {
  it('rejects unreadable accents and leaves safe ones alone', () => {
    expect(accentIsSafe('#ffd400', '#ffffff')).toBe(false);
    expect(accentIsSafe('#aa4f09', '#fffaf2', '#f7ecda')).toBe(true);
    expect(nearestSafeAccent('#1a7f4b', '#ffffff')).toBe('#1a7f4b');
  });

  it('turns any colour into an AA-safe one on light and dark canvases (the picker output)', () => {
    const samples = ['#ffd400', '#ffffff', '#00ffff', '#ff69b4', '#aaffaa', '#fafad2', '#123456', '#000000', '#7fff00'];
    for (const preset of Object.values(PRESETS)) {
      for (const s of samples) {
        const safe = nearestSafeAccent(s, preset.canvas, preset.surface);
        expect(isHexColour(safe)).toBe(true);
        expect(contrastRatio(safe, preset.canvas), `${s} on ${preset.id} canvas → ${safe}`).toBeGreaterThanOrEqual(4.5);
        expect(contrastRatio(safe, preset.surface), `${s} on ${preset.id} surface → ${safe}`).toBeGreaterThanOrEqual(4.5);
      }
    }
  });

  it('keeps the hue when it darkens', () => {
    const safe = nearestSafeAccent('#ffd400', '#ffffff');
    expect(safe).not.toBe('#ffd400');
    expect(safe.startsWith('#')).toBe(true);
  });

  it('resolveTheme: an unreadable accent or brand colour becomes safe, onAccent is computed', () => {
    const t = resolveTheme({ preset: 'clean', accent: '#ffd400' });
    expect(contrastRatio(t.accent, t.canvas)).toBeGreaterThanOrEqual(4.5);
    expect(contrastRatio(t.onAccent, t.accent)).toBeGreaterThanOrEqual(4.5);
    const fromBrand = resolveTheme({ preset: 'midnight', brandColour: '#1a2a4a' });
    expect(contrastRatio(fromBrand.accent, fromBrand.canvas)).toBeGreaterThanOrEqual(4.5);
    expect(resolveTheme({ preset: 'clean', accent: 'not-a-colour' }).accent).toBe(PRESETS.clean.accent);
  });

  it('chosen accent wins over brand colour, brand over preset', () => {
    expect(resolveTheme({ preset: 'clean', accent: '#1a7f4b', brandColour: '#0a0a99' }).accent).toBe('#1a7f4b');
    expect(resolveTheme({ preset: 'clean', brandColour: '#0a0a99' }).accent).toBe('#0a0a99');
  });
});

describe('css variables', () => {
  it('re-points the platform variables product cards and buttons read', () => {
    const v = themeCssVars(resolveTheme({ preset: 'midnight' }));
    expect(v['--at-color-canvas']).toBe('#0f1115');
    expect(v['--at-color-action-primary']).toBe(v['--sf-accent']);
    expect(v['--at-color-action-primary-text']).toBe(v['--sf-on-accent']);
    expect(Object.keys(v).every((k) => k.startsWith('--sf-') || k.startsWith('--at-'))).toBe(true);
  });
});
