import { getSellerDb, resetSellerDb } from '@afrotransact/api-client/mocks/seller';
import { configure, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it } from 'vitest';

import { BillingView } from '@/components/money/BillingView';
import { PayoutsView } from '@/components/money/PayoutsView';
import { OnboardingWizard } from '@/components/onboarding/OnboardingView';

import { asRole, mockApi } from './mock-api';
import { renderScreen } from './render';
import { nav } from './setup';

describe('Plan & billing (S292–S297)', () => {
  beforeEach(() => {
    resetSellerDb(new Date());
    asRole(null);
  });

  it('shows the plan, credit balance, rewards with progress, rewards received and invoices with PDFs', async () => {
    await renderScreen(<BillingView />);
    expect(await screen.findByText('Seller Growth', { selector: 'strong.text-2xl' })).toBeInTheDocument();
    expect(screen.getByText('Quarterly · plan version 1')).toBeInTheDocument();
    expect(within(screen.getByTestId('credit-balance')).getByText('$25.00')).toBeInTheDocument();
    const bar = await screen.findByRole('progressbar', { name: 'First $1,000 in sales' });
    expect(bar).toHaveAttribute('aria-valuenow', '64');
    expect(bar).toHaveAttribute('aria-valuetext', '$640 of $1,000 in sales');
    expect(screen.getByText('1 month free')).toBeInTheDocument();
    const earned = screen.getByRole('table', { name: 'Rewards and credits received' });
    expect(await within(earned).findByText('$25 credit')).toBeInTheDocument();
    const invoices = screen.getByRole('table', { name: 'Invoices' });
    expect(within(invoices).getAllByRole('link', { name: 'Download PDF' })[0]).toHaveAttribute('href', 'https://invoices.afrotransact.dev/in_3.pdf');
  });

  it('previews a plan change with Stripe’s proration before confirming it', async () => {
    const user = userEvent.setup();
    await renderScreen(<BillingView />);
    await user.click(await screen.findByRole('button', { name: 'Choose Seller Pro' }));
    const dialog = await screen.findByRole('dialog', { name: 'Change to Seller Pro' });
    expect(await within(dialog).findByText(/This upgrade starts today/)).toBeInTheDocument();
    expect(within(dialog).getByTestId('due-now').textContent).not.toBe('$0.00');
    await user.click(within(dialog).getByRole('button', { name: 'Confirm change' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(getSellerDb().subscription.SELLER.planCode).toBe('seller_pro');

    await user.click(screen.getByRole('button', { name: 'Choose Seller Essentials' }));
    expect(await screen.findByText(/takes effect at your next renewal/)).toBeInTheDocument();
    expect(screen.getByTestId('due-now')).toHaveTextContent('$0.00');
  });

  it('adds a card through the SetupIntent (local stand-in without a Stripe key) and makes it the default', async () => {
    const user = userEvent.setup();
    await renderScreen(<BillingView />);
    await user.click(await screen.findByRole('button', { name: 'Add payment method' }));
    const dialog = await screen.findByRole('dialog', { name: 'Add a payment method' });
    const local = await within(dialog).findByRole('group', { name: 'Local test card' });
    await user.type(within(local).getByLabelText('Card number'), '4000 0000 0000 0001');
    await user.type(within(local).getByLabelText('Expiry'), '0430');
    await user.click(within(dialog).getByRole('button', { name: 'Save payment method' }));
    expect(await within(dialog).findByRole('alert')).toHaveTextContent('Card number is invalid');
    await user.clear(within(local).getByLabelText('Card number'));
    await user.type(within(local).getByLabelText('Card number'), '4242 4242 4242 4242');
    await user.click(within(dialog).getByRole('button', { name: 'Save payment method' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    const added = getSellerDb().paymentMethods.SELLER.find((m) => m.id === 'pm_local_visa_4242');
    expect(added).toMatchObject({ isDefault: true, expMonth: 4, expYear: 2030 });
  });

  it('is read-only for finance: no plan changes or new payment methods', async () => {
    asRole('seller.finance');
    await renderScreen(<BillingView />);
    expect(await screen.findByText('Only the owner can change billing.')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /^Choose / })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Add payment method' })).not.toBeInTheDocument();
    expect(await screen.findByRole('progressbar', { name: 'First $1,000 in sales' })).toBeInTheDocument();
  });
});

describe('onboarding Plan step: promo code (S295)', () => {
  // The wizard saves through the in-process mock BFF: give slow CI machines room.
  configure({ asyncUtilTimeout: 5000 });

  beforeEach(async () => {
    resetSellerDb(new Date());
    asRole(null);
    nav.pathname = '/onboarding/plan';
    nav.push.mockReset();
    getSellerDb().orgs = [];
    const api = mockApi();
    await api.startOnboarding('SELLER');
    await api.saveOnboardingBusiness({ offering: 'PRODUCTS', entityType: 'SOLE_PROPRIETOR', country: 'US', storeName: 'Titi Foods', contactEmail: 't@titi.test', phone: '+17132490142' });
    await api.saveOnboardingDetails({ legalFirstName: 'Titi', legalLastName: 'A', taxId: '123-45-6789', dateOfBirth: '1990-01-01',
      address: { line1: '1 Main St', city: 'Houston', state: 'TX', postalCode: '77002' } });
    await api.createOnboardingStripeSession();
    await api.refreshOnboardingStripe();
  });

  it('refuses a code the plan doesn’t take and lengthens the trial with a valid one', { timeout: 20_000 }, async () => {
    const user = userEvent.setup();
    await renderScreen(<OnboardingWizard slug="plan" />);
    await user.click(await screen.findByRole('radio', { name: /Seller Growth/ }));
    await user.type(screen.getByLabelText('Card number'), '4242 4242 4242 4242');
    await user.type(screen.getByLabelText('Expiry'), '0430');
    await user.type(screen.getByLabelText('Security code'), '123');
    await user.type(screen.getByLabelText('Promo code (optional)'), 'nope1');
    await user.click(screen.getByRole('button', { name: 'Save and continue' }));
    expect(await screen.findAllByText('This code isn’t valid for this plan')).not.toHaveLength(0);
    const promo = screen.getByLabelText('Promo code (optional)');
    await user.clear(promo);
    await user.type(promo, 'grow60');
    expect(promo).toHaveValue('GROW60');
    await user.click(screen.getByRole('button', { name: 'Save and continue' }));
    await waitFor(() => expect(nav.push).toHaveBeenCalledWith('/onboarding/catalog-compliance'));
    const status = await mockApi().getOnboarding();
    expect(status.form.plan.firstCharge?.sentence).toMatch(/^Free for 60 days/);
  });
});

describe('how the plan is paid (S298)', () => {
  configure({ asyncUtilTimeout: 5000 });

  beforeEach(() => {
    resetSellerDb(new Date());
    asRole(null);
  });

  it('lets the owner switch between paying from sales and the card, applied from the next invoice', async () => {
    const user = userEvent.setup();
    await renderScreen(<BillingView />);
    const card = await screen.findByTestId('plan-fee-collection');
    const fromSales = within(card).getByRole('radio', { name: /Pay your plan from your sales/ });
    const byCard = within(card).getByRole('radio', { name: /Charge my card/ });
    expect(fromSales).toHaveAttribute('aria-checked', 'true');
    expect(within(card).getByText(/separate from your payouts/)).toBeInTheDocument();
    expect(within(card).getByText(/^Changes apply from your next invoice, on /)).toBeInTheDocument();
    await user.click(byCard);
    await waitFor(() => expect(getSellerDb().planFeeCollection.SELLER).toBe('CARD'));
    expect(await screen.findByText('Saved. It applies from your next invoice.')).toBeInTheDocument();
    await waitFor(() => expect(byCard).toHaveAttribute('aria-checked', 'true'));
  });

  it('shows on each invoice what sales and the card paid', async () => {
    await renderScreen(<BillingView />);
    const invoices = await screen.findByRole('table', { name: 'Invoices' });
    expect(await within(invoices).findByText('Paid from your sales: $90.00 · Card: $21.15')).toBeInTheDocument();
    expect(within(invoices).getAllByText('Card: $111.15').length).toBeGreaterThan(0);
  });

  it('lets finance change it but not other roles, and hides it when the platform doesn’t offer it', async () => {
    const user = userEvent.setup();
    asRole('seller.finance');
    const { unmount } = await renderScreen(<BillingView />);
    await user.click(within(await screen.findByTestId('plan-fee-collection')).getByRole('radio', { name: /Charge my card/ }));
    await waitFor(() => expect(getSellerDb().planFeeCollection.SELLER).toBe('CARD'));
    unmount();
    getSellerDb().planFeeCollectionAvailable = false;
    asRole(null);
    await renderScreen(<BillingView />);
    expect(await screen.findByText('Seller Growth', { selector: 'strong.text-2xl' })).toBeInTheDocument();
    expect(screen.queryByTestId('plan-fee-collection')).not.toBeInTheDocument();
  });

  it('lists the plan fees deducted from settlements on Payouts', async () => {
    await renderScreen(<PayoutsView />);
    const list = await screen.findByRole('list', { name: 'Plan fees paid from your sales' });
    expect(within(list).getByText('Plan fee (invoice AT-1003)')).toBeInTheDocument();
    expect(within(list).getByText('Deducted from order AT-2026-400112')).toBeInTheDocument();
    expect(within(list).getAllByText('-$90.00').length).toBeGreaterThan(0);
  });
});

describe('onboarding Plan step: how the plan is paid (S298)', () => {
  configure({ asyncUtilTimeout: 5000 });

  beforeEach(async () => {
    resetSellerDb(new Date());
    asRole(null);
    nav.pathname = '/onboarding/plan';
    nav.push.mockReset();
    getSellerDb().orgs = [];
    const api = mockApi();
    await api.startOnboarding('SELLER');
    await api.saveOnboardingBusiness({ offering: 'PRODUCTS', entityType: 'SOLE_PROPRIETOR', country: 'US', storeName: 'Titi Foods', contactEmail: 't@titi.test', phone: '+17132490142' });
    await api.saveOnboardingDetails({ legalFirstName: 'Titi', legalLastName: 'A', taxId: '123-45-6789', dateOfBirth: '1990-01-01',
      address: { line1: '1 Main St', city: 'Houston', state: 'TX', postalCode: '77002' } });
    await api.createOnboardingStripeSession();
    await api.refreshOnboardingStripe();
  });

  it('offers paying from sales (the platform default) or the card, and saves the choice', { timeout: 20_000 }, async () => {
    const user = userEvent.setup();
    await renderScreen(<OnboardingWizard slug="plan" />);
    await user.click(await screen.findByRole('radio', { name: /Seller Growth/ }));
    const choice = await screen.findByRole('radiogroup', { name: 'How you pay your plan' });
    expect(within(choice).getByRole('radio', { name: /Pay your plan from your sales.*Default/ })).toHaveAttribute('aria-checked', 'true');
    await user.click(within(choice).getByRole('radio', { name: /Charge my card/ }));
    await user.type(screen.getByLabelText('Card number'), '4242 4242 4242 4242');
    await user.type(screen.getByLabelText('Expiry'), '0430');
    await user.type(screen.getByLabelText('Security code'), '123');
    await user.click(screen.getByRole('button', { name: 'Save and continue' }));
    await waitFor(() => expect(nav.push).toHaveBeenCalledWith('/onboarding/catalog-compliance'));
    const status = await mockApi().getOnboarding();
    expect(status.form.plan).toMatchObject({ planFeeCollection: 'CARD', cardLast4: '4242' });
  });
});
