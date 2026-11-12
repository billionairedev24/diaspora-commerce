import { beforeEach, describe, expect, it } from 'vitest';

import { createSellerApiClient, isApiError } from '../src';
import { getSellerDb, MOCK_FASHION_ORG, MOCK_FOOD_ORG, resetSellerDb, SELLER_ORG } from '../src/mocks/seller';
import { handleSellerMockRequest } from '../src/mocks/seller-runtime';

const client = () => createSellerApiClient({ baseUrl: 'http://seller.test/api/bff', headers: {}, fetch: (req) => handleSellerMockRequest(req) });

const names = (tree: { name: string }[]) => tree.map((d) => d.name);

describe('a seller may sell in any department (mock BFF)', () => {
  beforeEach(() => resetSellerDb());

  it('a food-only seller sees every department and Fashion sizing: registered departments restrict nothing', async () => {
    const api = client();
    await api.setActiveOrg(MOCK_FOOD_ORG);
    const scope = await api.getCatalogScope();
    expect(scope.exempt).toBe(true);
    expect(scope.departments.map((d) => d.code).sort()).toEqual(['beauty', 'books', 'fashion', 'groceries']);
    expect(scope.hasSizing).toBe(true);
    expect(scope.requests).toEqual([]);
    expect(names(await api.getCategoryTree())).toEqual(['Groceries', 'Fashion', 'Beauty', 'Books']);
  });

  it('every seller, whatever it registered for, gets the whole category tree', async () => {
    const trees: string[][] = [];
    for (const org of [MOCK_FOOD_ORG, MOCK_FASHION_ORG, SELLER_ORG]) {
      const api = client();
      await api.setActiveOrg(org);
      trees.push(names(await api.getCategoryTree()));
    }
    expect(trees[0]).toEqual(trees[1]);
    expect(trees[1]).toEqual(trees[2]);
    expect(trees[0]).toEqual(['Groceries', 'Fashion', 'Beauty', 'Books']);
  });

  it('the category tree does not depend on the department lookup', async () => {
    const api = client();
    await api.setActiveOrg(MOCK_FOOD_ORG);
    getSellerDb().scopeUnavailable = true;
    await expect(api.getCatalogScope()).rejects.toSatisfy((e) => isApiError(e) && e.status === 503);
    expect(names(await api.getCategoryTree())).toEqual(['Groceries', 'Fashion', 'Beauty', 'Books']);
  });

  it('onboarding offers the catalog departments with the evidence each asks for', async () => {
    const api = client();
    const departments = await api.listDepartments();
    expect(departments.find((d) => d.code === 'groceries')?.requires).toEqual([]);
    expect(departments.find((d) => d.code === 'beauty')?.requires).toEqual(['BEAUTY_LABELLING']);
    expect(departments.find((d) => d.code === 'fashion')?.requires).toEqual([]);
  });
});
