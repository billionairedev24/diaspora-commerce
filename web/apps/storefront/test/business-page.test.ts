import { describe, expect, it } from 'vitest';

import { textOnBrand } from '@/lib/brand';

describe('business page brand colour', () => {
  it('picks the text colour that contrasts more with the brand colour', () => {
    expect(textOnBrand('#ffffff')).toBe('dark');
    expect(textOnBrand('#fff3b0')).toBe('dark');
    expect(textOnBrand('#000000')).toBe('light');
    expect(textOnBrand('#8a3b12')).toBe('light');
    expect(textOnBrand('#1a7f4b')).toBe('light');
  });
});
