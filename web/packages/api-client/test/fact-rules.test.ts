import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

import { describe, expect, it } from 'vitest';

import { type AttributeDef, categoryClosed, checkFacts, evaluateFact, type FactExpression, missingMessage } from '../src';
import { TAX_PRODUCT_TYPES, TAX_TREE } from '../src/mocks/seller/taxonomy';

const vectors = JSON.parse(readFileSync(resolve(__dirname, '../../../../docs/engineering/fact-expression-vectors.json'), 'utf8')) as {
  vectors: { name: string; expr: FactExpression | null; facts: Record<string, never>; expect: boolean }[];
};

const q = (key: string, kind: AttributeDef['kind'], extra: Partial<AttributeDef> = {}): AttributeDef => ({
  key, label: key, options: [], required: true, multiline: false, kind, units: [], section: 'TAX', help: null, taxFact: true, owner: 'VARIANT', evidence: false, optionLabels: {}, ...extra
});

describe('fact expressions (shared vectors with the catalog)', () => {
  it.each(vectors.vectors.map((v) => [v.name, v] as const))('%s', (_n, v) => {
    expect(evaluateFact(v.expr, v.facts)).toBe(v.expect);
  });
});

describe('checkFacts', () => {
  const notCandy: FactExpression = { op: 'isFalse', fact: 'is_candy' };
  const defs = [q('is_candy', 'BOOLEAN'), q('net_weight_oz', 'DECIMAL', { unit: 'oz', label: 'Net weight (oz)', help: 'On the label.', requiredWhen: notCandy, showWhen: notCandy }),
    q('servings', 'INTEGER', { requiredWhen: notCandy, showWhen: notCandy }), q('juice', 'PERCENT', { required: false })];

  it('types the answers and drops hidden ones', () => {
    const r = checkFacts(defs, { is_candy: 'yes', net_weight_oz: '8', servings: '2' });
    expect(r.facts).toEqual({ is_candy: true });
    expect(r.missing).toEqual([]);
  });
  it('lists missing questions in order with the catalog wording', () => {
    const r = checkFacts(defs, { is_candy: 'no' });
    expect(r.missing).toEqual(['net_weight_oz', 'servings']);
    expect(missingMessage(defs[1] as AttributeDef)).toBe('Net weight (oz) is needed. On the label.');
  });
  it('refuses wrong types and ranges', () => {
    const r = checkFacts(defs, { is_candy: 'no', net_weight_oz: '0', servings: '1.5', juice: '120' });
    expect(r.problems).toEqual({ net_weight_oz: 'number', servings: 'whole', juice: 'percent' });
  });
});

describe('the mock category tax tree', () => {
  it('has 13 departments, 74 categories and gated statuses', () => {
    expect(TAX_TREE).toHaveLength(13);
    const cats = TAX_TREE.flatMap((d) => d.children);
    expect(cats).toHaveLength(74);
    expect(cats.filter((c) => c.taxStatus === 'ACTIVE')).toHaveLength(57);
    expect(categoryClosed(cats.find((c) => c.name === 'Gift baskets / bundles')?.taxStatus)).toBe(true);
    expect(cats.find((c) => c.name === 'Fabrics & textiles')?.taxStatus).toBe('NEEDS_APPROVAL');
  });
  it('gives every category its tax questions, never the staff or system ones', () => {
    const snacks = TAX_PRODUCT_TYPES['packaged-snacks'];
    expect(snacks?.attributes.filter((a) => a.taxFact).map((a) => a.key)).toEqual(['is_candy', 'net_weight_oz', 'servings_per_package', 'individual_serving']);
    const all = Object.values(TAX_PRODUCT_TYPES).flatMap((t) => t.attributes.map((a) => a.key));
    expect(all).not.toContain('exemption_confirmed');
    expect(all).not.toContain('gift_card_event');
    expect(TAX_PRODUCT_TYPES['bakery-products']?.attributes.find((a) => a.key === 'heated_by_seller')?.owner).toBe('OFFER');
  });
});
