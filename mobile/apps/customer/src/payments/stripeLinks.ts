/* Where Stripe sends the shopper back after a bank (3-D Secure) or wallet hand-off. Our builds own the
   com.afrotransact.customer:// scheme; Expo Go only answers its own exp://<host>:<port>/--/… links, so there the
   return URL is built from the running Metro address (expo-linking). Wallets (Apple Pay / Google Pay) need the
   merchant set-up of our own build and are left out of the PaymentSheet in Expo Go: cards still work there. */
import * as Linking from 'expo-linking';

import { env } from '@/config/env';
import { capabilities, isExpoGo } from '@/platform/runtime';

/** StripeProvider urlScheme: the prefix of links Stripe should treat as returns to this app. */
export function stripeUrlScheme(): string {
  return isExpoGo() ? Linking.createURL('') : env.redirectScheme;
}

/** returnURL for PaymentSheet / handleNextAction. */
export function stripeReturnUrl(): string {
  return isExpoGo() ? Linking.createURL('stripe-redirect') : `${env.redirectScheme}://stripe-redirect`;
}

export const walletPayAvailable = capabilities.walletPay;
