import { StripeProvider } from '@stripe/stripe-react-native';
import { type ReactNode } from 'react';

import { env } from '@/config/env';

import { stripeUrlScheme } from './stripeLinks';

/** Initialises the native Stripe SDK (Apple Pay merchant id, 3-D Secure return scheme). Skipped on dev mocks. Stripe's
    native SDK ships in Expo Go too (same version as bundled with the SDK), so card payments work there. */
export function PaymentsProvider({ children }: { children: ReactNode }) {
  if (env.mocks) return <>{children}</>;
  return (
    <StripeProvider publishableKey={env.stripePublishableKey} merchantIdentifier={env.stripeMerchantId} urlScheme={stripeUrlScheme()}>
      <>{children}</>
    </StripeProvider>
  );
}
