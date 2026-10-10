/* Native payments with @stripe/stripe-react-native (S165). Dev builds with EXPO_PUBLIC_MOCKS=1 use the mock. */
import { AddressCollectionMode, CaptureMethod, type IntentCreationCallbackParams, useStripe } from '@stripe/stripe-react-native';
import { useMemo } from 'react';

import { type OrderConfirmation } from '@/api/types';
import { env } from '@/config/env';

import { mockPayments } from './mockPayments';
import { type Payments, type PayResult } from './types';

const RETURN_URL = `${env.redirectScheme}://stripe-redirect`;
const COMMON = {
  merchantDisplayName: 'AfroTransact',
  returnURL: RETURN_URL,
  allowsDelayedPaymentMethods: true,
  billingDetailsCollectionConfiguration: { address: AddressCollectionMode.AUTOMATIC },
  applePay: { merchantCountryCode: 'US' },
  googlePay: { merchantCountryCode: 'US', currencyCode: 'USD', testEnv: env.appEnv !== 'prod' }
};

export function usePayments(): Payments {
  const stripe = useStripe();
  return useMemo<Payments>(() => {
    if (env.mocks) return mockPayments;
    return {
      mode: 'stripe',
      async addPaymentMethod(setupIntentClientSecret) {
        const init = await stripe.initPaymentSheet({ ...COMMON, setupIntentClientSecret });
        if (init.error) return { error: init.error.localizedMessage ?? init.error.message };
        const r = await stripe.presentPaymentSheet();
        if (r.error) return r.error.code === 'Canceled' ? 'cancelled' : { error: r.error.localizedMessage ?? r.error.message };
        return 'added';
      },
      async payWithSheet({ amountMinor, currency, placeOrder }): Promise<PayResult> {
        let placed: OrderConfirmation | null = null;
        const init = await stripe.initPaymentSheet({
          ...COMMON,
          intentConfiguration: {
            // The order saga authorises now and captures per shipment when it ships.
            mode: { amount: amountMinor, currencyCode: currency.toUpperCase(), captureMethod: CaptureMethod.Manual },
            confirmHandler: (paymentMethod, _save, intentCreationCallback: (r: IntentCreationCallbackParams) => void) => {
              placeOrder(paymentMethod.id)
                .then((c) => {
                  placed = c;
                  if (c.paymentClientSecret) intentCreationCallback({ clientSecret: c.paymentClientSecret });
                  else intentCreationCallback({ error: { code: 'Failed', message: 'The order was placed without a payment to confirm.', localizedMessage: 'Something went wrong. Check Your orders before trying again.' } });
                })
                .catch((e: unknown) => {
                  const message = e instanceof Error ? e.message : 'Payment failed';
                  intentCreationCallback({ error: { code: 'Failed', message, localizedMessage: message } });
                });
            }
          }
        });
        if (init.error) return { status: 'failed', message: init.error.localizedMessage ?? init.error.message };
        const r = await stripe.presentPaymentSheet();
        if (r.error) return r.error.code === 'Canceled' && !placed ? { status: 'cancelled' } : { status: 'failed', message: r.error.localizedMessage ?? r.error.message };
        return placed ? { status: 'placed', confirmation: placed } : { status: 'failed', message: 'Payment finished without an order.' };
      },
      async handleNextAction(paymentClientSecret) {
        const r = await stripe.handleNextAction(paymentClientSecret, RETURN_URL);
        return r.error ? { ok: false, message: r.error.localizedMessage ?? r.error.message } : { ok: true };
      },
      async payDeposit(paymentIntentClientSecret) {
        const init = await stripe.initPaymentSheet({ ...COMMON, paymentIntentClientSecret });
        if (init.error) return { error: init.error.localizedMessage ?? init.error.message };
        const r = await stripe.presentPaymentSheet();
        if (r.error) return r.error.code === 'Canceled' ? 'cancelled' : { error: r.error.localizedMessage ?? r.error.message };
        return 'paid';
      }
    };
  }, [stripe]);
}
