import { beforeEach, describe, expect, expectTypeOf, it } from 'vitest';

import { createStaffApiClient, type DeliveryDetail, type DeliveryList, deliveryPhotoProblem, type DeliveryProofView, isApiError, type StaffSession } from '../src';
import { type components as StaffComponents } from '../src/generated/gateway-bff-staff';
import { MOCK_STAFF_ROLE_COOKIE, resetStaffDb } from '../src/mocks/staff';
import { handleStaffMockRequest } from '../src/mocks/staff-runtime';

/* The staff Delivery module (docs/engineering/delivery-fulfilment.md) against the mock that implements the contract: the
   queue, who may act, the steps, the proof rules, idempotent repeats, optimistic locking and the audit trail. */

const staff = (roles: string) =>
  createStaffApiClient({
    baseUrl: 'http://staff.test/api/bff',
    headers: { cookie: `${MOCK_STAFF_ROLE_COOKIE}=${roles}`, origin: 'http://staff.test' },
    fetch: (req) => handleStaffMockRequest(req)
  });
const MANAGER = 'platform.dispatcher';
const READER = 'platform.customer_support';
const status = (e: unknown) => (isApiError(e) ? e.status : 0);
const READY_FBA = 'shp-dl-1';
const READY_STORE = 'shp-dl-2';
const PHOTO = new File([new Uint8Array([1, 2, 3, 4])], 'door.jpg', { type: 'image/jpeg' });

describe('Delivery schemas stay aligned with the contract', () => {
  it('runtime types are assignable to the generated contract types', () => {
    expectTypeOf<DeliveryList>().toExtend<StaffComponents['schemas']['DeliveryList']>();
    expectTypeOf<DeliveryDetail>().toExtend<StaffComponents['schemas']['DeliveryDetail']>();
    expectTypeOf<DeliveryProofView>().toExtend<StaffComponents['schemas']['DeliveryProofView']>();
  });

  it('checks a proof photo the way the server does', () => {
    expect(deliveryPhotoProblem({ type: 'image/jpeg', size: 1000 })).toBeNull();
    expect(deliveryPhotoProblem({ type: 'image/webp', size: 10 * 1024 * 1024 })).toBeNull();
    expect(deliveryPhotoProblem({ type: 'application/pdf', size: 1000 })).toMatch(/JPEG, PNG or WebP/);
    expect(deliveryPhotoProblem({ type: 'image/gif', size: 1000 })).toMatch(/JPEG, PNG or WebP/);
    expect(deliveryPhotoProblem({ type: 'image/png', size: 10 * 1024 * 1024 + 1 })).toMatch(/10 MB/);
    expect(deliveryPhotoProblem({ type: 'image/png', size: 0 })).toMatch(/empty/);
  });
});

describe('Delivery module mock', () => {
  beforeEach(() => resetStaffDb(new Date('2026-10-10T15:00:00Z')));

  it('is a module of the roles that hold delivery permissions, with its own sidebar link', async () => {
    const dispatcher = await staff(MANAGER).getSession();
    const link = dispatcher.navGroups.flatMap((g) => g.modules).find((m) => m.key === 'delivery');
    expect(link).toMatchObject({ label: 'Delivery', level: 'EDIT' });
    expect((await staff(READER).getSession()).navGroups.flatMap((g) => g.modules).find((m) => m.key === 'delivery')?.level).toBe('VIEW');
    const finance: StaffSession = await staff('platform.finance').getSession();
    expect(finance.navGroups.flatMap((g) => g.modules).some((m) => m.key === 'delivery')).toBe(false);
  });

  it('lists the active queue with names and cities, never seller-shipped orders', async () => {
    const list = await staff(MANAGER).listDeliveries();
    expect(list.canManage).toBe(true);
    expect(list.items.map((r) => r.status)).not.toContain('delivered');
    const first = list.items[0];
    expect(first?.customerCity).toBeTruthy();
    expect(first?.originCity).toBeTruthy();
    expect(first?.sellerName).toBeTruthy();
    expect(list.items.every((r) => r.mode === 'AFROTRANSACT_DELIVERY' || r.mode === 'FULFILLED_BY_AFROTRANSACT')).toBe(true);
    expect(list.counts.ready).toBe(2);
  });

  it('filters, searches, sorts and pages on the server', async () => {
    const api = staff(MANAGER);
    expect((await api.listDeliveries({ status: ['delivered'] })).items.map((r) => r.status)).toEqual(['delivered']);
    expect((await api.listDeliveries({ assignee: 'unassigned' })).items.every((r) => r.assigneeName === null)).toBe(true);
    expect((await api.listDeliveries({ q: 'Ngozi' })).items.map((r) => r.customerName)).toEqual(['Ngozi Eze']);
    expect((await api.listDeliveries({ city: 'pearl' })).items.map((r) => r.customerCity)).toEqual(['Pearland']);
    expect((await api.listDeliveries({ mode: 'AFROTRANSACT_DELIVERY' })).items.every((r) => r.mode === 'AFROTRANSACT_DELIVERY')).toBe(true);
    const desc = await api.listDeliveries({ sort: 'order', desc: true });
    expect(desc.items.map((r) => r.orderNumber)).toEqual([...desc.items.map((r) => r.orderNumber)].sort().reverse());
    const page = await api.listDeliveries({ size: 2, page: 1 });
    expect(page.items).toHaveLength(2);
    expect(page.total).toBe(5);
    await expect(api.listDeliveries({ status: ['bogus' as 'ready'] })).rejects.toSatisfy((e) => status(e) === 422);
  });

  it('lets a reader look but not act; the server refuses every action without delivery:manage', async () => {
    const api = staff(READER);
    const list = await api.listDeliveries();
    expect(list.canManage).toBe(false);
    const d = await api.getDelivery(READY_FBA);
    expect(d.canManage).toBe(false);
    expect(d.nextStatuses).toEqual([]);
    expect(d.destination.line1).toBeNull();
    expect(d.destination.phone).toBeNull();
    for (const act of [
      () => api.takeDelivery(READY_FBA, {}),
      () => api.markDeliveryPickedUp(READY_FBA, {}),
      () => api.markDeliveryDelivered(READY_FBA, { photoAssetIds: ['11111111-1111-4111-8111-111111111111'] }),
      () => api.markDeliveryFailed(READY_FBA, 'No answer'),
      () => api.requestDeliveryPhotoUpload({ name: 'a.jpg', type: 'image/jpeg', size: 10 })
    ])
      await expect(act()).rejects.toSatisfy((e) => status(e) === 403);
    await expect(staff('platform.finance').listDeliveries()).rejects.toSatisfy((e) => status(e) === 403);
    await expect(staff('platform.driver').listDeliveries()).rejects.toSatisfy((e) => status(e) === 403);
  });

  it('runs a delivery end to end with a photo and shows the proof, audit and actor names', async () => {
    const api = staff(MANAGER);
    // Nobody has it yet: whoever takes the first step takes the job, audited as a take (the order sheet and the queue offer the same steps).
    const taken = await api.getDelivery(READY_FBA);
    expect(taken.nextStatuses).toEqual(['picked_up']);
    await api.markDeliveryPickedUp(READY_FBA, { note: 'Collected from the warehouse, 2 boxes', expectedStatus: 'ready' });
    const mine = await api.getDelivery(READY_FBA);
    expect(mine.job.assigneeName).toBe('Ifeoma Okafor');
    expect(mine.row.assigneeMe).toBe(true);
    await expect(api.markDeliveryDelivered(READY_FBA, { photoAssetIds: ['11111111-1111-4111-8111-111111111111'] })).rejects.toSatisfy((e) => status(e) === 409);
    await api.markDeliveryOutForDelivery(READY_FBA, {});
    // A photo is required: none, malformed and unknown ids are refused and change nothing.
    await expect(api.markDeliveryDelivered(READY_FBA, { photoAssetIds: [] })).rejects.toSatisfy((e) => status(e) === 422);
    await expect(api.markDeliveryDelivered(READY_FBA, { photoAssetIds: ['nope'] })).rejects.toSatisfy((e) => status(e) === 422);
    expect((await api.getDelivery(READY_FBA)).row.status).toBe('out_for_delivery');
    const assetId = await api.uploadDeliveryPhoto(PHOTO);
    const done = await api.markDeliveryDelivered(READY_FBA, { photoAssetIds: [assetId], recipientName: 'Ada Obi', note: 'Left with the neighbour' });
    expect(done.row.status).toBe('delivered');
    expect(done.proof).toMatchObject({ recipientName: 'Ada Obi', note: 'Left with the neighbour', recordedByName: 'Ifeoma Okafor', latitude: null, corrections: 0 });
    expect(done.proof?.photoUrls[0]).toMatch(/^data:image\/jpeg/);
    expect(done.audit.map((a) => a.action)).toEqual(['TAKE', 'PICKED_UP', 'OUT_FOR_DELIVERY', 'DELIVERED']);
    expect(done.audit[1]?.note).toBe('Collected from the warehouse, 2 boxes');
    expect(done.audit.every((a) => a.actorName === 'Ifeoma Okafor')).toBe(true);
    // The same step again changes nothing and never replaces the saved proof.
    const again = await api.markDeliveryDelivered(READY_FBA, { photoAssetIds: [assetId], recipientName: 'Someone else' });
    expect(again.proof?.recipientName).toBe('Ada Obi');
    expect(again.audit).toHaveLength(4);
  });

  it('assigns another staff member by name, refuses people who cannot deliver, and respects the job version', async () => {
    const api = staff(MANAGER);
    const couriers = await api.listDeliveryCouriers();
    expect(couriers.map((c) => c.name)).toContain('Segun Lawal');
    const seg = couriers.find((c) => c.name === 'Segun Lawal');
    if (!seg) throw new Error('courier');
    const assigned = await api.assignDelivery(READY_STORE, seg.id, {});
    expect(assigned.job.assigneeName).toBe('Segun Lawal');
    expect(assigned.row.assigneeMe).toBe(false);
    await expect(api.assignDelivery(READY_STORE, 'stf-nobody', {})).rejects.toSatisfy((e) => status(e) === 422);
    await expect(api.assignDelivery(READY_STORE, '', {})).rejects.toSatisfy((e) => status(e) === 422);
    // Taking one somebody else has is a conflict; a stale version is a conflict too.
    await expect(api.takeDelivery(READY_STORE, {})).rejects.toSatisfy((e) => status(e) === 409);
    await expect(api.markDeliveryPickedUp(READY_STORE, { version: 0 })).rejects.toSatisfy((e) => status(e) === 409);
    const reassigned = await api.assignDelivery(READY_STORE, couriers.find((c) => c.me)?.id ?? '', { version: assigned.job.version });
    expect(reassigned.audit.map((a) => a.action)).toEqual(['ASSIGN', 'REASSIGN']);
  });

  it('records a failed attempt with a reason and allows a reattempt', async () => {
    const api = staff(MANAGER);
    await api.takeDelivery(READY_FBA, {});
    await api.markDeliveryPickedUp(READY_FBA, {});
    await api.markDeliveryOutForDelivery(READY_FBA, {});
    await expect(api.markDeliveryFailed(READY_FBA, '  ')).rejects.toSatisfy((e) => status(e) === 422);
    const failed = await api.markDeliveryFailed(READY_FBA, 'Nobody home, left a card');
    expect(failed.job).toMatchObject({ attempts: 1, lastFailure: 'Nobody home, left a card' });
    expect(failed.nextStatuses).toEqual(['out_for_delivery', 'returned']);
    const again = await api.markDeliveryOutForDelivery(READY_FBA, {});
    expect(again.audit.at(-1)?.action).toBe('REATTEMPT');
  });

  it('puts a parcel in transit (optional), returns it after a failure with a reason, and tells a stale screen', async () => {
    const api = staff(MANAGER);
    await api.markDeliveryPickedUp(READY_FBA, {});
    await expect(api.markDeliveryInTransit(READY_FBA, { expectedStatus: 'ready' })).rejects.toSatisfy((e) => status(e) === 409);
    const transit = await api.markDeliveryInTransit(READY_FBA, { expectedStatus: 'picked_up' });
    expect(transit.row.status).toBe('in_transit');
    await expect(api.markDeliveryReturned(READY_FBA, 'too early')).rejects.toSatisfy((e) => status(e) === 409);
    await api.markDeliveryOutForDelivery(READY_FBA, {});
    await api.markDeliveryFailed(READY_FBA, 'Nobody home twice');
    await expect(api.markDeliveryReturned(READY_FBA, ' ')).rejects.toSatisfy((e) => status(e) === 422);
    const back = await api.markDeliveryReturned(READY_FBA, 'Customer asked for a refund', 'delivery_failed');
    expect(back.row.status).toBe('returned');
    expect(back.audit.map((a) => a.action)).toEqual(['TAKE', 'PICKED_UP', 'IN_TRANSIT', 'OUT_FOR_DELIVERY', 'FAILED', 'RETURNED']);
    expect(back.nextStatuses).toEqual([]);
  });

  it('corrects a saved proof only with a reason and keeps the correction on the record', async () => {
    const api = staff(MANAGER);
    const done = await api.getDelivery('shp-dl-6');
    expect(done.proof?.corrections).toBe(0);
    const photo = await api.uploadDeliveryPhoto(PHOTO);
    await expect(api.correctDeliveryProof('shp-dl-6', { photoAssetIds: [photo], reason: ' ' })).rejects.toSatisfy((e) => status(e) === 422);
    await expect(api.correctDeliveryProof('shp-dl-6', { photoAssetIds: [photo], reason: 'Wrong photo', version: 9 })).rejects.toSatisfy((e) => status(e) === 409);
    const fixed = await api.correctDeliveryProof('shp-dl-6', { photoAssetIds: [photo], recipientName: 'Efua A.', reason: 'Wrong photo was attached' });
    expect(fixed.proof).toMatchObject({ corrections: 1, correctedByName: 'Ifeoma Okafor', recipientName: 'Efua A.' });
    expect(fixed.audit.at(-1)).toMatchObject({ action: 'PROOF_CORRECTED', note: 'Wrong photo was attached' });
    // No proof, nothing to correct.
    await expect(api.correctDeliveryProof(READY_FBA, { photoAssetIds: [photo], reason: 'why' })).rejects.toSatisfy((e) => status(e) === 409);
  });

  it('refuses photos that are not images or are too large before any upload is signed', async () => {
    const api = staff(MANAGER);
    await expect(api.requestDeliveryPhotoUpload({ name: 'a.pdf', type: 'application/pdf', size: 100 })).rejects.toSatisfy((e) => status(e) === 422);
    await expect(api.requestDeliveryPhotoUpload({ name: 'a.jpg', type: 'image/jpeg', size: 11 * 1024 * 1024 })).rejects.toSatisfy((e) => status(e) === 422);
    await expect(api.requestDeliveryPhotoUpload({ name: '../a.jpg', type: 'image/jpeg', size: 100 })).rejects.toSatisfy((e) => status(e) === 422);
    const ticket = await api.requestDeliveryPhotoUpload({ name: 'a.jpg', type: 'image/jpeg', size: 100 });
    expect(ticket.upload.method).toBe('PUT');
  });
});
