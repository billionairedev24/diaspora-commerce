import { describe, expect, it } from 'vitest';

import { createStaffApiClient, type ResourceKey } from '../src';
import { MOCK_STAFF_ROLE_COOKIE } from '../src/mocks/staff';
import { RESOURCES } from '../src/mocks/staff/resources';
import { handleStaffMockRequest } from '../src/mocks/staff-runtime';

/*
 * Names, not ids. Staff must never have to read an internal id (org_rf8l…, a user or driver id) to know who or what a
 * row is about. The BFF names every id it shows; the MSW mocks do the same so the mock UI reads like the real one, and
 * this test keeps a table column from being bound to an id again.
 */

/** Fields that hold an internal id of a person or an organisation (the Go BFF's staff_names_test.go lists the same). */
const ID_KEYS = [
  'ownerOrgId', 'sellerOrgId', 'orgId', 'customerId', 'userId', 'actorId', 'reviewerId', 'driverId', 'routeId', 'stopId',
  'requestedBy', 'createdBy', 'updatedBy', 'decidedBy', 'approvedBy', 'assignee', 'proposedBy', 'resolvedBy', 'changedBy', 'recordedBy'
];

const client = () =>
  createStaffApiClient({
    baseUrl: 'http://staff.test/api/bff',
    headers: { cookie: `${MOCK_STAFF_ROLE_COOKIE}=platform.super_admin`, origin: 'http://staff.test' },
    fetch: (req) => handleStaffMockRequest(req)
  });

describe('no table column shows an id of a person or an organisation', () => {
  it('has no mono column bound to a known id key without a resolver', () => {
    const offenders = Object.entries(RESOURCES).flatMap(([resource, def]) =>
      def.columns.filter((c) => c.kind === 'mono' && ID_KEYS.includes(c.key)).map((c) => `${resource}.${c.key}`)
    );
    expect(offenders).toEqual([]);
  });

  it('flags the pattern: a mono column on an id key is exactly what the guard looks for', () => {
    const bad = [{ key: 'sellerOrgId', kind: 'mono' }, { key: 'sellerOrgName', kind: 'text' }];
    expect(bad.filter((c) => c.kind === 'mono' && ID_KEYS.includes(c.key)).map((c) => c.key)).toEqual(['sellerOrgId']);
  });
});

describe('the mock tables read like the real ones', () => {
  it('shows the driver and route of an SOS alert by name, finds them by id too, and keeps the ids in the sheet', async () => {
    const api = client();
    const page = await api.listResource('sos-alerts', { page: 0, size: 10 });
    const keys = page.columns.map((c) => c.key);
    expect(keys).toContain('driverName');
    expect(keys).not.toContain('driverId');
    const first = page.rows[0];
    expect(first?.cells.driverName).toBe('Tunde Adeyemi');
    expect(first?.cells.routeName).toBe('HOU West AM');
    // The id is not shown, yet the search box finds it, and so does the name.
    expect((await api.listResource('sos-alerts', { page: 0, size: 10, q: 'drv-1' })).rows.map((r) => r.id)).toEqual(['sos-1']);
    expect((await api.listResource('sos-alerts', { page: 0, size: 10, q: 'tunde' })).rows.map((r) => r.id)).toEqual(['sos-1']);
    const sheet = await api.getRecord('sos-alerts', 'sos-1');
    const raw = sheet.sections.filter((s) => s.title !== 'IDs').flatMap((s) => s.fields.map((f) => f.value));
    expect(raw).not.toContain('drv-1');
    expect(raw).not.toContain('rte-1000');
    expect(sheet.subtitle).toBe('Driver Tunde Adeyemi · route HOU West AM');
    const ids = sheet.sections.find((s) => s.title === 'IDs');
    expect(ids?.fields).toEqual(expect.arrayContaining([{ label: 'Driver ID', value: 'drv-1', kind: 'mono' }, { label: 'Route ID', value: 'rte-1000', kind: 'mono' }]));
  });

  it('sorts and exports by the name', async () => {
    const api = client();
    const sorted = await api.listResource('driver-events', { page: 0, size: 50, sort: ['driverName,asc'] });
    const names = sorted.rows.map((r) => String(r.cells.driverName));
    expect(names).toEqual([...names].sort((a, b) => a.localeCompare(b)));
    expect(names[0]).toBe('Chinedu Okafor');
  });

  it('never leaks an id into a cell of any table', async () => {
    const api = client();
    const idLike = /^(org[_-]|drv-|stf-|cus-|usr[_-])/;
    for (const key of Object.keys(RESOURCES) as ResourceKey[]) {
      const page = await api.listResource(key, { page: 0, size: 25 });
      for (const row of page.rows) {
        for (const [col, value] of Object.entries(row.cells)) {
          if (typeof value === 'string' && idLike.test(value)) throw new Error(`${key}: column ${col} shows the id ${value}`);
        }
      }
    }
  });

  it('shows who reviewed an application by name and the reviewer id apart', async () => {
    const api = client();
    const apps = await api.listResource('applications', { page: 0, size: 20 });
    const waiting = apps.rows.find((r) => r.cells.status === 'AWAITING_FINAL_APPROVAL');
    expect(waiting).toBeDefined();
    const sheet = await api.getRecord('applications', waiting?.id ?? '');
    const review = sheet.sections.find((s) => s.title === 'Review');
    expect(review?.fields.find((f) => f.label === 'Reviewer name')?.value).toBe('Sara Tesfaye');
    expect(sheet.sections.find((s) => s.title === 'IDs')?.fields[0]).toEqual({ label: 'Reviewer ID', value: 'stf-2', kind: 'mono' });
    expect(review?.fields.map((f) => f.value)).not.toContain('stf-2');
  });
});
