import { describe, expect, it } from 'vitest';

import { nativeTokens, toNativeColor, toNativeShadow, toNumber, tracking } from '../src/native';

describe('native tokens', () => {
  it('converts px and unitless values to numbers', () => {
    expect(toNumber('16px')).toBe(16);
    expect(toNumber('0')).toBe(0);
    expect(toNumber('1.45')).toBe(1.45);
    expect(() => toNumber('clamp(12px,2.4vw,32px)')).toThrow();
  });

  it('converts CSS Color 4 rgb() to rgba() and leaves hex alone', () => {
    expect(toNativeColor('rgb(0 0 0 / .55)')).toBe('rgba(0,0,0,0.55)');
    expect(toNativeColor('rgb(1 2 3 / 50%)')).toBe('rgba(1,2,3,0.5)');
    expect(toNativeColor(nativeTokens.color.canvas)).toBe(nativeTokens.color.canvas);
  });

  it('exposes numeric scales and semantic colours', () => {
    expect(nativeTokens.space['4']).toBe(16);
    expect(nativeTokens.radius.pill).toBe(999);
    expect(nativeTokens.fontSize.base).toBe(15);
    expect(nativeTokens.fontWeight.bold).toBe('700');
    expect(nativeTokens.color.overlay).toBe('rgba(0,0,0,0.55)');
    expect(nativeTokens.size.touch).toBe(44);
  });

  it('maps CSS shadows to React Native shadow props', () => {
    const s = toNativeShadow('0 4px 14px rgb(25 24 22 / .12)');
    expect(s.shadowOffset).toEqual({ width: 0, height: 4 });
    expect(s.shadowOpacity).toBe(0.12);
    expect(s.shadowRadius).toBe(7);
    expect(nativeTokens.shadow.lg.elevation).toBeGreaterThan(nativeTokens.shadow.sm.elevation);
  });

  it('computes letter spacing from em tracking', () => {
    expect(tracking('tight', 20)).toBe(-0.3);
  });
});
