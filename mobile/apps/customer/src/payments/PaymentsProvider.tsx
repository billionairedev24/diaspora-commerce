import { StripeProvider } from '@stripe/stripe-react-native';
import { type ReactNode } from 'react';

import { env } from '@/config/env';

/** Initialises the native Stripe SDK (Apple Pay merchant id, 3-D Secure return scheme). Skipped on dev mocks. */
export function PaymentsProvider({ children }: { children: ReactNode }) {
  if (env.mocks) return <>{children}</>;
  return (
    <StripeProvider publishableKey={env.stripePublishableKey} merchantIdentifier={env.stripeMerchantId} urlScheme={env.redirectScheme}>
      <>{children}</>
    </StripeProvider>
  );
}
