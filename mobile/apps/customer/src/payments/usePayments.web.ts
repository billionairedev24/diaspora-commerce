/* Web preview: the Stripe React Native SDK has no web build, so payments always use the dev mock. */
import { mockPayments } from './mockPayments';
import { type Payments } from './types';

export function usePayments(): Payments {
  return mockPayments;
}
