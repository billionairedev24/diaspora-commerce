import { getStaffDb, resetStaffDb } from '@afrotransact/api-client/mocks/staff';
import { configure, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it } from 'vitest';

import { CataloguePanel } from '@/components/plans/CataloguePanel';
import { RewardsPanel } from '@/components/plans/RewardsPanel';
import { SellerBillingPanel } from '@/components/plans/SellerBillingPanel';
import { SettingsPanel } from '@/components/plans/SettingsPanel';
import { TrialsPanel } from '@/components/plans/TrialsPanel';

import { asRoles } from './mock-api';
import { renderScreen } from './render';

configure({ asyncUtilTimeout: 5000 });

describe('Plans & billing (S292–S297)', { timeout: 30_000 }, () => {
  beforeEach(() => {
    resetStaffDb(new Date('2026-10-10T15:00:00Z'));
    asRoles('platform.finance');
  });

  it('lists the catalogue with Stripe sync status, and versions a plan: new prices are a draft until published', async () => {
    const user = userEvent.setup();
    await renderScreen(<CataloguePanel />);
    const table = await screen.findByRole('table', { name: 'Plans' });
    const growth = await within(table).findByRole('row', { name: /Seller Growth/ });
    expect(within(growth).getByText('$39.00')).toBeInTheDocument();
    expect(await screen.findByText(/Every price matches Stripe/)).toBeInTheDocument();
    await user.click(within(growth).getByRole('button', { name: 'New prices or trial' }));
    const dialog = await screen.findByRole('dialog', { name: 'New prices or trial for Seller Growth' });
    const monthly = within(dialog).getByLabelText('Monthly ($)');
    await user.clear(monthly);
    await user.type(monthly, '45');
    await user.click(within(dialog).getByRole('button', { name: 'Save and publish' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    const plan = getStaffDb().plans.plans.find((p) => p.code === 'seller_growth');
    expect(plan).toMatchObject({ currentVersion: 2, monthlyCents: 4500 });
    expect(await within(table).findByText('$45.00')).toBeInTheDocument();
    expect(within(await within(table).findByRole('row', { name: /Seller Growth/ })).getByRole('button', { name: 'Migrate subscribers' })).toBeInTheDocument();
  });

  it('creates a promo-code trial campaign', async () => {
    const user = userEvent.setup();
    await renderScreen(<TrialsPanel />);
    await user.click(await screen.findByRole('button', { name: 'New campaign' }));
    const dialog = await screen.findByRole('dialog', { name: 'New campaign' });
    await user.type(within(dialog).getByLabelText('Name'), 'Pro 45');
    await user.type(within(dialog).getByLabelText('Promo code'), 'pro45');
    const days = within(dialog).getByLabelText('Trial days');
    await user.clear(days);
    await user.type(days, '45');
    await user.click(within(dialog).getByRole('checkbox', { name: 'Seller Pro' }));
    await user.click(within(dialog).getByRole('button', { name: 'Save' }));
    const table = screen.getByRole('table', { name: 'Trials & promotions' });
    expect(await within(table).findByText('PRO45')).toBeInTheDocument();
  });

  it('previews who qualifies for an unsaved reward rule, then saves it', async () => {
    const user = userEvent.setup();
    await renderScreen(<RewardsPanel />);
    await user.click(await screen.findByRole('button', { name: 'New reward rule' }));
    const dialog = await screen.findByRole('dialog', { name: 'New reward rule' });
    await user.type(within(dialog).getByLabelText('Name'), 'Ten orders');
    await user.selectOptions(within(dialog).getByLabelText('Metric'), 'COMPLETED_ORDERS');
    await user.type(within(dialog).getByLabelText('Threshold'), '10');
    await user.selectOptions(within(dialog).getByLabelText('Reward'), 'CREDIT');
    await user.type(within(dialog).getByLabelText('Credit ($)'), '25');
    await user.click(within(dialog).getByRole('button', { name: 'Check now' }));
    expect(await within(dialog).findByText('2 of 3 organisations qualify now')).toBeInTheDocument();
    await user.click(within(dialog).getByRole('button', { name: 'Save and activate' }));
    const table = screen.getByRole('table', { name: 'Rewards' });
    const row = await within(table).findByRole('row', { name: /Ten orders/ });
    expect(within(row).getByText('$25 credit')).toBeInTheDocument();
    expect(within(row).getByText('Active')).toBeInTheDocument();
  });

  it('finds a seller, credits it with a reason code and waives an open invoice', async () => {
    const user = userEvent.setup();
    await renderScreen(<SellerBillingPanel />);
    await user.type(screen.getByRole('searchbox'), 'ade');
    await user.click(await screen.findByRole('button', { name: /Ade’s Kitchen/ }));
    expect(await screen.findByTestId('panel-credit')).toHaveTextContent('$0.00');
    await user.click(screen.getByRole('button', { name: 'Add credit' }));
    const dialog = await screen.findByRole('dialog', { name: 'Add credit' });
    await user.type(within(dialog).getByLabelText('Credit ($)'), '25');
    await user.click(within(dialog).getByRole('button', { name: 'Apply' }));
    // The placeholder and the field error say the same thing.
    await waitFor(() => expect(within(dialog).getAllByText('Choose a reason')).toHaveLength(2));
    await waitFor(() => expect(within(dialog).getAllByRole('option').length).toBeGreaterThan(2));
    await user.selectOptions(within(dialog).getByLabelText('Reason'), 'GOODWILL');
    await user.click(within(dialog).getByRole('button', { name: 'Apply' }));
    await waitFor(() => expect(screen.getByTestId('panel-credit')).toHaveTextContent('$25.00'));
    expect(getStaffDb().plans.grants[0]).toMatchObject({ type: 'CREDIT', reasonCode: 'GOODWILL', creditCents: 2500 });

    const invoices = screen.getByRole('table', { name: 'Invoices' });
    await user.click(within(invoices).getByRole('button', { name: 'Waive' }));
    const waive = await screen.findByRole('dialog', { name: /Waive invoice/ });
    await user.selectOptions(within(waive).getByLabelText('Reason'), 'BILLING_ERROR');
    await user.click(within(waive).getByRole('button', { name: 'Apply' }));
    await waitFor(() => expect(within(invoices).queryByRole('button', { name: 'Waive' })).not.toBeInTheDocument());
    expect(within(screen.getByRole('table', { name: 'Rewards and adjustments' })).getAllByRole('row').length).toBeGreaterThan(2);
  });

  it('overrides how a seller pays its plan with a reason, and shows the invoices paid from earnings (S298)', async () => {
    const user = userEvent.setup();
    await renderScreen(<SellerBillingPanel />);
    await user.type(screen.getByRole('searchbox'), 'ade');
    await user.click(await screen.findByRole('button', { name: /Ade’s Kitchen/ }));
    const fees = await screen.findByTestId('panel-plan-fees');
    expect(within(fees).getByText('From sales first, card for the rest')).toBeInTheDocument();
    expect(within(fees).getByText(/Platform default/)).toBeInTheDocument();
    expect(within(fees).getByText(/^A change applies from the invoice of /)).toBeInTheDocument();
    const collections = screen.getByRole('table', { name: 'Invoices paid from earnings' });
    expect(within(collections).getByRole('row', { name: /AT-ADES1/ })).toHaveTextContent('$25.00');
    expect(within(screen.getByRole('table', { name: 'Invoices' })).getByRole('row', { name: /AT-ADES1/ })).toHaveTextContent('$14.00');

    await user.click(within(fees).getByRole('button', { name: 'Change' }));
    const dialog = await screen.findByRole('dialog', { name: 'How Ade’s Kitchen pays its plan' });
    expect(within(dialog).getByLabelText('Plan fees')).toHaveValue('CARD');
    await user.click(within(dialog).getByRole('button', { name: 'Apply' }));
    await waitFor(() => expect(within(dialog).getAllByText('Choose a reason')).toHaveLength(2));
    await waitFor(() => expect(within(dialog).getAllByRole('option').length).toBeGreaterThan(3));
    await user.selectOptions(within(dialog).getByLabelText('Reason'), 'SELLER_REQUEST');
    await user.type(within(dialog).getByLabelText('Note (optional)'), 'Books plan fees as a card expense');
    await user.click(within(dialog).getByRole('button', { name: 'Apply' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    await waitFor(() => expect(screen.getByTestId('panel-plan-fees')).toHaveTextContent('Card only'));
    expect(screen.getByTestId('panel-plan-fees')).toHaveTextContent('Set by staff');
    expect(within(screen.getByRole('list', { name: 'Changes' })).getByText(/Books plan fees as a card expense/)).toBeInTheDocument();
    expect(getStaffDb().plans.orgs[0]).toMatchObject({ planFeeCollection: 'CARD', planFeeSource: 'STAFF' });
  });

  it('sets the plan fee default for new sellers in Settings; the kill switch is read-only (S298)', async () => {
    const user = userEvent.setup();
    await renderScreen(<SettingsPanel />);
    expect(await screen.findByTestId('kill-switch')).toHaveTextContent('On: sellers may pay their plan from their sales');
    const select = screen.getByLabelText('Default for new sellers');
    expect(select).toHaveValue('EARNINGS_FIRST');
    await user.selectOptions(select, 'CARD');
    await user.click(screen.getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(getStaffDb().plans.settings.planFeeCollectionDefault).toBe('CARD'));
    expect(await screen.findByText('Default saved')).toBeInTheDocument();
  });

  it('is read-only without plans:manage or billing:adjust', async () => {
    asRoles('platform.auditor');
    await renderScreen(<CataloguePanel />);
    await within(await screen.findByRole('table', { name: 'Plans' })).findByRole('row', { name: /Seller Growth/ });
    expect(screen.queryByRole('button', { name: 'New plan' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Sync with Stripe' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Retire' })).not.toBeInTheDocument();
  });

  it('shows the plan fee settings read-only to an auditor (S298)', async () => {
    asRoles('platform.auditor');
    await renderScreen(<SettingsPanel />);
    expect(await screen.findByLabelText('Default for new sellers')).toBeDisabled();
    expect(screen.queryByRole('button', { name: 'Save' })).not.toBeInTheDocument();
  });
});
