import { describe, expect, it } from 'vitest';

import { resolveEnv } from '../src/config/env';

describe('driver env', () => {
  it('resolves per-environment defaults', () => {
    expect(resolveEnv({}).fulfilmentUrl).toBe('http://localhost:8108');
    expect(resolveEnv({}).oidcClientId).toBe('driver-app');
    expect(resolveEnv({ appEnv: 'prod', mocks: '1' }).mocks).toBe(false);
    expect(resolveEnv({ appEnv: 'dev', fulfilmentUrl: 'https://f.example' }).fulfilmentUrl).toBe('https://f.example');
  });
});
