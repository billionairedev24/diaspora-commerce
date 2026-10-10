import { describe, expect, it } from 'vitest';

import { localUrls, resolveEnv } from '../src/config/env';

describe('driver env', () => {
  it('falls back to the local stack, on the dev host when one is set', () => {
    expect(resolveEnv({}).fulfilmentUrl).toBe('http://localhost:8108');
    expect(resolveEnv({})).toMatchObject(localUrls());
    expect(resolveEnv({}).oidcClientId).toBe('driver-app');
    expect(resolveEnv({ devHost: '10.0.2.2' }).mediaUrl).toBe('http://10.0.2.2:8207');
  });

  it('uses the URLs the build profile sets', () => {
    expect(resolveEnv({ appEnv: 'prod', mocks: '1' }).mocks).toBe(false);
    expect(resolveEnv({ appEnv: 'dev', fulfilmentUrl: 'https://f.example/' }).fulfilmentUrl).toBe('https://f.example');
  });
});
