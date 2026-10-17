import { getStaffDb, resetStaffDb } from '@afrotransact/api-client/mocks/staff';
import { configure, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it } from 'vitest';

import { CataloguePanel } from '@/components/plans/CataloguePanel';
import { FeatureMatrixPanel } from '@/components/plans/FeatureMatrixPanel';
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

  it('lists the catalogue with what each plan allows, and versions a plan: new prices are a draft until published', async () => {
    const user = userEvent.setup();
    await renderScreen(<CataloguePanel />);
    const table = await screen.findByRole('table', { name: 'Plans' });
    const growth = await within(table).findByRole('row', { name: /Seller Growth/ });
    expect(within(growth).getByText('$39.00')).toBeInTheDocument();
    expect(within(growth).getByText('Up to 1,000 listings · Bulk upload · Image library · Coupons · AI listing writer · Lola Growth assistant')).toBeInTheDocument();
    await user.click(within(growth).getByRole('button', { name: 'More actions for Seller Growth' }));
    await user.click(await screen.findByRole('menuitem', { name: 'New prices or trial' }));
    const dialog = await screen.findByRole('dialog', { name: 'New prices or trial for Seller Growth' });
    const monthly = within(dialog).getByLabelText('Monthly ($)');
    await user.clear(monthly);
    await user.type(monthly, '45');
    await user.click(within(dialog).getByRole('button', { name: 'Save and publish' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    const plan = getStaffDb().plans.plans.find((p) => p.code === 'seller_growth');
    expect(plan).toMatchObject({ currentVersion: 2, monthlyCents: 4500 });
    expect(await within(table).findByText('$45.00')).toBeInTheDocument();
    await user.click(within(await within(table).findByRole('row', { name: /Seller Growth/ })).getByRole('button', { name: 'More actions for Seller Growth' }));
    expect(await screen.findByRole('menuitem', { name: 'Migrate subscribers' })).toBeInTheDocument();
    await user.keyboard('{Escape}');
  });

  it('shows a plan read only with View: prices, what it allows and its versions', async () => {
    const user = userEvent.setup();
    await renderScreen(<CataloguePanel />);
    const table = await screen.findByRole('table', { name: 'Plans' });
    await user.click(within(await within(table).findByRole('row', { name: /Seller Growth/ })).getByRole('button', { name: 'View' }));
    const dialog = await screen.findByRole('dialog', { name: /Seller Growth/ });
    expect(within(dialog).getByText('$39.00')).toBeInTheDocument();
    expect(within(dialog).getByText('Up to 1,000 listings · Bulk upload · Image library · Coupons · AI listing writer · Lola Growth assistant')).toBeInTheDocument();
    expect(within(dialog).getByText('v1')).toBeInTheDocument();
  });

  it('edits what a plan includes: the listing cap, bulk upload, the AI writer and the Lola tier', async () => {
    const user = userEvent.setup();
    await renderScreen(<CataloguePanel />);
    const table = await screen.findByRole('table', { name: 'Plans' });
    const essentials = await within(table).findByRole('row', { name: /Seller Essentials/ });
    expect(within(essentials).getByText('Up to 50 listings · Lola Essentials assistant')).toBeInTheDocument();
    await user.click(within(essentials).getByRole('button', { name: 'More actions for Seller Essentials' }));
    await user.click(await screen.findByRole('menuitem', { name: 'Edit' }));
    const dialog = await screen.findByRole('dialog', { name: /Edit Seller Essentials/ });
    const cap = within(dialog).getByLabelText('Live listings');
    await user.clear(cap);
    await user.type(cap, '120');
    await user.click(within(dialog).getByLabelText('AI listing writer'));
    await user.click(within(dialog).getByLabelText('Bulk upload of listings'));
    await user.selectOptions(within(dialog).getByLabelText('Lola (seller assistant)'), 'GROWTH');
    expect(within(dialog).getByText('Up to 120 listings · Bulk upload · AI listing writer · Lola Growth assistant')).toBeInTheDocument();
    await user.click(within(dialog).getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(getStaffDb().plans.plans.find((p) => p.code === 'seller_essentials')?.limits).toMatchObject({ listings: '120', aiListingWriter: 'true', lolaTier: 'GROWTH', bulkUpload: 'true', coupons: 'false' });
    expect(await within(table).findByText('Up to 120 listings · Bulk upload · AI listing writer · Lola Growth assistant')).toBeInTheDocument();
  });

  it('shows which plans unlock each feature', async () => {
    await renderScreen(<FeatureMatrixPanel />);
    const table = await screen.findByRole('table', { name: 'Seller plans' });
    const bulk = within(table).getByRole('row', { name: /Bulk upload/ });
    expect(within(bulk).getAllByLabelText('Included').length).toBeGreaterThan(0);
    expect(within(bulk).getAllByLabelText('Not included').length).toBeGreaterThan(0);
    expect(within(within(table).getByRole('row', { name: /Lola assistant/ })).getByText('Growth')).toBeInTheDocument();
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
    expect(await within(dialog).findByText('2 of 4 organisations qualify now')).toBeInTheDocument();
    await user.click(within(dialog).getByRole('button', { name: 'Save and activate' }));
    const table = screen.getByRole('table', { name: 'Rewards' });
    const row = await within(table).findByRole('row', { name: /Ten orders/ });
    expect(within(row).getByText('$25 credit')).toBeInTheDocument();
    expect(within(row).getByText('Active')).toBeInTheDocument();
  });

  it('builds a low-sales relief rule: less than $1,000 in the first 30 days of the subscription, checked before the trial ends (S299)', async () => {
    const user = userEvent.setup();
    await renderScreen(<RewardsPanel />);
    await user.click(await screen.findByRole('button', { name: 'New reward rule' }));
    const dialog = await screen.findByRole('dialog', { name: 'New reward rule' });
    await user.type(within(dialog).getByLabelText('Name'), 'Quiet start');
    await user.type(within(dialog).getByLabelText('Threshold ($ in sales)'), '1000');
    await user.selectOptions(within(dialog).getByLabelText('Kind'), 'SELLER');
    await user.selectOptions(within(dialog).getByLabelText('Comparison'), 'LESS_THAN');
    await user.selectOptions(within(dialog).getByLabelText('Window starts at'), 'SUBSCRIPTION_START');
    await user.type(within(dialog).getByLabelText('First N days of the subscription'), '30');
    expect(within(dialog).queryByLabelText('Decide once, when the window closes')).not.toBeInTheDocument();
    expect(within(dialog).getByLabelText('Check (hours before the window or trial ends)')).toHaveValue('24');
    await user.click(await within(dialog).findByLabelText('Seller Growth'));
    expect(within(dialog).getByTestId('rule-sentence')).toHaveTextContent(
      'Sellers on Seller Growth who sell less than $1,000 in the first 30 days of their subscription get 30 free days (checked 24 h before the trial ends).'
    );
    await user.click(within(dialog).getByRole('button', { name: 'Check now' }));
    expect(await within(dialog).findByText(/0 of 3 organisations qualify now · 1 still in their window/)).toBeInTheDocument();
    const preview = within(dialog).getByRole('table', { name: 'Who qualifies now' });
    const mama = await within(preview).findByRole('row', { name: /Mama Put/ });
    expect(within(mama).getByText('$320.00 so far (less than $1,000.00 qualifies)')).toBeInTheDocument();
    expect(within(mama).getByText(/Pending · checked/)).toBeInTheDocument();
    await user.click(within(dialog).getByRole('button', { name: 'Save and activate' }));
    const row = await within(screen.getByRole('table', { name: 'Rewards' })).findByRole('row', { name: /Quiet start/ });
    expect(within(row).getByText('less than $1,000 in sales in the first 30 days of the subscription')).toBeInTheDocument();
    expect(getStaffDb().plans.rules[0]).toMatchObject({ comparison: 'LESS_THAN', windowAnchor: 'SUBSCRIPTION_START', checkAtWindowEnd: true, checkLeadHours: 24 });
  });

  it('can ask an "at least" rule to wait for its window to close (S299)', async () => {
    const user = userEvent.setup();
    await renderScreen(<RewardsPanel />);
    await user.click(await screen.findByRole('button', { name: 'New reward rule' }));
    const dialog = await screen.findByRole('dialog', { name: 'New reward rule' });
    await user.type(within(dialog).getByLabelText('Threshold ($ in sales)'), '5000');
    await user.type(within(dialog).getByLabelText('Within N days of approval'), '60');
    expect(within(dialog).getByTestId('rule-sentence')).toHaveTextContent('Sellers and providers who sell at least $5,000 in the first 60 days after approval get 30 free days (as soon as they reach it).');
    await user.click(within(dialog).getByLabelText('Decide once, when the window closes'));
    expect(within(dialog).getByTestId('rule-sentence')).toHaveTextContent('(checked 24 h before the window ends).');
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

  it('gives free months and sets a charge condition with a reason, then removes it', async () => {
    const user = userEvent.setup();
    await renderScreen(<SellerBillingPanel />);
    await user.type(screen.getByRole('searchbox'), 'ade');
    await user.click(await screen.findByRole('button', { name: /Ade’s Kitchen/ }));
    expect(await screen.findByTestId('panel-entitlements')).toHaveTextContent('Up to 1,000 listings · Bulk upload · Image library · Coupons · AI listing writer · Lola Growth assistant');
    await user.click(screen.getByRole('button', { name: 'Give free months' }));
    const months = await screen.findByRole('dialog', { name: 'Give free months' });
    await user.type(within(months).getByLabelText('Free months'), '2');
    await waitFor(() => expect(within(months).getAllByRole('option').length).toBeGreaterThan(2));
    await user.selectOptions(within(months).getByLabelText('Reason'), 'PROMOTION');
    await user.click(within(months).getByRole('button', { name: 'Apply' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(getStaffDb().plans.grants[0]).toMatchObject({ type: 'FREE_MONTHS', freeMonths: 2, reasonCode: 'PROMOTION' });

    await user.click(screen.getByRole('button', { name: 'Set a charge condition' }));
    const gate = await screen.findByRole('dialog', { name: 'Set a charge condition' });
    await user.type(within(gate).getByLabelText('Amount ($)'), '1000');
    await waitFor(() => expect(within(gate).getAllByRole('option').length).toBeGreaterThan(6));
    await user.selectOptions(within(gate).getByLabelText('Reason'), 'PROMOTION');
    await user.click(within(gate).getByRole('button', { name: 'Apply' }));
    const summary = await screen.findByTestId('panel-charge-gate');
    expect(summary).toHaveTextContent('Charging starts after $1,000 in sales');
    expect(getStaffDb().plans.orgs[0]?.chargeGate).toMatchObject({ mode: 'START_AFTER', metric: 'GROSS_SALES', threshold: 100000 });

    await user.click(within(summary).getByRole('button', { name: 'Remove charge condition' }));
    const clear = await screen.findByRole('dialog', { name: 'Remove charge condition' });
    await waitFor(() => expect(within(clear).getAllByRole('option').length).toBeGreaterThan(2));
    await user.selectOptions(within(clear).getByLabelText('Reason'), 'GOODWILL');
    await user.click(within(clear).getByRole('button', { name: 'Apply' }));
    await waitFor(() => expect(screen.queryByTestId('panel-charge-gate')).not.toBeInTheDocument());
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
    expect(select).toHaveValue('CARD');
    await user.selectOptions(select, 'EARNINGS_FIRST');
    await user.click(screen.getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(getStaffDb().plans.settings.planFeeCollectionDefault).toBe('EARNINGS_FIRST'));
    expect(await screen.findByText('Default saved')).toBeInTheDocument();
  });

  it('is read-only without plans:manage or billing:adjust', async () => {
    asRoles('platform.auditor');
    await renderScreen(<CataloguePanel />);
    await within(await screen.findByRole('table', { name: 'Plans' })).findByRole('row', { name: /Seller Growth/ });
    expect(screen.queryByRole('button', { name: 'New plan' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Retire' })).not.toBeInTheDocument();
  });

  it('shows the plan fee settings read-only to an auditor (S298)', async () => {
    asRoles('platform.auditor');
    await renderScreen(<SettingsPanel />);
    expect(await screen.findByLabelText('Default for new sellers')).toBeDisabled();
    expect(screen.queryByRole('button', { name: 'Save' })).not.toBeInTheDocument();
  });
});
