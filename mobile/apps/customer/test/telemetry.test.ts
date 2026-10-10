import { resolveTelemetryConfig } from '@afrotransact/telemetry';
import { describe, expect, it } from 'vitest';

describe('mobile telemetry config', () => {
  it('is off without EXPO_PUBLIC_SENTRY_DSN and samples per environment', () => {
    expect(resolveTelemetryConfig('app', { environment: 'prod' })).toMatchObject({ dsn: undefined, environment: 'prod', tracesSampleRate: 0.05 });
    expect(resolveTelemetryConfig('app', { dsn: 'https://k@o.example/2', environment: 'uat', release: '2.0.0+7' }).release).toBe('2.0.0+7');
  });
});
