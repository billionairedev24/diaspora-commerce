import { describe, expect, it } from 'vitest';

import { BRAND_PALETTE, buttonInk, normaliseHex, swatchOf } from '../src';
import { AA_TEXT, contrastRatio } from '../src';

describe('brand palette', () => {
  it('has unique ids and lower-case #rrggbb colours', () => {
    expect(new Set(BRAND_PALETTE.map((s) => s.id)).size).toBe(BRAND_PALETTE.length);
    for (const s of BRAND_PALETTE) expect(s.hex).toMatch(/^#[0-9a-f]{6}$/);
  });

  it('gives AA contrast to the button text on every swatch, and AA against a white page', () => {
    for (const s of BRAND_PALETTE) {
      expect(buttonInk(s.hex).ratio, s.id).toBeGreaterThanOrEqual(AA_TEXT);
    }
    // Gold is the one light swatch: dark ink on it, and it is a button fill, not text on white.
    expect(buttonInk('#f5c542').ink).toBe('#111111');
    expect(contrastRatio('#1a7f4b', '#ffffff')).toBeGreaterThanOrEqual(AA_TEXT);
  });

  it('normalises any reasonable spelling to #rrggbb lower case', () => {
    expect(normaliseHex('#1A7F4B')).toBe('#1a7f4b');
    expect(normaliseHex('1a7f4b')).toBe('#1a7f4b');
    expect(normaliseHex(' #0F8 ')).toBe('#00ff88');
    expect(normaliseHex('fa0')).toBe('#ffaa00');
    for (const bad of ['green', '#12345', '#gggggg', '', '##123456', '#1234567', null, undefined]) expect(normaliseHex(bad)).toBeNull();
  });

  it('finds the swatch for a colour in any spelling', () => {
    expect(swatchOf('1A7F4B')?.id).toBe('green');
    expect(swatchOf('#123456')).toBeUndefined();
  });
});
