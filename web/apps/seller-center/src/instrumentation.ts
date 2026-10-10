// Server telemetry (S178): off unless SENTRY_DSN (or NEXT_PUBLIC_SENTRY_DSN) is set; see @afrotransact/telemetry.
import { registerServerTelemetry } from '@afrotransact/telemetry/next/server';

export async function register(): Promise<void> {
  await registerServerTelemetry('seller-center');
}

export { onRequestError } from '@afrotransact/telemetry/next/server';
