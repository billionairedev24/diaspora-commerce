/* Dev mock of the Stripe SDK: no network, no native module. The "sheet" succeeds after a short delay. */
import { type Payments } from './types';

const wait = (ms: number) => new Promise((r) => setTimeout(r, ms));

export const mockPayments: Payments = {
  mode: 'mock',
  addPaymentMethod: async () => 'added',
  payWithSheet: async ({ placeOrder }) => {
    await wait(400);
    try {
      return { status: 'placed', confirmation: await placeOrder('pm_mock_sheet') };
    } catch (e) {
      return { status: 'failed', message: e instanceof Error ? e.message : 'Payment failed' };
    }
  },
  handleNextAction: async () => ({ ok: true }),
  payDeposit: async () => {
    await wait(400);
    return 'paid';
  }
};
