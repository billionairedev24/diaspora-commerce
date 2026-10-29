import { describe, expect, it } from 'vitest';

import { formatGiftCode, giftCodeComplete } from '@/components/credit/giftCode';

describe('gift card code entry', () => {
  it('groups what is typed in fours, upper-cased', () => {
    expect(formatGiftCode('abcd')).toBe('ABCD');
    expect(formatGiftCode('abcde')).toBe('ABCD-E');
    expect(formatGiftCode('abcdefghjklm2345')).toBe('ABCD-EFGH-JKLM-2345');
  });

  it('accepts a pasted code with spaces, dashes or stray punctuation, and stops at 16 characters', () => {
    expect(formatGiftCode(' abcd efgh  jklm-2345 ')).toBe('ABCD-EFGH-JKLM-2345');
    expect(formatGiftCode('ABCD-EFGH-JKLM-2345-9999')).toBe('ABCD-EFGH-JKLM-2345');
  });

  it('is complete only with 16 characters', () => {
    expect(giftCodeComplete('ABCD-EFGH-JKLM-234')).toBe(false);
    expect(giftCodeComplete('ABCD-EFGH-JKLM-2345')).toBe(true);
    expect(giftCodeComplete('abcdefghjklm2345')).toBe(true);
    expect(giftCodeComplete('')).toBe(false);
  });
});
