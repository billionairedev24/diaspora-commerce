import { describe, expect, it } from 'vitest';

import { EN_MESSAGES } from '@/i18n/t';

/** Seller Center English copy: no "offer", no warehouse or stock-sent-to-us wording, no carrier names, and never "Shipping charge (USD, optional)". */
describe('Seller Center English copy', () => {
  const entries = Object.entries(EN_MESSAGES).filter(([k]) => !k.startsWith('$'));
  it('never uses the word offer', () => {
    expect(entries.filter(([, v]) => /\boffer(s|ed|ing)?\b/i.test(String(v))).map(([k]) => k)).toEqual([]);
  });
  it('has no fulfilment-centre or send-us-your-stock wording and no per-unit FBA fee promise', () => {
    expect(entries.filter(([, v]) => /fulfil+ment cent(re|er)|send (your )?stock|per unit/i.test(String(v)) && !/earlier/i.test(String(v))).map(([k]) => k)).toEqual([]);
  });
  it('never names a carrier', () => {
    expect(entries.filter(([, v]) => /\b(usps|ups|fedex|dhl)\b/i.test(String(v))).map(([k]) => k)).toEqual([]);
  });
  it('has no optional shipping charge field label', () => {
    expect(entries.filter(([, v]) => /Shipping charge \(USD, optional\)/.test(String(v)))).toEqual([]);
  });
});
