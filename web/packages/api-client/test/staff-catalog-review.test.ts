import { beforeEach, describe, expect, it } from 'vitest';

import { createStaffApiClient, type RecordDetail, type ResourceKey } from '../src';
import { MOCK_STAFF_ROLE_COOKIE, resetStaffDb } from '../src/mocks/staff';
import { handleStaffMockRequest } from '../src/mocks/staff-runtime';

/* Catalog > the review queues (S385 to S387, S390): change requests, wrong matches, duplicate pages, no-barcode items and
   held images. Names not ids, every decision with a reason of 3 or more characters, catalog:moderate, and the decision
   audited. */

const client = (roles?: string) =>
  createStaffApiClient({
    baseUrl: 'http://staff.test/api/bff',
    headers: roles ? { cookie: `${MOCK_STAFF_ROLE_COOKIE}=${roles}`, origin: 'http://staff.test' } : {},
    fetch: (req) => handleStaffMockRequest(req)
  });

const QUEUES: ResourceKey[] = ['change-requests', 'wrong-matches', 'duplicate-pages', 'gtin-exemptions', 'held-images'];

beforeEach(() => resetStaffDb(new Date('2026-11-13T12:00:00Z')));

async function status(call: Promise<unknown>): Promise<{ status: number; errors: Record<string, string> }> {
  return call.then(
    () => ({ status: 200, errors: {} }),
    (e: { status: number; problem?: { errors?: Record<string, string> } }) => ({ status: e.status, errors: e.problem?.errors ?? {} })
  );
}

describe('the queues', () => {
  it('list pending requests with names, age and the service level, oldest first, paged on the server', async () => {
    const page = await client().listResource('change-requests', { status: 'PENDING', page: 0, size: 2 });
    expect(page.title).toBe('Change requests');
    expect(page.columns.map((c) => c.key)).toEqual(['title', 'change', 'asked', 'requestedByOrgName', 'waiting', 'status']);
    expect(page.rows).toHaveLength(2);
    expect(page.total).toBe(3);
    const first = page.rows[0]?.cells ?? {};
    expect(first.requestedByOrgName).toBe('Kente House');
    expect(first.waiting).toBe('2 days · overdue');
    expect(JSON.stringify(page.rows)).not.toMatch(/org_/);
  });

  it('exist for every kind and every one names the seller, never an id', async () => {
    for (const key of QUEUES) {
      const page = await client().listResource(key, { page: 0, size: 10 });
      expect(page.rows.length, key).toBeGreaterThan(0);
      expect(JSON.stringify(page.rows), key).not.toMatch(/org[-_][a-z0-9]/i);
    }
  });

  it('show the held photo itself, what was found and the score', async () => {
    const detail = await client().getRecord('held-images', 'img-1');
    const photo = detail.sections.find((s) => s.title === 'Photo');
    expect(photo?.fields).toHaveLength(1);
    expect(photo?.fields[0]).toMatchObject({ kind: 'image' });
    const why = Object.fromEntries((detail.sections.find((s) => s.title === 'Why it is held')?.fields ?? []).map((f) => [f.label, f.value]));
    expect(why['Checks found']).toBe('Text, logo or watermark');
    expect(why['Labels the AI found']).toBe('Watermark');
    expect(why['Match with the title and brand']).toContain('62%');
    expect(detail.banner?.text).toContain('5 minutes');
  });

  it('show the duplicate hints of a no-barcode item to staff', async () => {
    const detail = await client().getRecord('gtin-exemptions', 'ex-1');
    expect(detail.tables[0]?.title).toBe('Pages that may be the same item');
    expect(detail.tables[0]?.rows[0]?.cells.title).toBe('Hand carved teak stool');
  });
});

describe('decisions', () => {
  const act = (key: ResourceKey, id: string, action: string, values: Record<string, string | boolean>, roles?: string): Promise<RecordDetail> =>
    client(roles).runAction(key, id, action, values);

  it('need a reason of 3 or more characters', async () => {
    for (const reason of ['', '  ', 'ok']) {
      const refused = await status(act('change-requests', 'cr-1', 'approve', { reason }));
      expect(refused.status).toBe(422);
      expect(refused.errors.reason).toBeTruthy();
    }
    expect((await client().getRecord('change-requests', 'cr-1')).status).toBe('PENDING');
  });

  it('approve a change with the reason on the record and who decided', async () => {
    const done = await act('change-requests', 'cr-1', 'approve', { reason: 'Matches the pack' });
    const flat = Object.fromEntries(done.sections.flatMap((s) => s.fields.map((f) => [f.label, f.value])));
    expect(done.status).toBe('APPLIED');
    expect(flat['Reason']).toBe('Matches the pack');
    expect(flat['Decided by']).toBeTruthy();
    expect(done.actions.every((a) => !a.allowed)).toBe(true);
    expect(done.activity[0]?.text).toContain('Matches the pack');
  });

  it('reject, and a decided request cannot be decided twice', async () => {
    const done = await act('wrong-matches', 'wm-1', 'reject', { reason: 'The barcode is right' });
    expect(done.status).toBe('REJECTED');
    expect((await status(act('wrong-matches', 'wm-1', 'split', { reason: 'Changed my mind' }))).status).toBe(409);
  });

  it('a wrong match can be corrected, moved or split, and a duplicate page merged, each with its resolution', async () => {
    const corrected = await act('wrong-matches', 'wm-1', 'correct', { reason: 'Seller is right', gtin: '4006381333931' });
    expect(corrected.status).toBe('APPLIED');
    expect(JSON.stringify(corrected.sections)).toContain('Barcode corrected');
    const merged = await act('duplicate-pages', 'dp-1', 'merge', { reason: 'Same product, two pages', survivorAtin: 'AT10000001' });
    expect(JSON.stringify(merged.sections)).toContain('Pages merged');
  });

  it('release or reject a held photo with a reason', async () => {
    expect((await status(act('held-images', 'img-1', 'approve', { reason: 'x' }))).status).toBe(422);
    const released = await act('held-images', 'img-1', 'approve', { reason: 'Packaging text only' });
    expect(released.status).toBe('RELEASED');
    const rejected = await act('held-images', 'img-2', 'reject', { reason: 'A watermark of another shop' });
    expect(rejected.status).toBe('REJECTED');
  });

  it('accept or reject a no-barcode item', async () => {
    const done = await act('gtin-exemptions', 'ex-1', 'approve', { reason: 'Handmade, checked the maker' });
    expect(done.status).toBe('APPROVED');
  });

  it('a new variant offers the hold of the page for tax', async () => {
    const detail = await client().getRecord('change-requests', 'cr-3');
    const approve = detail.actions.find((a) => a.key === 'approve');
    expect(approve?.form?.fields.map((f) => f.key)).toEqual(['holdPageForTax', 'reason']);
    const plain = await client().getRecord('change-requests', 'cr-1');
    expect(plain.actions.find((a) => a.key === 'approve')?.form?.fields.map((f) => f.key)).toEqual(['reason']);
  });

  it('are for staff with catalog:moderate only', async () => {
    const refused = await status(act('change-requests', 'cr-1', 'approve', { reason: 'Matches the pack' }, 'platform.customer_support'));
    expect([403, 404]).toContain(refused.status);
  });
});
