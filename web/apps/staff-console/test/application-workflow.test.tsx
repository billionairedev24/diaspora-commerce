import { resetStaffDb } from '@afrotransact/api-client/mocks/staff';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { ResourceTable } from '@/components/resources/ResourceTable';
import { SettingsHub } from '@/components/settings/SettingsHub';

import { asRoles, interceptStaff } from './mock-api';
import { renderScreen } from './render';
import { nav } from './setup';

/*
 * Application review workflow: the list says who has each case and for how long (With / Waiting / SLA), the queue
 * tabs filter it, the case sheet leads with "where it is", the approver's send-back shows as a banner, and the actions
 * follow permission. Seed: app-200 is with Amina Bello (50 h, overdue, escalated), app-201 waits in the review pool
 * (3 h), app-207 was sent back by Adam Ade, app-203 awaits approval.
 */

describe('Application review workflow', () => {
  beforeEach(() => {
    resetStaffDb(new Date('2026-10-12T14:00:00Z'));
    asRoles(null);
  });
  afterEach(() => interceptStaff(null));

  it('shows With, Waiting and SLA columns and the escalation badge for every case, held or not', async () => {
    nav.set('/applications?status=ALL');
    await renderScreen(<ResourceTable resource="applications" statusFilter defaultStatus="MY" />);
    const table = await screen.findByRole('table');
    for (const h of ['With', 'Waiting', 'SLA', 'Escalation']) expect(await within(table).findByRole('columnheader', { name: new RegExp(h) })).toBeInTheDocument();
    const held = (await within(table).findByText('Amina Bello')).closest('tr') as HTMLElement;
    expect(within(held).getByText('2 days')).toBeInTheDocument();
    expect(within(held).getByText('Overdue')).toBeInTheDocument();
    expect(within(held).getByText('Level 1')).toBeInTheDocument();
    expect(within(table).getAllByText('Any reviewer').length).toBeGreaterThan(0);
    expect(within(table).getAllByText('Any approver').length).toBeGreaterThan(0);
  });

  it('offers the queue tabs and filters by them', async () => {
    const user = userEvent.setup();
    nav.set('/applications');
    await renderScreen(<ResourceTable resource="applications" statusFilter defaultStatus="MY" />);
    const tabs = await screen.findByRole('group', { name: /status|filter/i });
    for (const label of ['My queue', 'Awaiting review', 'Awaiting approval', 'Escalated', 'Returned to applicant', 'All']) {
      expect(within(tabs).getByRole('button', { name: label })).toBeInTheDocument();
    }
    expect(within(tabs).getByRole('button', { name: 'My queue' })).toHaveAttribute('aria-pressed', 'true');
    await user.click(within(tabs).getByRole('button', { name: 'Escalated' }));
    const table = await screen.findByRole('table');
    await waitFor(() => expect(within(table).queryByText('Any approver')).not.toBeInTheDocument());
    expect(within(table).getByText('Amina Bello')).toBeInTheDocument();
  });

  it('leads the case sheet with where it is, with whom and since when', async () => {
    nav.set('/applications?status=ALL&open=app-200');
    await renderScreen(<ResourceTable resource="applications" statusFilter defaultStatus="MY" />);
    const sheet = await screen.findByRole('dialog');
    expect((await within(sheet).findAllByText(/Awaiting review · with Amina Bello · since 2 days/)).length).toBeGreaterThan(0);
    expect(sheet).not.toHaveTextContent(/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/);
  });

  it('shows who sent the case back, when and why, at the top of the case in the review queue', async () => {
    nav.set('/applications?status=ALL&open=app-207');
    await renderScreen(<ResourceTable resource="applications" statusFilter defaultStatus="MY" />);
    const banner = await screen.findByTestId('record-banner');
    expect(banner).toHaveTextContent('Returned by approver');
    expect(banner).toHaveTextContent('Adam Ade');
    expect(banner).toHaveTextContent('The tax ID was not checked');
  });

  it('claims an unassigned case and releases it again', async () => {
    const user = userEvent.setup();
    nav.set('/applications?status=ALL&open=app-201');
    await renderScreen(<ResourceTable resource="applications" statusFilter defaultStatus="MY" />);
    await user.click(await screen.findByRole('button', { name: 'Claim' }));
    const confirm = (await screen.findAllByRole('button', { name: 'Claim' })).at(-1) as HTMLElement;
    await user.click(confirm);
    await waitFor(() => expect(screen.getByRole('dialog').textContent).toMatch(/with Ifeoma Okafor/));
    expect(screen.queryByRole('button', { name: 'Claim' })).toBeNull();
    await user.click(screen.getByRole('button', { name: 'Release to the pool' }));
    const form = await screen.findByRole('dialog', { name: 'Release to the pool' });
    await user.click(within(form).getByRole('button', { name: 'Release' }));
    await waitFor(() => expect(screen.getByRole('dialog')).toHaveTextContent(/any reviewer/));
  });

  it('escalating needs a reason', async () => {
    const user = userEvent.setup();
    nav.set('/applications?status=ALL&open=app-201');
    await renderScreen(<ResourceTable resource="applications" statusFilter defaultStatus="MY" />);
    await user.click(await screen.findByRole('button', { name: 'Escalate now' }));
    const form = await screen.findByRole('dialog', { name: 'Escalate now' });
    await user.click(within(form).getByRole('button', { name: 'Escalate' }));
    expect((await within(form).findAllByText(/required|fill|reason/i)).length).toBeGreaterThan(0);
  });

  it('a person who only reads applications sees the list and the holder but no action buttons', async () => {
    asRoles('platform.finance');
    nav.set('/applications?status=ALL&open=app-200');
    await renderScreen(<ResourceTable resource="applications" statusFilter defaultStatus="MY" />);
    const sheet = await screen.findByRole('dialog');
    expect((await within(sheet).findAllByText(/with Amina Bello/)).length).toBeGreaterThan(0);
    for (const name of ['Claim', 'Assign…', 'Release to the pool', 'Escalate now']) {
      const b = within(sheet).queryByRole('button', { name });
      if (b) expect(b).toBeDisabled();
    }
  });

  it('the settings screen shows the SLAs and chains and refuses level 2 without level 1', async () => {
    const user = userEvent.setup();
    nav.set('/settings?section=application-review&open=current');
    await renderScreen(<SettingsHub />);
    const sheet = await screen.findByRole('dialog');
    expect(await within(sheet).findByText('Service levels')).toBeInTheDocument();
    expect((await within(sheet).findAllByText(/everyone with the role application supervisor/)).length).toBeGreaterThan(0);
    await user.click(within(sheet).getByRole('button', { name: /Edit|Change/ }));
    const form = await screen.findByRole('dialog', { name: 'Application review: SLAs and escalation' });
    const level1 = within(form).getByLabelText(/Review: level 1 role/);
    await user.clear(level1);
    await user.type(within(form).getByLabelText(/^Reason/), 'Trying level two on its own');
    await user.click(within(form).getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(screen.getAllByRole('dialog').map((d) => d.textContent).join('|')).toContain('Set level 1 before level 2'));
  });
});
