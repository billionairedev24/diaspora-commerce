import { afterEach, beforeEach, describe, expect, expectTypeOf, it, vi } from 'vitest';

import { createStaffApiClient, type IdentityDocuments, type IdentityTokenInspection, isApiError, type OneTimeSecret, type RecordDetail } from '../src';
import { type components } from '../src/generated/gateway-bff-staff';
import { MOCK_STAFF_ROLE_COOKIE, resetStaffDb } from '../src/mocks/staff';
import { MOCK_STEP_UP_PASSWORD, MOCK_VALID_SIGNATURE } from '../src/mocks/staff/identity';
import { handleStaffMockRequest } from '../src/mocks/staff-runtime';

type S = components['schemas'];

const client = (roles?: string) =>
  createStaffApiClient({
    baseUrl: 'http://staff.test/api/bff',
    headers: roles ? { cookie: `${MOCK_STAFF_ROLE_COOKIE}=${roles}`, origin: 'http://staff.test' } : {},
    fetch: (req) => handleStaffMockRequest(req)
  });

const WHY = 'Routine check by the security team';
const SURE = { stepUpPassword: MOCK_STEP_UP_PASSWORD };
const b64 = (o: unknown) => btoa(JSON.stringify(o)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
const jwt = (header: object, claims: object, signature = MOCK_VALID_SIGNATURE) => `${b64(header)}.${b64(claims)}.${signature}`;

describe('Identity & access: contract alignment', () => {
  it('runtime types are assignable to the generated contract types', () => {
    expectTypeOf<IdentityTokenInspection>().toExtend<S['IdentityTokenInspection']>();
    expectTypeOf<IdentityDocuments>().toExtend<S['IdentityDocuments']>();
    expectTypeOf<OneTimeSecret>().toEqualTypeOf<S['OneTimeSecret']>();
    expectTypeOf<RecordDetail>().toExtend<S['RecordDetail']>();
  });
});

describe('Identity & access mocks (every answer is parsed by the Zod client)', () => {
  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date('2026-10-24T10:00:00Z'));
    resetStaffDb(new Date('2026-10-24T10:00:00Z'));
  });
  afterEach(() => vi.useRealTimers());

  it('only Super Admin (identity:admin) has the module in the sidebar and can open its tables', async () => {
    const keys = (await client().getSession()).navGroups.flatMap((g) => g.modules.map((m) => m.key));
    expect(keys).toContain('identity');
    for (const roles of ['platform.developer', 'platform.finance', 'platform.auditor', 'platform.customer_support']) {
      const session = await client(roles).getSession();
      expect(session.navGroups.flatMap((g) => g.modules.map((m) => m.key)), roles).not.toContain('identity');
      for (const r of ['oauth-clients', 'identity-sessions', 'signing-keys', 'auth-settings'] as const) {
        await expect(client(roles).listResource(r, { page: 0, size: 10 })).rejects.toMatchObject({ status: 403 });
      }
      await expect(client(roles).inspectIdentityToken('a.b.c')).rejects.toMatchObject({ status: 403 });
      await expect(client(roles).getIdentityDocuments()).rejects.toMatchObject({ status: 403 });
    }
  });

  it('lists clients, sessions, signing keys and the settings', async () => {
    const api = client();
    const clients = await api.listResource('oauth-clients', { page: 0, size: 25 });
    expect(clients.rows.map((r) => r.id)).toEqual(['gateway-bff', 'customer-app', 'driver-app', 'inventory-service', 'media-service']);
    expect(clients.canCreate).toBe(true);
    expect(clients.rows.find((r) => r.id === 'media-service')?.tone).toBe('bad');
    expect((await api.listResource('identity-sessions', { page: 0, size: 25 })).rows).toHaveLength(6);
    const keys = await api.listResource('signing-keys', { page: 0, size: 25 });
    expect(keys.rows.map((r) => r.tone)).toEqual(['good', 'info', 'info']);
    expect((await api.listResource('auth-settings', { page: 0, size: 5 })).rows).toHaveLength(1);
  });

  it('shows a new secret once: in the answer to the create and the rotation, never in a later read', async () => {
    const api = client();
    const created = await api.createRecord('oauth-clients', {
      clientId: 'partner-api',
      type: 'confidential',
      name: 'Partner API',
      grantTypes: ['client_credentials'],
      scopes: 'partner.read',
      accessTokenTtlMinutes: 5,
      refreshTokenTtlMinutes: 60,
      reason: WHY
    });
    const secret = created.reveal?.value ?? '';
    expect(secret.length).toBeGreaterThan(20);
    const again = await api.getRecord('oauth-clients', 'partner-api');
    expect(again.reveal).toBeUndefined();
    expect(JSON.stringify(again)).not.toContain(secret);

    const rotated = await api.runAction('oauth-clients', 'partner-api', 'rotate-secret', { reason: WHY });
    expect(rotated.reveal?.value).toBeTruthy();
    expect(rotated.reveal?.value).not.toBe(secret);
    expect(JSON.stringify(await api.getRecord('oauth-clients', 'partner-api'))).not.toContain(rotated.reveal?.value ?? 'x');
    expect(rotated.sections.find((s) => s.title === 'Secret')?.fields.find((f) => f.label === 'Previous secret expires at')?.value).not.toBe('—');
  });

  it('every change needs a reason, and a public client has no secret to rotate', async () => {
    const api = client();
    await expect(api.runAction('oauth-clients', 'driver-app', 'disable', { reason: 'short', ...SURE })).rejects.toSatisfy((e) => isApiError(e) && e.status === 422 && 'reason' in e.fieldErrors);
    await expect(api.runAction('oauth-clients', 'driver-app', 'rotate-secret', { reason: WHY })).rejects.toMatchObject({ status: 409 });
    await expect(api.createRecord('signing-keys', { ...SURE })).rejects.toMatchObject({ status: 422 });
    await expect(api.updateRecord('auth-settings', 'settings', { passwordMinLength: 12 }, null)).rejects.toMatchObject({ status: 422 });
  });

  it('asks for the password on the most dangerous actions and refuses a missing or wrong one', async () => {
    const api = client();
    const sheet = await api.getRecord('oauth-clients', 'driver-app');
    for (const key of ['disable', 'revoke-sessions']) {
      const fields = sheet.actions.find((a) => a.key === key)?.form?.fields ?? [];
      expect(fields.at(-1)).toMatchObject({ key: 'stepUpPassword', type: 'password', required: false });
    }
    // not asked for the ordinary ones
    expect(sheet.actions.find((a) => a.key === 'enable')?.form?.fields.map((f) => f.key)).toEqual(['reason']);
    expect((await api.getRecord('identity-sessions', 'auth-1001')).actions.find((a) => a.key === 'revoke-user')?.form?.fields.at(-1)?.key).toBe('stepUpPassword');
    expect((await api.listResource('signing-keys', { page: 0, size: 25 })).createForm?.fields.at(-1)?.key).toBe('stepUpPassword');
    expect((await api.getRecord('auth-settings', 'settings')).editForm?.fields.at(-1)?.key).toBe('stepUpPassword');

    const refused = (e: unknown) => isApiError(e) && e.status === 422 && 'stepUpPassword' in e.fieldErrors;
    await expect(api.runAction('oauth-clients', 'driver-app', 'disable', { reason: WHY })).rejects.toSatisfy(refused);
    await expect(api.runAction('oauth-clients', 'driver-app', 'disable', { reason: WHY, stepUpPassword: 'not-it' })).rejects.toSatisfy(refused);
    await expect(api.createRecord('signing-keys', { reason: WHY, stepUpPassword: 'not-it' })).rejects.toSatisfy(refused);
    // nothing happened
    expect((await api.getRecord('oauth-clients', 'driver-app')).status).toBe('ENABLED');
    expect((await api.listResource('signing-keys', { page: 0, size: 25 })).rows).toHaveLength(3);
    // the password is never part of an answer
    const done = await api.runAction('oauth-clients', 'driver-app', 'disable', { reason: WHY, ...SURE });
    expect(JSON.stringify(done)).not.toContain(MOCK_STEP_UP_PASSWORD);
  });

  it('disables and enables a client, but not the platform’s own', async () => {
    const api = client();
    const sheet = await api.getRecord('oauth-clients', 'gateway-bff');
    const disable = sheet.actions.find((a) => a.key === 'disable');
    expect(disable?.allowed).toBe(false);
    expect(disable?.reason).toMatch(/lock every staff member out/);
    await expect(api.runAction('oauth-clients', 'gateway-bff', 'disable', { reason: WHY, ...SURE })).rejects.toMatchObject({ status: 409 });
    expect((await api.runAction('oauth-clients', 'driver-app', 'disable', { reason: WHY, ...SURE })).status).toBe('DISABLED');
    expect((await api.runAction('oauth-clients', 'driver-app', 'enable', { reason: WHY })).status).toBe('ENABLED');
  });

  it('revokes one sign-in, a person’s sign-ins and a client’s sign-ins', async () => {
    const api = client();
    await api.runAction('identity-sessions', 'auth-1001', 'revoke', { reason: WHY });
    expect((await api.listResource('identity-sessions', { page: 0, size: 25 })).rows.map((r) => r.id)).not.toContain('auth-1001');
    // Amara's other sign-in goes with "sign out everywhere"
    const out = await api.runAction('identity-sessions', 'auth-1002', 'revoke-user', { reason: WHY, ...SURE });
    expect(out.removed).toBe(true);
    await api.runAction('oauth-clients', 'gateway-bff', 'revoke-sessions', { reason: WHY, ...SURE });
    const left = await api.listResource('identity-sessions', { page: 0, size: 25 });
    expect(left.rows.map((r) => r.id)).toEqual(['auth-1004', 'auth-1006']);
    const service = await api.getRecord('identity-sessions', 'auth-1006');
    expect(service.actions.find((a) => a.key === 'revoke-user')?.allowed).toBe(false);
  });

  it('rotates the signing key: the new key signs, the old one stays published but retiring', async () => {
    const api = client();
    const made = await api.createRecord('signing-keys', { reason: WHY, ...SURE });
    expect(made.status).toBe('ACTIVE');
    const rows = (await api.listResource('signing-keys', { page: 0, size: 25 })).rows;
    expect(rows.filter((r) => r.tone === 'good')).toHaveLength(1);
    expect(rows.map((r) => r.id)).toContain(made.id);
    const docs = await api.getIdentityDocuments();
    const kids = (docs.jwks.keys as { kid: string }[]).map((k) => k.kid);
    expect(kids).toContain(made.id);
    expect(kids).not.toContain('identity-1');
  });

  it('stores only a valid settings change', async () => {
    const api = client();
    const sheet = await api.getRecord('auth-settings', 'settings');
    const values = Object.fromEntries((sheet.editForm?.fields ?? []).map((f) => [f.key, f.value ?? null]));
    await expect(api.updateRecord('auth-settings', 'settings', { ...values, passwordMinLength: 4, reason: WHY, ...SURE }, null)).rejects.toMatchObject({ status: 422 });
    const saved = await api.updateRecord('auth-settings', 'settings', { ...values, passwordMinLength: 16, mfaRequiredSeller: true, reason: WHY, ...SURE }, null);
    expect(saved.sections[0]?.fields.find((f) => f.label === 'Password min length')?.value).toBe('16');
  });

  it('inspects a token without echoing it, and reports what is wrong with a bad one', async () => {
    const api = client();
    const exp = Math.floor(Date.now() / 1000) + 300;
    const good = jwt({ alg: 'RS256', kid: 'k20260915T090000-3fa1' }, { iss: 'https://id.afrotransact.com', sub: 'user-1', scope: 'openid profile', exp, iat: exp - 300 });
    const ok = await api.inspectIdentityToken(good);
    expect(ok).toMatchObject({ wellFormed: true, signatureValid: true, issuerValid: true, valid: true, expired: false, keyId: 'k20260915T090000-3fa1' });
    expect(ok.scopes).toEqual(['openid', 'profile']);
    expect(JSON.stringify(ok)).not.toContain(good);

    const expired = await api.inspectIdentityToken(jwt({ alg: 'RS256', kid: 'k20260915T090000-3fa1' }, { iss: 'https://id.afrotransact.com', exp: exp - 3600 }));
    expect(expired.valid).toBe(false);
    expect(expired.expired).toBe(true);
    const forged = await api.inspectIdentityToken(jwt({ alg: 'RS256', kid: 'k20260915T090000-3fa1' }, { iss: 'https://id.afrotransact.com', exp }, 'forged'));
    expect(forged.signatureValid).toBe(false);
    const retired = await api.inspectIdentityToken(jwt({ alg: 'RS256', kid: 'identity-1' }, { iss: 'https://id.afrotransact.com', exp }));
    expect(retired.problems.join(' ')).toMatch(/No published key has the id identity-1/);
    const opaque = await api.inspectIdentityToken('opaque-refresh-token');
    expect(opaque.wellFormed).toBe(false);
    await expect(api.inspectIdentityToken('')).rejects.toMatchObject({ status: 422 });
  });
});
