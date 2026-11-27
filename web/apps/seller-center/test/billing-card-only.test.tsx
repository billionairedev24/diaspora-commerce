import { getSellerDb, resetSellerDb } from '@afrotransact/api-client/mocks/seller';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { BillingView } from '@/components/money/BillingView';

import { asRole } from './mock-api';
import { renderScreen } from './render';

// Plan fees are paid by card (or from sales), never by bank debit: the live Payment Element is limited to cards, and a
// non-card method Stripe returns is refused before the SetupIntent is confirmed or anything is registered.
const stripe = vi.hoisted(() => {
  const seen: { elementsOptions: Record<string, unknown>[]; confirmed: number } = { elementsOptions: [], confirmed: 0 };
  const instance = {
    elements(options: Record<string, unknown>) {
      seen.elementsOptions.push(options);
      return {
        create: () => ({ mount: () => undefined, destroy: () => undefined, on: () => undefined }),
        submit: () => Promise.resolve({})
      };
    },
    createPaymentMethod: () => Promise.resolve({ paymentMethod: { id: 'pm_bank_1', type: 'us_bank_account' } }),
    confirmSetup: () => {
      seen.confirmed += 1;
      return Promise.resolve({});
    }
  };
  return { seen, instance };
});

vi.mock('@/lib/stripe', async (orig) => ({
  ...(await orig<Record<string, unknown>>()),
  getStripePayments: vi.fn(() => Promise.resolve(stripe.instance))
}));

vi.mock('@/lib/api', async () => {
  const { mockApi } = await import('./mock-api');
  return {
    sellerApi: vi.fn(() =>
      Object.assign(mockApi(), {
        createBillingSetupIntent: () => Promise.resolve({ setupIntentId: 'seti_1', clientSecret: 'seti_1_secret_x', publishableKey: 'pk_test_live_key', localTest: false })
      })
    )
  };
});

describe('Plan & billing: card only', () => {
  beforeEach(() => {
    resetSellerDb(new Date());
    asRole(null);
    stripe.seen.elementsOptions.length = 0;
    stripe.seen.confirmed = 0;
  });

  it('offers only cards in the Payment Element and refuses a bank account', async () => {
    const user = userEvent.setup();
    await renderScreen(<BillingView />);
    await user.click(await screen.findByRole('button', { name: 'Add payment method' }));
    const dialog = await screen.findByRole('dialog', { name: 'Add a payment method' });
    await waitFor(() => expect(stripe.seen.elementsOptions).toHaveLength(1));
    expect(stripe.seen.elementsOptions[0]).toMatchObject({ mode: 'setup', paymentMethodTypes: ['card'] });

    const before = getSellerDb().paymentMethods.SELLER.length;
    await user.click(within(dialog).getByRole('button', { name: 'Save payment method' }));
    expect(await within(dialog).findByRole('alert')).toHaveTextContent('Plan fees are paid by card. Add a card.');
    expect(stripe.seen.confirmed).toBe(0);
    expect(getSellerDb().paymentMethods.SELLER).toHaveLength(before);
  });
});
