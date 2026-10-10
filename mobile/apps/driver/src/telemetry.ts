/* Telemetry bootstrap: the only module that touches the Sentry SDK. Screens use getTelemetry() from
   @afrotransact/telemetry (captureException, addBreadcrumb, setUser with the opaque account id only). */
import { initNativeTelemetry } from '@afrotransact/telemetry/native';
import * as Sentry from '@sentry/react-native';

import { telemetryConfig } from '@/config/telemetry';

initNativeTelemetry(Sentry, telemetryConfig, {
  // Trace only our own APIs; never attach trace headers to third parties (Stripe, identity's hosted pages).
  tracePropagationTargets: [/^https:\/\/api\.(dev\.|uat\.)?afrotransact\.com\//]
});
