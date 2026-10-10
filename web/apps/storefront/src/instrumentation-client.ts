// Browser telemetry (S178): off unless NEXT_PUBLIC_SENTRY_DSN is set for this environment; see @afrotransact/telemetry.
import { initBrowserTelemetry, onRouterTransitionStart } from '@afrotransact/telemetry/next/client';

initBrowserTelemetry('storefront');

export { onRouterTransitionStart };
