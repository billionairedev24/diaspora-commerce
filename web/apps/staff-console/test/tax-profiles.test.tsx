import { resetStaffDb } from '@afrotransact/api-client/mocks/staff';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { TaxProfilesPanel } from '@/components/tax/TaxProfilesPanel';

import { asRoles, interceptStaff } from './mock-api';
import { renderScreen } from './render';
import { nav } from './setup';

const NOW = new Date('2026-11-09T12:00:00Z');

describe('Tax profiles', () => {
  beforeEach(() => {
    resetStaffDb(NOW);
    asRoles('platform.super_admin');
    nav.set('/fees?tab=tax-profiles');
  });
  afterEach(() => interceptStaff(null));

  it('lists the categories with plain status labels, Stripe codes and the rule version', async () => {
    await renderScreen(<TaxProfilesPanel />);
    const table = await screen.findByRole('table', { name: 'Tax profiles' });
    const row = (await within(table).findByText('Nuts & seeds')).closest('tr') as HTMLElement;
    expect(within(row).getByText('Groceries & Food')).toBeInTheDocument();
    expect(within(row).getByText('Ready')).toBeInTheDocument();
    expect(within(row).getByText('txcd_40040016, txcd_40100001')).toBeInTheDocument();
    expect(within(row).getByText('tx-2026.11.09-draft.1')).toBeInTheDocument();
    // Never the raw enum.
    expect(table).not.toHaveTextContent('NEEDS_APPROVAL');
    expect(screen.getByRole('combobox', { name: 'Status' })).toHaveDisplayValue('All statuses');
  });

  it('filters by department and by status, and says so when nothing matches', async () => {
    const user = userEvent.setup();
    await renderScreen(<TaxProfilesPanel />);
    const table = await screen.findByRole('table', { name: 'Tax profiles' });
    await within(table).findByText('Nuts & seeds');
    await user.selectOptions(screen.getByRole('combobox', { name: 'Department' }), 'digital-products');
    await waitFor(() => expect(within(table).queryByText('Nuts & seeds')).not.toBeInTheDocument());
    expect(await within(table).findByText('Downloadable e-books')).toBeInTheDocument();
    await user.selectOptions(screen.getByRole('combobox', { name: 'Status' }), 'BLOCKED_BUNDLE');
    expect(await screen.findByText('No category matches these filters.')).toBeInTheDocument();
    expect(nav.search).toContain('profiles.department=digital-products');
    expect(nav.search).toContain('profiles.status=BLOCKED_BUNDLE');
  });

  it('opens a profile with its questions, the decision table as sentences and the notes for the tax professional', async () => {
    nav.set('/fees?tab=tax-profiles&profile=nuts-seeds');
    await renderScreen(<TaxProfilesPanel />);
    const sheet = await screen.findByRole('dialog', { name: 'Nuts & seeds' });
    expect(await within(sheet).findByText(/net weight is at least 2.5 oz/)).toBeInTheDocument();
    expect(within(sheet).getByText(/servings per package is more than 1/)).toBeInTheDocument();
    const flagged = within(sheet).getByRole('region', { name: 'Flagged for the tax professional' });
    expect(within(flagged).getByText(/Interpretation: exempt only when servings > 1/)).toBeInTheDocument();
    const questions = within(sheet).getByRole('table', { name: 'Questions for this category' });
    expect(within(questions).getAllByText('Always needed').length).toBeGreaterThan(0);
    expect(within(sheet).getByRole('button', { name: 'Reopen for approval…' })).toBeInTheDocument();
    expect(within(sheet).queryByRole('button', { name: 'Mark approved…' })).not.toBeInTheDocument();
  });

  it('marks a category approved only with a reason of ten characters, the sign-off name and a reference', async () => {
    const user = userEvent.setup();
    nav.set('/fees?tab=tax-profiles&profile=downloadable-ebooks');
    await renderScreen(<TaxProfilesPanel />);
    const sheet = await screen.findByRole('dialog', { name: 'Downloadable e-books' });
    expect(await within(sheet).findByText('Needs tax sign-off', { selector: 'span' })).toBeInTheDocument();
    await user.click(within(sheet).getByRole('button', { name: 'Mark approved…' }));
    const dialog = await screen.findByRole('dialog', { name: 'Mark Downloadable e-books approved?' });
    const save = within(dialog).getByRole('button', { name: 'Mark approved' });
    expect(save).toBeDisabled();
    // The rule is on screen before anything is typed, and counts as the person types.
    expect(within(dialog).getByText('At least 10 characters.')).toBeInTheDocument();
    await user.type(within(dialog).getByLabelText('Reason'), 'Opinion');
    expect(within(dialog).getByText(/Add a few more words/)).toBeInTheDocument();
    await user.type(within(dialog).getByLabelText('Reason'), ' letter received');
    expect(save).toBeDisabled(); // the sign-off name and reference are still missing
    await user.type(within(dialog).getByLabelText('Signed off by'), 'Dana Whitfield, CPA');
    await user.type(within(dialog).getByLabelText('Reference'), 'OD-4 letter 2026-11-09');
    expect(save).toBeEnabled();
    await user.click(save);
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Mark Downloadable e-books approved?' })).not.toBeInTheDocument());
    expect(await within(sheet).findByText('Ready', { selector: 'span' })).toBeInTheDocument();
    expect(within(sheet).getByText(/Signed off by Dana Whitfield, CPA, OD-4 letter 2026-11-09/)).toBeInTheDocument();
    expect(await screen.findByText('Downloadable e-books is marked approved.')).toBeInTheDocument();
  });

  it('is read only where the engine owns the gate', async () => {
    nav.set('/fees?tab=tax-profiles&profile=cleaning-paper-products');
    await renderScreen(<TaxProfilesPanel />);
    const sheet = await screen.findByRole('dialog', { name: 'Cleaning & paper products' });
    expect(await within(sheet).findByText(/lifted product by product/)).toBeInTheDocument();
    expect(within(sheet).queryByRole('button', { name: /Mark approved|Reopen/ })).not.toBeInTheDocument();
  });

  it('is read only for someone without tax:classify-admin, in the sheet and in the row menu', async () => {
    const user = userEvent.setup();
    asRoles('platform.auditor');
    nav.set('/fees?tab=tax-profiles&profile=downloadable-ebooks');
    await renderScreen(<TaxProfilesPanel />);
    const sheet = await screen.findByRole('dialog', { name: 'Downloadable e-books' });
    expect(await within(sheet).findByText(/Changing it needs the tax:classify-admin permission/)).toBeInTheDocument();
    expect(within(sheet).queryByRole('button', { name: /Mark approved/ })).not.toBeInTheDocument();
    await user.click(within(sheet).getByRole('button', { name: 'Close' }));
    const table = await screen.findByRole('table', { name: 'Tax profiles' });
    await user.click(await within(table).findByRole('button', { name: 'Actions for Baby food & formula' }));
    expect(await screen.findByRole('menuitem', { name: 'Open profile' })).toBeInTheDocument();
    expect(screen.queryByRole('menuitem', { name: /Mark approved/ })).not.toBeInTheDocument();
  });

  it('shows a permission message when the role cannot read tax at all', async () => {
    asRoles('platform.customer_support');
    await renderScreen(<TaxProfilesPanel />);
    expect(await screen.findByText('You don’t have access to this')).toBeInTheDocument();
  });

  it('shows the unavailable state, with a retry, when the tax service is down', async () => {
    const user = userEvent.setup();
    let down = true;
    interceptStaff(async (req, next) => {
      if (down && new URL(req.url).pathname.endsWith('/v1/staff/tax/profiles'))
        return new Response(JSON.stringify({ status: 503, code: 'UPSTREAM_UNAVAILABLE', title: 'Service unavailable', detail: 'This part of AfroTransact is temporarily unavailable.' }), { status: 503, headers: { 'content-type': 'application/problem+json' } });
      return next(req);
    });
    await renderScreen(<TaxProfilesPanel />);
    expect(await screen.findByText('This part of AfroTransact is temporarily unavailable')).toBeInTheDocument();
    down = false;
    await user.click(screen.getByRole('button', { name: 'Try again' }));
    expect(await screen.findByText('Nuts & seeds')).toBeInTheDocument();
  });
});
