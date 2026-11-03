import { beforeEach, describe, expect, it } from 'vitest';

import { createSellerApiClient, isApiError } from '../src';
import { MOCK_FASHION_ORG, MOCK_FOOD_ORG, resetSellerDb, SELLER_ORG } from '../src/mocks/seller';
import { handleSellerMockRequest } from '../src/mocks/seller-runtime';

const client = () => createSellerApiClient({ baseUrl: 'http://seller.test/api/bff', headers: {}, fetch: (req) => handleSellerMockRequest(req) });

const names = (tree: { name: string }[]) => tree.map((d) => d.name);

describe('the departments an organisation may sell in (mock BFF)', () => {
  beforeEach(() => resetSellerDb());

  it('a food-only seller sees only Groceries and nothing that sells sized goods', async () => {
    const api = client();
    await api.setActiveOrg(MOCK_FOOD_ORG);
    const scope = await api.getCatalogScope();
    expect(scope.departments.map((d) => d.code)).toEqual(['groceries']);
    expect(scope.hasSizing).toBe(false);
    expect(names(await api.getCategoryTree())).toEqual(['Groceries']);
  });

  it('a fashion seller sees Fashion and sized goods', async () => {
    const api = client();
    await api.setActiveOrg(MOCK_FASHION_ORG);
    const scope = await api.getCatalogScope();
    expect(scope.departments.map((d) => d.code)).toEqual(['fashion']);
    expect(scope.hasSizing).toBe(true);
    expect(names(await api.getCategoryTree())).toEqual(['Fashion']);
  });

  it('a multi-department seller sees each of its departments', async () => {
    const api = client();
    await api.setActiveOrg(SELLER_ORG);
    const scope = await api.getCatalogScope();
    expect(scope.departments.map((d) => d.code).sort()).toEqual(['beauty', 'fashion', 'groceries']);
    expect(names(await api.getCategoryTree())).toEqual(['Groceries', 'Fashion', 'Beauty']);
  });

  it('asking for another department is remembered, says what is still needed, and is refused for one it has', async () => {
    const api = client();
    await api.setActiveOrg(MOCK_FASHION_ORG);
    const asked = await api.requestDepartment({ departmentCode: 'groceries', note: 'Spices too' });
    expect(asked).toMatchObject({ status: 'PENDING', needs: ['FOOD_PERMIT'] });
    expect((await api.requestDepartment({ departmentCode: 'groceries' })).id).toBe(asked.id);
    expect((await api.getCatalogScope()).requests).toMatchObject([{ departmentCode: 'groceries', status: 'PENDING' }]);
    await expect(api.requestDepartment({ departmentCode: 'fashion' })).rejects.toSatisfy((e) => isApiError(e) && e.status === 409);
    await expect(api.requestDepartment({ departmentCode: 'hardware' })).rejects.toSatisfy((e) => isApiError(e) && e.status === 422);
  });

  it('onboarding offers the catalog departments with the evidence each asks for', async () => {
    const api = client();
    const departments = await api.listDepartments();
    expect(departments.find((d) => d.code === 'groceries')?.requires).toEqual(['FOOD_PERMIT']);
    expect(departments.find((d) => d.code === 'fashion')?.requires).toEqual([]);
  });
});
