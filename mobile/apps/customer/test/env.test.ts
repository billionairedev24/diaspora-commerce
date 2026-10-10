import { describe, expect, it } from 'vitest';

import { resolveEnv } from '../src/config/env';

describe('env', () => {
  it('uses per-environment defaults and overrides', () => {
    const local = resolveEnv({});
    expect(local.appEnv).toBe('local');
    expect(local.mocks).toBe(true);
    expect(local.oidcClientId).toBe('customer-app');
    const uat = resolveEnv({ appEnv: 'uat', bffUrl: 'https://bff.example' });
    expect(uat.bffUrl).toBe('https://bff.example');
    expect(uat.oidcIssuer).toBe('https://auth.uat.afrotransact.com');
    expect(uat.mocks).toBe(false);
    expect(resolveEnv({ appEnv: 'prod', mocks: '1' }).mocks).toBe(false);
  });
});
