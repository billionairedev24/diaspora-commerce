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
    expect(mfa).toMatchObject({ totp: true, sms: true, required: true, mayDisable: false, mayRemoveTotp: true, mayRemoveSms: true });
    expect((await call('DELETE', '/v1/account/mfa/sms')).status).toBe(204);
    expect((await call('DELETE', '/v1/account/mfa/totp')).status).toBe(403);
  });
});
