import { getSellerDb, resetSellerDb } from '@afrotransact/api-client/mocks/seller';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { BillingView } from '@/components/money/BillingView';

import { asRole } from './mock-api';
import { renderScreen } from './render';

// Plan fees by card, US bank debit (ACH) or from sales. A bank account is added through the Payment Element (Financial
// Connections, mandate) and registered with its SetupIntent; a bank debit shows as in progress for up to 4 business days;
// a charge the bank asks about (3-D Secure) is "Action needed" with Confirm payment.
const stripe = vi.hoisted(() => {
  const seen: { create: unknown[]; confirmed: number } = { create: [], confirmed: 0 };
  const instance = {
    elements: () => ({
      create: (_type: string, options: unknown) => {
        seen.create.push(options);
        return { mount: () => undefined, destroy: () => undefined, on: () => undefined };
      },
      submit: () => Promise.resolve({})
    }),
    createPaymentMethod: () =>
      Promise.resolve({ paymentMethod: { id: 'pm_bank_1', type: 'us_bank_account', us_bank_account: { bank_name: 'STRIPE TEST BANK', last4: '6789', account_type: 'checking' } } }),
    confirmSetup: () => {
      seen.confirmed += 1;
      return Promise.resolve({ setupIntent: { id: 'seti_1', status: 'succeeded' } });
    },
    confirmCardPayment: () => Promise.resolve({ paymentIntent: { id: 'pi_1', status: 'succeeded' } })
  };
  return { seen, instance };
});

vi.mock('@/lib/stripe', async (orig) => ({
  ...(await orig<Record<string, unknown>>()),
  getStripePayments: vi.fn(() => Promise.resolve(stripe.instance))
}));

const added = vi.hoisted(() => ({ bodies: [] as unknown[] }));

vi.mock('@/lib/api', async () => {
  const { mockApi } = await import('./mock-api');
  return {
    sellerApi: vi.fn(() => {
      const api = mockApi();
      return Object.assign(api, {
        createBillingSetupIntent: () => Promise.resolve({ setupIntentId: 'seti_1', clientSecret: 'seti_1_secret_x', publishableKey: 'pk_test_live_key', localTest: false }),
        addBillingPaymentMethod: (body: unknown) => {
          added.bodies.push(body);
          return Promise.resolve({ id: 'pm_bank_1', kind: 'bank', brand: null, last4: '6789', expMonth: null, expYear: null, bankName: 'STRIPE TEST BANK', accountType: 'checking',
            verified: true, receivesPayouts: false, isDefault: true, label: 'STRIPE TEST BANK ••6789' });
        }
      });
    })
  };
});

describe('Plan & billing: card, bank debit, 3-D Secure', () => {
  beforeEach(() => {
    resetSellerDb(new Date());
    asRole(null);
    stripe.seen.create.length = 0;
    stripe.seen.confirmed = 0;
    added.bodies.length = 0;
  });

  it('offers a card or a US bank account and registers the bank account with its SetupIntent', async () => {
    const user = userEvent.setup();
    await renderScreen(<BillingView />);
    await user.click(await screen.findByRole('button', { name: 'Add payment method' }));
    const dialog = await screen.findByRole('dialog', { name: 'Add a payment method' });
    await waitFor(() => expect(stripe.seen.create).toHaveLength(1));
    expect(stripe.seen.create[0]).toMatchObject({ paymentMethodOrder: ['card', 'us_bank_account'] });
    expect(within(dialog).getByText(/a debit takes up to 4 business days/)).toBeInTheDocument();
    await user.click(within(dialog).getByRole('button', { name: 'Save payment method' }));
    await waitFor(() => expect(added.bodies).toHaveLength(1));
    expect(added.bodies[0]).toMatchObject({ kind: 'US_BANK_ACCOUNT', last4: '6789', setupIntentId: 'seti_1' });
    expect(stripe.seen.confirmed).toBe(1);
  });

  it('shows a bank debit in progress for up to 4 business days', async () => {
    const inv = getSellerDb().invoices[0];
    if (!inv) throw new Error('seeded');
    Object.assign(inv, { status: 'OPEN', paidAt: null, amountPaidMinor: 0, paymentInProgress: true, lastError: 'Bank debit in progress (up to 4 business days)', canRetry: false });
    await renderScreen(<BillingView />);
    expect(await screen.findByText('Bank debit in progress (up to 4 business days)')).toBeInTheDocument();
    expect(screen.getByText(/We update the invoice when your bank confirms it/)).toBeInTheDocument();
    expect(within(screen.getByRole('table', { name: 'Invoices' })).getByText('Payment in progress')).toBeInTheDocument();
  });

  it('asks the seller to confirm a charge their bank needs to approve, and settles it', async () => {
    const user = userEvent.setup();
    const db = getSellerDb();
    const inv = db.invoices[0];
    if (!inv) throw new Error('seeded');
    Object.assign(inv, { status: 'OPEN', paidAt: null, amountPaidMinor: 0, actionRequired: true, canRetry: false, lastError: 'Action needed' });
    await renderScreen(<BillingView />);
    expect(await screen.findByText(`Your bank asks you to confirm invoice ${inv.number}`)).toBeInTheDocument();
    expect(within(screen.getByRole('table', { name: 'Invoices' })).getByText('Action needed')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Confirm payment' }));
    await waitFor(() => expect(db.invoices[0]?.status).toBe('PAID'));
    await waitFor(() => expect(screen.queryByText(`Your bank asks you to confirm invoice ${inv.number}`)).not.toBeInTheDocument());
  });
});
