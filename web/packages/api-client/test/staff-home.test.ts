import { beforeEach, describe, expect, expectTypeOf, it } from 'vitest';

import { ApiError, createStaffApiClient } from '../src';
import { type components } from '../src/generated/gateway-bff-staff';
import { homeMock, resetHomeMock } from '../src/mocks';
import { MOCK_STAFF_ROLE_COOKIE, resetStaffDb } from '../src/mocks/staff';
import { resetHomeRegistry } from '../src/mocks/staff/home-settings';
import { handleStaffMockRequest } from '../src/mocks/staff-runtime';
import { type HomeCaps, type HomeModuleRule, type HomePreview, type HomeSettings, homeSettingsProblems, staffHomeKeys } from '../src/staff-home';

/* Home settings, versions, community registry and Home preview (S417, S418, S420, S430): the schemas stay aligned with the staff
   contract and the mock BFF behaves like the Go one (mandatory reason, optimistic version, audit, revert, read-only preview). */

const NOW = new Date('2026-11-12T12:00:00Z');
const client = (roles = 'platform.marketing') =>
  createStaffApiClient({
    baseUrl: 'http://staff.test/api/bff',
    headers: { cookie: `${MOCK_STAFF_ROLE_COOKIE}=${roles}`, origin: 'http://staff.test' },
    fetch: (req) => handleStaffMockRequest(req)
  });

type S = components['schemas'];

describe('schemas stay aligned with contracts/openapi/gateway-bff-staff.yaml', () => {
  it('runtime types are assignable to the generated contract types', () => {
    expectTypeOf<HomeModuleRule>().toExtend<S['StaffHomeModuleRule']>();
    expectTypeOf<HomeCaps>().toExtend<S['StaffHomeCaps']>();
    expectTypeOf<HomeSettings['departmentLabels']>().toExtend<S['StaffHomeSettings']['departmentLabels']>();
    expectTypeOf<HomePreview['seed']>().toEqualTypeOf<S['StaffHomePreview']['seed']>();
  });

  it('has key factories per query', () => {
    expect(staffHomeKeys.settings()).toEqual(['staff', 'home', 'settings']);
    expect(staffHomeKeys.preview({ zip: '77042' })).toEqual(['staff', 'home', 'preview', { zip: '77042' }]);
  });
});

describe('home settings mock', () => {
  beforeEach(() => {
    resetStaffDb(NOW);
    resetHomeMock();
    resetHomeRegistry();
  });
  const api = () => client().home;
  const input = (s: HomeSettings) => ({ dynamic: s.dynamic, modules: s.modules, caps: s.caps, departmentLabels: s.departmentLabels });

  it('loads the documented defaults at version 0 with module names, never keys alone', async () => {
    const s = await api().settings();
    expect(s.version).toBe(0);
    expect(s.modules.communities).toMatchObject({ minUnits: 3, unitMinProducts: 8, unitMinSellers: 2 });
    expect(s.caps).toMatchObject({ maxModules: 12, sponsoredPerOrganic: 3, sellerShare: 2 });
    expect(s.moduleNames.communities).toBe('Shop by community');
  });

  it('a save needs a reason, the current version, and is kept as a version with what changed', async () => {
    const s = await api().settings();
    const next = { ...input(s), modules: { ...s.modules, communities: { ...s.modules.communities!, unitMinProducts: 12 } } };
    await expect(api().saveSettings(next, 0, '  ')).rejects.toMatchObject({ status: 422 });
    const saved = await api().saveSettings(next, 0, 'Raise the community bar');
    expect(saved).toMatchObject({ version: 1, updatedBy: expect.any(String) });
    await expect(api().saveSettings(next, 0, 'Stale save')).rejects.toMatchObject({ status: 409 });
    const versions = await api().versions();
    expect(versions[0]).toMatchObject({ version: 1, reason: 'Raise the community bar' });
    expect(versions[0]?.changes).toEqual([{ field: 'modules.communities.unitMinProducts', from: 8, to: 12 }]);
  });

  it('rejects a weight outside 0 to 100 and a minimum above the maximum', async () => {
    const s = await api().settings();
    const weight = await api().saveSettings({ ...input(s), modules: { ...s.modules, deals: { ...s.modules.deals!, weight: 150 } } }, 0, 'Try it').catch((x: unknown) => x);
    expect(weight).toBeInstanceOf(ApiError);
    expect((weight as ApiError).status).toBe(422);
    expect(Object.keys((weight as ApiError).fieldErrors).join()).toContain('weight');
    const caps = await api().saveSettings({ ...input(s), caps: { ...s.caps, minModules: 20 } }, 0, 'Try it').catch((x: unknown) => x);
    expect((caps as ApiError).fieldErrors).toHaveProperty('caps.minModules');
  });

  it('reverts to an earlier version as a new version, with a reason', async () => {
    const s = await api().settings();
    await api().saveSettings({ ...input(s), caps: { ...s.caps, maxModules: 8 } }, 0, 'Shorter page');
    const reverted = await api().revert(0, 'Back to the defaults');
    expect(reverted).toMatchObject({ version: 2, caps: { maxModules: 12 } });
    expect((await api().versions())[0]).toMatchObject({ version: 2, revertOf: 0 });
    await expect(api().revert(0, '')).rejects.toMatchObject({ status: 422 });
  });

  it('refuses staff without Marketing access, customers and guests on every route', async () => {
    // Signed in without Marketing: 403. Not signed in as staff at all: 401 (sign in first).
    for (const [roles, status] of [['platform.finance', 403], ['customer', 403], ['guest', 401]] as const) {
      const other = client(roles).home;
      await expect(other.settings(), roles).rejects.toMatchObject({ status });
      await expect(other.preview({}), roles).rejects.toMatchObject({ status });
      await expect(other.communities(), roles).rejects.toMatchObject({ status });
      await expect(other.versions(), roles).rejects.toMatchObject({ status });
    }
  });

  it('refuses a caller without Marketing access (read and write)', async () => {
    const guest = client('platform.auditor').home;
    await expect(guest.settings()).resolves.toBeDefined();
    const s = await guest.settings();
    await expect(guest.saveSettings(input(s), 0, 'Not allowed')).rejects.toMatchObject({ status: 403 });
  });

  it('preview: ZIP, sign-in state, date and catalogue change the result; it is read-only; the seed is shown', async () => {
    const live = await api().preview({ catalog: '10', zip: '77042' });
    expect(live.decisions.map((d) => d.label)).toContain('Shop by community');
    expect(live.decisions.find((d) => d.module === 'communities')).toMatchObject({ shown: false, reason: 'not-enough-products' });
    const big = await api().preview({ catalog: '100', zip: '77042' });
    expect(big.decisions.find((d) => d.module === 'communities')?.shown).toBe(true);
    const guest = await api().preview({ catalog: '100', signedIn: false });
    const member = await api().preview({ catalog: '100', signedIn: true });
    expect(guest.decisions.find((d) => d.module === 'continue-shopping')?.shown).toBe(false);
    expect(member.decisions.find((d) => d.module === 'continue-shopping')?.shown).toBe(true);
    const bf = await api().preview({ catalog: '100', asOf: '2026-11-25' });
    expect(bf.season?.name).toBe('Black Friday');
    expect(bf.asOf).toBe('2026-11-25T00:00:00.000Z');
    const a = await api().preview({ catalog: '10000', seed: 'x|1' });
    expect(a.seed).toBe('x|1');
    expect((await api().preview({ catalog: '10000', deterministic: true })).seed).toBe('default');
    expect((await api().settings()).version).toBe(0);
  });

  it('community registry: a staff entry is listed with its visibility; unregistered raw values are offered; a reason is required', async () => {
    await expect(api().saveCommunity('nigerian', { labels: { en: 'Nigerian' }, aliases: [], imageUrl: null }, '')).rejects.toMatchObject({ status: 422 });
    await expect(api().saveCommunity('nigerian', { labels: { en: 'Nigerian' }, aliases: [], imageUrl: null }, 'too short')).rejects.toMatchObject({ status: 422 });
    await api().saveCommunity('nigerian', { labels: { en: 'Nigerian', fr: 'Nigérian' }, aliases: ['naija'], imageUrl: null }, 'First registry entry');
    const list = await api().communities();
    expect(list.registered[0]).toMatchObject({ key: 'nigerian', aliases: ['naija'], labels: { fr: 'Nigérian' } });
    expect(list.unregistered.map((u) => u.value)).not.toContain('Nigerian');
    await api().deleteCommunity('nigerian', 'Remove it for now');
    expect((await api().communities()).registered).toEqual([]);
  });

  it('lists every department for the label editor: the empty ones are included with no link', async () => {
    homeMock.catalogue = 10;
    const all = await api().departments();
    expect(all.find((d) => d.name === 'Groceries & Food')).toMatchObject({ count: 4, href: '/s?dept=Groceries%20%26%20Food' });
    expect(all.find((d) => d.name === 'Electronics')).toMatchObject({ count: 0 });
    expect(all.find((d) => d.name === 'Electronics')?.href).toBeUndefined();
  });

  it('client-side checks mirror the server: reason length and English required with another language', () => {
    const base = { dynamic: null, modules: {}, caps: { maxModules: 12, minModules: 3, sellerShare: 2, sponsoredPerOrganic: 3, poolMultiplier: 3, noveltyLoads: 3, noveltyPenalty: 0.6, visitWindowMinutes: 30, hysteresis: 2, dealMinPercent: 10, navBarSize: 7 }, departmentLabels: {} };
    expect(homeSettingsProblems(base, 'nine char')).toContain('reason');
    expect(homeSettingsProblems(base, 'ten chars!')).toEqual([]);
    expect(homeSettingsProblems({ ...base, departmentLabels: { pets: { en: '', fr: 'Animaux' } } }, 'ten chars!')).toContain('departmentLabels.pets');
  });
});
