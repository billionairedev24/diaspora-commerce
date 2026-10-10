/*
 * Server bootstrap, used by each app's src/instrumentation.ts:
 *
 *   import { registerServerTelemetry } from '@afrotransact/telemetry/next/server';
 *   export const register = () => registerServerTelemetry('storefront');
 *   export { onRequestError } from '@afrotransact/telemetry/next/server';
 *
 * Off unless SENTRY_DSN (or NEXT_PUBLIC_SENTRY_DSN) is set; the SDK is imported only then.
 */
import { setTelemetry } from '../index';
import { baseInitOptions, sentryTelemetry } from '../sdk';
import { serverTelemetryConfig } from './env';

type RequestErrorArgs = [error: unknown, request: { path: string; method: string; headers: Record<string, string | string[] | undefined> }, context: { routerKind: string; routePath: string; routeType: string; renderSource?: string; revalidateReason?: string; renderType?: string }];

let capture: ((...args: RequestErrorArgs) => void) | undefined;

export async function registerServerTelemetry(service: string): Promise<void> {
  const config = serverTelemetryConfig(service);
  if (!config.dsn) return;
  const Sentry = await import('@sentry/nextjs');
  Sentry.init({ ...baseInitOptions(config) });
  capture = Sentry.captureRequestError as (...args: RequestErrorArgs) => void;
  setTelemetry(sentryTelemetry(Sentry));
}

/** Next.js calls this for errors thrown while rendering, in route handlers and server actions. */
export function onRequestError(...args: RequestErrorArgs): void {
  capture?.(...args);
}
