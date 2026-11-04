import { afterEach, beforeEach, describe, expect, expectTypeOf, it, vi } from 'vitest';

import {
  createSellerApiClient,
  createStaffApiClient,
  isApiError,
  type RunDispatch,
  type RunDispatchList,
  sellerKeys,
  sellerQueries,
  type SellerRunPickup,
  type SellerRunPickupList,
  staffKeys,
  staffQueries
} from '../src';
import { type components as SellerComponents } from '../src/generated/gateway-bff-seller';
import { type components as StaffComponents } from '../src/generated/gateway-bff-staff';
import { MOCK_ROLE_COOKIE, MOCK_RUN_AMARILLO, MOCK_RUN_LUBBOCK, resetSellerDb, SELLER_ORG } from '../src/mocks/seller';
import { handleSellerMockRequest } from '../src/mocks/seller-runtime';
import { MOCK_STAFF_ROLE_COOKIE, resetStaffDb, RUN_AMARILLO, RUN_LUBBOCK, RUN_ODESSA } from '../src/mocks/staff';
import { handleStaffMockRequest } from '../src/mocks/staff-runtime';

/* Delivery runs (S264): the seller's pick list and the staff dispatch view, against the mocks that implement
   gateway-bff-seller.yaml and gateway-bff-staff.yaml. */

const seller = (cookie?: string) =>
  createSellerApiClient({ baseUrl: 'http://seller.test/api/bff', headers: cookie ? { cookie } : {}, fetch: (req) => handleSellerMockRequest(req) });
const staff = (roles: string) =>
  createStaffApiClient({
    baseUrl: 'http://staff.test/api/bff',
    headers: { cookie: `${MOCK_STAFF_ROLE_COOKIE}=${roles}`, origin: 'http://staff.test' },
    fetch: (req) => handleStaffMockRequest(req)
  });

describe('Run schemas stay aligned with the contracts', () => {
  it('runtime types are assignable to the generated contract types', () => {
    expectTypeOf<SellerRunPickup>().toExtend<SellerComponents['schemas']['SellerRunPickup']>();
    expectTypeOf<SellerRunPickupList>().toExtend<SellerComponents['schemas']['SellerRunPickupList']>();
    expectTypeOf<RunDispatch>().toExtend<StaffComponents['schemas']['RunDispatch']>();
    expectTypeOf<RunDispatchList>().toExtend<StaffComponents['schemas']['RunDispatchList']>();
  });
});

describe('Seller run pick lists', () => {
  beforeEach(() => resetSellerDb(new Date('2026-10-10T15:00:00Z')));

  it('lists the runs that collect from the active organisation with a pick list per product and per order', async () => {
    const api = seller();
    const { items } = await api.listRunPickups();
    expect(items.map((r) => r.runId)).toEqual([MOCK_RUN_LUBBOCK, MOCK_RUN_AMARILLO]);
    const lubbock = items[0];
    if (!lubbock) throw new Error('no run');
    expect(lubbock).toMatchObject({ town: 'Lubbock', runStatus: 'COLLECTING', dropMode: 'DOORSTEP', orders: 3, units: 9, collectedOrders: 1 });
    // Units per product across the orders, most units first.
    expect(lubbock.items.map((i) => [i.name, i.units])).toEqual([['Palm oil 1L', 3], ['Gari 1kg', 3], ['Suya spice 200g', 3]].sort((a, b) => Number(b[1]) - Number(a[1]) || String(a[0]).localeCompare(String(b[0]))));
    expect(lubbock.bags.map((b) => [b.orderNumber, b.collected])).toEqual([['LBK-1001', true], ['LBK-1002', false], ['LBK-1004', false]]);
    expect(lubbock.pickupAt).not.toBeNull();
  });

  it('opens one run and answers 404 for a run the organisation has no lines on', async () => {
    const api = seller();
    const one = await api.getRunPickup(MOCK_RUN_AMARILLO);
    expect(one).toMatchObject({ town: 'Amarillo', dropMode: 'PICKUP_POINT', runStatus: 'CONFIRMED', collectedOrders: 0 });
    await expect(api.getRunPickup('00000000-0000-4000-8000-000000000000')).rejects.toSatisfy((e) => isApiError(e) && e.status === 404);
  });

  it('needs the Orders module: a marketing member is turned away', async () => {
    const marketing = seller(`${MOCK_ROLE_COOKIE}=seller.ads`);
    await expect(marketing.listRunPickups()).rejects.toSatisfy((e) => isApiError(e) && e.status === 403);
    await expect(marketing.getRunPickup(MOCK_RUN_LUBBOCK)).rejects.toSatisfy((e) => isApiError(e) && e.status === 403);
  });

  it('has query keys under the organisation, so switching organisation drops them', () => {
    expect(sellerKeys.runPickups(SELLER_ORG)).toEqual([...sellerKeys.org(SELLER_ORG), 'run-pickups']);
    expect(sellerKeys.runPickup(SELLER_ORG, 'r1').slice(0, 3)).toEqual(sellerKeys.runPickups(SELLER_ORG).slice(0, 3));
    expect(sellerQueries.runPickups(seller(), SELLER_ORG).queryKey).toEqual(sellerKeys.runPickups(SELLER_ORG));
  });
});

describe('Staff delivery runs in dispatch', () => {
  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date('2026-10-10T15:30:00Z'));
    resetStaffDb(new Date('2026-10-10T15:00:00Z'));
  });
  afterEach(() => vi.useRealTimers());

  it('lists runs latest first without stops and says who may manage them', async () => {
    const dispatcher = await staff('platform.dispatcher').listRuns();
    expect(dispatcher.canManage).toBe(true);
    expect(dispatcher.items.map((r) => r.runId)).toEqual([RUN_AMARILLO, RUN_LUBBOCK, RUN_ODESSA]);
    expect(dispatcher.items.every((r) => r.routes.every((x) => x.stops.length === 0))).toBe(true);
    expect((await staff('platform.customer_support').listRuns()).canManage).toBe(false);
    expect((await staff('platform.dispatcher').listRuns('PENDING')).items.map((r) => r.runId)).toEqual([RUN_AMARILLO]);
    await expect(staff('platform.finance').listRuns()).rejects.toSatisfy((e) => isApiError(e) && e.status === 403);
  });

  it('opens a run with its routes, pickups before drops and the orders per stage', async () => {
    const run = await staff('platform.customer_support').getRun(RUN_LUBBOCK);
    expect(run).toMatchObject({ runStatus: 'COLLECTING', status: 'PLANNED', canManage: false });
    const stops = run.routes[0]?.stops ?? [];
    expect(stops.map((s) => s.kind)).toEqual(['PICKUP', 'PICKUP', 'DROPOFF', 'DROPOFF']);
    expect(stops.filter((s) => s.kind === 'DROPOFF').every((s) => s.handoverRequired && s.orderNumber)).toBe(true);
    expect(run.orders).toMatchObject({ orders: 2, collected: 1, delivered: 0 });
    await expect(staff('platform.dispatcher').getRun('nope')).rejects.toSatisfy((e) => isApiError(e) && e.status === 404);
  });

  it('plans a run that could not be planned and keeps pickups before drops', async () => {
    const api = staff('platform.dispatcher');
    const before = await api.getRun(RUN_AMARILLO);
    expect(before).toMatchObject({ status: 'PENDING', dropMode: 'PICKUP_POINT', planError: 'No depot is configured for this market' });
    expect(before.routes).toHaveLength(0);
    const planned = await api.planRun(RUN_AMARILLO);
    expect(planned).toMatchObject({ status: 'PLANNED', planError: null });
    const kinds = planned.routes[0]?.stops.map((s) => s.kind) ?? [];
    expect(kinds[0]).toBe('PICKUP');
    expect(kinds.slice(1).every((k) => k === 'DROPOFF')).toBe(true);
    expect(planned.routes[0]?.stops.slice(1).every((s) => s.addressLine?.startsWith('2200 Gateway Dr'))).toBe(true);
    // Planning twice is harmless.
    expect((await api.planRun(RUN_AMARILLO)).routes).toHaveLength(1);
    await expect(staff('platform.customer_support').planRun(RUN_AMARILLO)).rejects.toSatisfy((e) => isApiError(e) && e.status === 403);
  });

  it('moves a run forward one step at a time and refuses a step that does not follow', async () => {
    const api = staff('platform.dispatcher');
    await expect(api.advanceRun(RUN_LUBBOCK, { to: 'COMPLETED' })).rejects.toSatisfy((e) => isApiError(e) && e.status === 409);
    expect((await api.advanceRun(RUN_LUBBOCK, { to: 'OUT_FOR_DELIVERY' })).runStatus).toBe('OUT_FOR_DELIVERY');
    expect((await api.advanceRun(RUN_LUBBOCK, { to: 'COMPLETED' })).runStatus).toBe('COMPLETED');
    await expect(api.advanceRun(RUN_LUBBOCK, { to: 'COMPLETED' })).rejects.toSatisfy((e) => isApiError(e) && e.status === 409);
    await expect(api.advanceRun(RUN_LUBBOCK, { to: 'DELIVERED' as 'COMPLETED' })).rejects.toSatisfy((e) => isApiError(e) && e.status === 422 && !!e.fieldErrors.to);
    await expect(staff('platform.customer_support').advanceRun(RUN_AMARILLO, { to: 'COLLECTING' })).rejects.toSatisfy((e) => isApiError(e) && e.status === 403);
    expect((await api.getRun(RUN_LUBBOCK)).runStatus).toBe('COMPLETED');
  });

  it('has query keys under dispatch, so an action refreshes the runs only', () => {
    expect(staffKeys.run('r1').slice(0, 3)).toEqual(staffKeys.runs());
    expect(staffKeys.runList('PENDING')).toEqual([...staffKeys.runs(), 'list', 'PENDING']);
    expect(staffQueries.runs(staff('platform.dispatcher')).queryKey).toEqual(staffKeys.runList(''));
  });
});
