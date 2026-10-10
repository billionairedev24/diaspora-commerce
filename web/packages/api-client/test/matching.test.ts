import { describe, expect, it } from 'vitest';

import { atinOf, completePhrases, levenshtein, matchScore, PRODUCTS, suggest } from '../src/mocks';
import { getDb } from '../src/mocks/db';

const product = (id: string) => {
  const p = PRODUCTS.find((x) => x.id === id);
  if (!p) throw new Error(id);
  return p;
};
const hou = { zip: '77042', city: 'Houston', state: 'TX', label: 'Houston 77042', serviceable: true };
const now = new Date('2026-10-09T10:00:00');

describe('search token matching (mock search service)', () => {
  it('requires every typed word to match a product word', () => {
    expect(matchScore(product('p2'), 'jollof seasoning')).toBeGreaterThan(0);
    expect(matchScore(product('p2'), 'jollof rice')).toBe(0);
  });

  it('matches partial words as prefixes', () => {
    expect(matchScore(product('p2'), 'jol')).toBeGreaterThan(0);
    expect(matchScore(product('p11'), 'basm')).toBeGreaterThan(0);
  });

  it('tolerates typos (1 edit for 4–5 letters, 2 for 6+)', () => {
    expect(matchScore(product('p11'), 'basmti rice')).toBeGreaterThan(0);
    expect(matchScore(product('p2'), 'jolof')).toBeGreaterThan(0);
    expect(matchScore(product('p4'), 'berbre')).toBeGreaterThan(0);
    expect(matchScore(product('p4'), 'xyzzy')).toBe(0);
  });

  it('understands diaspora synonyms (hibiscus/bissap/zobo, gari/garri)', () => {
    expect(matchScore(product('p8'), 'zobo')).toBeGreaterThan(0);
    expect(matchScore(product('p8'), 'sorrel')).toBeGreaterThan(0);
  });

  it('ranks name matches above brand/origin-only matches', () => {
    expect(matchScore(product('p2'), 'jollof')).toBeGreaterThan(matchScore(product('p16'), 'west'));
  });

  it('computes edit distance', () => {
    expect(levenshtein('kitten', 'sitting')).toBe(3);
    expect(levenshtein('', 'abc')).toBe(3);
  });

  it('completes phrases from what was typed', () => {
    const phrases = completePhrases('jol');
    expect(phrases.length).toBeGreaterThan(0);
    expect(phrases.every((p) => p.startsWith('jol'))).toBe(true);
    expect(completePhrases('jollof sea')).toContain('jollof seasoning');
  });

  it('typeahead for "jol" returns phrases (first scoped to a department) and up to 5 products', () => {
    const s = suggest('jol', hou, now);
    expect(s.phrases[0]).toEqual({ text: expect.stringMatching(/^jol/), department: 'Groceries' });
    expect(s.products.length).toBeGreaterThan(0);
    expect(s.products.length).toBeLessThanOrEqual(5);
    expect(s.products[0]?.title).toMatch(/Jollof/);
  });

  it('every fixture product has a valid ATIN and an offer', () => {
    const db = getDb();
    for (const p of PRODUCTS) {
      expect(atinOf(p.id)).toMatch(/^AT[0-9A-Z]{8}$/);
      expect(db.offersByAtin.get(atinOf(p.id))?.length).toBeGreaterThan(0);
    }
  });
});
