import { resetStaffDb } from '@afrotransact/api-client/mocks/staff';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { ResourceTable } from '@/components/resources/ResourceTable';

import { asRoles, interceptStaff } from './mock-api';
import { renderScreen } from './render';
import { nav } from './setup';

/*
 * Selling approvals: a case type next to Applications (same queue tabs, who-has-it names, SLA and escalation, the same two
 * steps) and the selling rules with their audit trail. Names, never ids.
 */

describe('Selling approvals in the staff queue', () => {
  beforeEach(() => {
    resetStaffDb(new Date('2026-10-12T14:00:00Z'));
    asRoles(null);
  });
  afterEach(() => interceptStaff(null));

  it('lists the cases like applications: the seller by name, who has it, how long, the SLA', async () => {
    nav.set('/applications?tab=selling-approvals&status=ALL');
    await renderScreen(<ResourceTable resource="selling-approvals" statusFilter defaultStatus="MY" />);
    const table = await screen.findByRole('table');
    for (const h of ['Approval for', 'Seller', 'With', 'Waiting', 'SLA', 'Escalation']) expect(await within(table).findByRole('columnheader', { name: new RegExp(h) })).toBeInTheDocument();
    expect(await within(table).findByText('Kente Naturals')).toBeInTheDocument();
    expect(within(table).getByText('Dietary supplements')).toBeInTheDocument();
    expect(within(table).getAllByText('Any reviewer').length).toBeGreaterThan(0);
    expect(within(table).queryByText(/org-sell-/)).not.toBeInTheDocument();
  });

  it('offers the same queue tabs as applications', async () => {
    nav.set('/applications?tab=selling-approvals');
    await renderScreen(<ResourceTable resource="selling-approvals" statusFilter defaultStatus="MY" />);
    const tabs = await screen.findByRole('group', { name: /status|filter/i });
    for (const label of ['My queue', 'Awaiting review', 'Awaiting approval', 'Escalated', 'Returned to applicant', 'All']) {
      expect(within(tabs).getByRole('button', { name: label })).toBeInTheDocument();
    }
  });

  it('the sheet leads with who has it and offers claim, both steps, reject and "cannot apply"', async () => {
    const user = userEvent.setup();
    nav.set('/applications?tab=selling-approvals&status=ALL');
    await renderScreen(<ResourceTable resource="selling-approvals" statusFilter defaultStatus="ALL" />);
    await user.click(await screen.findByText('Gucci'));
    const sheet = await screen.findByRole('dialog');
    expect((await within(sheet).findAllByText(/Awaiting approval/)).length).toBeGreaterThan(0);
    for (const label of ['Final approval', 'Send back', 'Reject', 'Cannot apply (not appealable)']) {
      await waitFor(() => expect(within(sheet).getByRole('button', { name: label })).toBeInTheDocument());
    }
  });
});

describe('Selling rules', () => {
  beforeEach(() => {
    resetStaffDb(new Date('2026-10-12T14:00:00Z'));
    asRoles(null);
  });
  afterEach(() => interceptStaff(null));

  it('lists the rules with their level and documents, and a way to add a gated brand', async () => {
    nav.set('/moderation?tab=selling-rules');
    await renderScreen(<ResourceTable resource="selling-rules" statusFilter />);
    const table = await screen.findByRole('table');
    expect(await within(table).findByText('Gucci')).toBeInTheDocument();
    expect(within(table).getAllByText('Apply to sell').length).toBeGreaterThan(0);
    expect(within(table).getAllByText(/Brand authorization/).length).toBeGreaterThan(0);
    expect(await screen.findByRole('button', { name: /New|Add/ })).toBeInTheDocument();
  });
});
