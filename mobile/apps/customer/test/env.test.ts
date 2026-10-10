import { describe, expect, it } from 'vitest';

import { localUrls, resolveEnv } from '../src/config/env';

describe('env', () => {
  it('falls back to the local stack, on the dev host when one is set', () => {
    const local = resolveEnv({});
    expect(local.appEnv).toBe('local');
    expect(local.mocks).toBe(true);
    expect(local.oidcClientId).toBe('customer-app');
    expect(local).toMatchObject(localUrls());
    expect(local.bffUrl).toBe('http://localhost:8200');
    expect(local.webUrl).toBe('http://localhost:3000');
    const lan = resolveEnv({ devHost: '192.168.1.20' });
    expect(lan.oidcIssuer).toBe('http://192.168.1.20:8100');
    expect(lan.bookingsUrl).toBe('http://192.168.1.20:8109');
  });

  it('uses the URLs the build profile sets', () => {
    const uat = resolveEnv({
      appEnv: 'uat',
      webUrl: 'https://shop.example.test/',
      bffUrl: 'https://api.example.test/bff',
      oidcIssuer: 'https://auth.example.test',
      identityApiUrl: 'https://api.example.test/identity',
      bookingsUrl: 'https://api.example.test/bookings'
    });
    expect(uat).toMatchObject({ webUrl: 'https://shop.example.test', bffUrl: 'https://api.example.test/bff', oidcIssuer: 'https://auth.example.test' });
    expect(uat.mocks).toBe(false);
    expect(resolveEnv({ appEnv: 'prod', mocks: '1' }).mocks).toBe(false);
  });
});
