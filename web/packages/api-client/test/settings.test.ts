import { getResponse } from 'msw';
import { describe, expect, expectTypeOf, it } from 'vitest';

import { type components } from '../src/generated/gateway-bff';
import { settingsHandlers, totpAt } from '../src/mocks/settings';
import { type AccountPreferences, type AccountProfile, type AccountSecurity, type AlertPreferences, type SignInEvent } from '../src/schemas/settings';

type S = components['schemas'];

describe('settings schemas stay aligned with the contract', () => {
  it('runtime types are assignable to the generated contract types', () => {
    expectTypeOf<AccountProfile>().toExtend<S['AccountProfile']>();
    expectTypeOf<AccountPreferences>().toExtend<S['AccountPreferences']>();
    expectTypeOf<AlertPreferences>().toExtend<S['AlertPreferences']>();
    expectTypeOf<SignInEvent>().toExtend<S['SignInEvent']>();
    expectTypeOf<AccountSecurity>().toExtend<S['SecurityOverview']>();
  });
});

describe('mock authenticator', () => {
  it('computes RFC 6238 codes (SHA-1 test vector)', async () => {
    // secret "12345678901234567890", T = 59 s → step 1 → 94287082 (8 digits) / 287082 (6 digits)
    expect(await totpAt('GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ', 1)).toBe('287082');
  });

  const staff = settingsHandlers({ kind: 'staff', person: () => ({ id: 'stf-9', firstName: 'Ife', lastName: 'Okafor', email: 'ife@afrotransact.com' }) });
  const call = async (method: string, path: string, body?: unknown) => {
    const res = await getResponse(staff, new Request(`http://bff.test${path}`, { method, headers: { 'content-type': 'application/json' }, body: body ? JSON.stringify(body) : undefined }));
    if (!res) throw new Error(`no mock for ${method} ${path}`);
    return { status: res.status, json: res.status === 204 ? null : ((await res.json()) as Record<string, unknown>) };
  };

  it('enrols with a real TOTP and keeps staff two-step verification on', async () => {
    const setup = (await call('POST', '/v1/account/mfa/totp')).json as { secret: string; otpauthUri: string };
    expect(setup.otpauthUri).toBe(`otpauth://totp/AfroTransact:ife%40afrotransact.com?secret=${setup.secret}&issuer=AfroTransact&algorithm=SHA1&digits=6&period=30`);
    expect((await call('POST', '/v1/account/mfa/totp/confirm', { code: '000000' })).status).toBe(422);
    const code = await totpAt(setup.secret, Math.floor(Date.now() / 30_000));
    expect((await call('POST', '/v1/account/mfa/totp/confirm', { code })).status).toBe(200);
    const mfa = ((await call('GET', '/v1/account/security')).json as AccountSecurity).mfa;
    expect(mfa).toMatchObject({ totp: true, emailOtp: true, sms: false, smsAvailable: false, required: true, mayDisable: false, mayRemoveTotp: true, mayRemoveEmailOtp: true });
    // turning a factor off asks for the password again
    expect((await call('DELETE', '/v1/account/mfa/email')).status).toBe(422);
    expect((await call('DELETE', '/v1/account/mfa/email', { currentPassword: 'Right-Password-1' })).status).toBe(204);
    // the last factor of a mandatory account stays on
    expect((await call('DELETE', '/v1/account/mfa/totp', { currentPassword: 'Right-Password-1' })).status).toBe(403);
  });

  it('turns email codes on with a first emailed code, and the reminder goes away once dismissed', async () => {
    const seller = settingsHandlers({ kind: 'seller', person: () => ({ id: 'slr-3', firstName: 'Ama', lastName: 'Boateng', email: 'ama@example.com' }) });
    const run = async (method: string, path: string, body?: unknown) => {
      const res = await getResponse(seller, new Request(`http://bff.test${path}`, { method, headers: { 'content-type': 'application/json' }, body: body ? JSON.stringify(body) : undefined }));
      if (!res) throw new Error(`no mock for ${method} ${path}`);
      return { status: res.status, json: res.status === 204 ? null : ((await res.json()) as Record<string, unknown>) };
    };
    const before = ((await run('GET', '/v1/account/security')).json as AccountSecurity).mfa;
    expect(before).toMatchObject({ totp: false, emailOtp: false, sms: false, setupSuggested: true, required: false });
    expect((await run('POST', '/v1/account/mfa/email/confirm', { code: '246810' })).status).toBe(422);
    expect((await run('POST', '/v1/account/mfa/email')).status).toBe(202);
    expect((await run('POST', '/v1/account/mfa/email/confirm', { code: '000000' })).status).toBe(422);
    const done = (await run('POST', '/v1/account/mfa/email/confirm', { code: '246810' })).json as { recoveryCodes: string[] };
    expect(done.recoveryCodes).toHaveLength(10);
    expect(((await run('GET', '/v1/account/security')).json as AccountSecurity).mfa).toMatchObject({ emailOtp: true, setupSuggested: false });
    expect((await run('DELETE', '/v1/account/mfa/email', { currentPassword: 'x' })).status).toBe(204);
    expect((await run('POST', '/v1/account/mfa/prompt/dismiss')).status).toBe(204);
    expect(((await run('GET', '/v1/account/security')).json as AccountSecurity).mfa.setupSuggested).toBe(false);
  });
});
