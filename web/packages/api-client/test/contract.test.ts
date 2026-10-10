import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

import { describe, expect, expectTypeOf, it } from 'vitest';

import { type Cart, type Home, type Me, type ProductDetail, type ProductSummary, type SearchPage, type SiteConfig, type Suggestions } from '../src';
import { type components } from '../src/generated/gateway-bff';

type S = components['schemas'];

describe('Zod schemas stay aligned with contracts/openapi/gateway-bff.yaml', () => {
  it('runtime types are assignable to the generated contract types', () => {
    expectTypeOf<ProductSummary>().toExtend<S['ProductSummary']>();
    expectTypeOf<ProductDetail>().toExtend<S['ProductDetail']>();
    expectTypeOf<SearchPage>().toExtend<S['SearchPage']>();
    expectTypeOf<Suggestions>().toExtend<S['Suggestions']>();
    expectTypeOf<Cart>().toExtend<S['Cart']>();
    expectTypeOf<Home>().toExtend<S['Home']>();
    expectTypeOf<SiteConfig>().toExtend<S['SiteConfig']>();
    expectTypeOf<Me>().toExtend<S['Me']>();
  });

  it('generated files carry the do-not-edit banner', () => {
    const text = readFileSync(resolve(__dirname, '../src/generated/gateway-bff.ts'), 'utf8');
    expect(text.startsWith('// GENERATED from contracts/openapi/gateway-bff.yaml')).toBe(true);
  });
});
