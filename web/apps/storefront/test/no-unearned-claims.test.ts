/* Guard: copy that claims social proof or urgency we have no data for must not come back. Each phrase below needs
   real data behind it (co-purchase counts, sales ranks, live viewers) before it may be written. */
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

import { describe, expect, it } from 'vitest';

const BANNED: [string, RegExp][] = [
  ['co-purchase claim', /customers? (who|that) (bought|purchased)|(also|people) bought|bought (this )?together|frequently bought|customers also (viewed|bought)|people also (viewed|bought)/i],
  ['sales rank claim', /best ?sellers?|top sellers?|bestselling|best-selling/i],
  ['unmeasured popularity', /trending|popular|most loved|fan favou?rite|going fast|selling fast/i],
  ['sales volume', /\d+\+? (bought|sold) in/i],
  ['live viewers', /people (are )?(viewing|watching|looking at)/i],
  ['made-up demand', /customers near you are looking/i]
];

function catalog(rel: string): Record<string, unknown> {
  return JSON.parse(readFileSync(fileURLToPath(new URL(rel, import.meta.url)), 'utf8')) as Record<string, unknown>;
}

describe.each([
  ['storefront en.json', '../src/i18n/en.json'],
  ['customer app en.json', '../../../../mobile/apps/customer/src/i18n/en.json']
])('%s makes no unearned claim', (_name, rel) => {
  const entries = Object.entries(catalog(rel)).filter((e): e is [string, string] => typeof e[1] === 'string');
  it.each(BANNED)('no %s', (_label, re) => {
    expect(entries.filter(([, v]) => re.test(v)).map(([k, v]) => `${k}: ${v}`)).toEqual([]);
  });
  it('the cart rail headings are the truthful ones', () => {
    if (!('cart.moreIn' in catalog(rel))) return;
    expect(catalog(rel)['cart.moreIn']).toBe('More in {category}');
    expect(catalog(rel)['cart.mightLike']).toBe('You might also like');
    expect(catalog(rel)).not.toHaveProperty('cart.alsoBought');
  });
});
