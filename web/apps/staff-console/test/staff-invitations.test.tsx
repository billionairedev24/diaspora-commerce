import { resetStaffDb } from '@afrotransact/api-client/mocks/staff';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import axe from 'axe-core';
import { StrictMode } from 'react';
import { beforeEach, describe, expect, it } from 'vitest';

import { InviteAccept } from '@/components/invite/InviteAccept';
import { StaffAccountsView } from '@/components/settings/StaffAccountsView';

import { asRoles } from './mock-api';
import { renderScreen, renderWithProviders } from './render';
import { nav } from './setup';

const reason = 'Joins support in the Lagos office';

describe('Staff accounts: invitations', () => {
  beforeEach(() => {
    resetStaffDb(new Date());
    asRoles(null);
    nav.set('/settings/staff');
  });

  it('lists who is waiting by name and email, with who invited them and when the link stops working, and never an id or a token', async () => {
    const { container } = await renderScreen(<StaffAccountsView />);
    const table = await screen.findByRole('table', { name: 'Invitations waiting' });
    const tunde = (await within(table).findByText('Tunde Bakare')).closest('tr') as HTMLElement;
    expect(within(tunde).getByText('tunde@afrotransact.com')).toBeInTheDocument();
    expect(within(tunde).getByText('Customer Support')).toBeInTheDocument();
    expect(within(tunde).getByText('Ifeoma Okafor')).toBeInTheDocument();
    expect(within(tunde).getByText(/^Expires /)).toBeInTheDocument();
    expect(container.textContent).not.toMatch(/mock-token|0b0b0b0b/);
    // the old "New staff account" form with a temporary password is gone
    expect(screen.queryByText(/Temporary password/i)).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /^New/ })).not.toBeInTheDocument();
  });

  it('invites a person: name, work email, role and why; the invitation appears in the list', async () => {
    const user = userEvent.setup();
    await renderScreen(<StaffAccountsView />);
    await user.click(await screen.findByTestId('invite-open'));
    const dialog = await screen.findByRole('dialog', { name: 'Invite a person to be staff' });
    const send = within(dialog).getByRole('button', { name: 'Send invitation' });
    expect(send).toBeDisabled();
    await user.type(within(dialog).getByLabelText(/^First name/), 'Ada');
    await user.type(within(dialog).getByLabelText(/^Last name/), 'Obi');
    await user.type(within(dialog).getByLabelText(/^Work email/), 'ada.obi@gmail.com');
    await user.selectOptions(within(dialog).getByLabelText(/^Role/), 'platform.customer_support');
    await user.type(within(dialog).getByLabelText(/^Why/), reason);
    await user.click(send);
    // identity limits staff to the work domain: the answer lands on the field
    expect(await within(dialog).findByText(/company email address/)).toBeInTheDocument();
    await user.clear(within(dialog).getByLabelText(/^Work email/));
    await user.type(within(dialog).getByLabelText(/^Work email/), 'ada.obi@afrotransact.com');
    await user.click(within(dialog).getByRole('button', { name: 'Send invitation' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    const table = await screen.findByRole('table', { name: 'Invitations waiting' });
    const row = (await within(table).findByText('Ada Obi')).closest('tr') as HTMLElement;
    expect(within(row).getByText('ada.obi@afrotransact.com')).toBeInTheDocument();
    expect(within(row).getByText('Ifeoma Okafor')).toBeInTheDocument();
  });

  it('sends a link again (the earlier one stops working) and revokes with a reason', async () => {
    const user = userEvent.setup();
    await renderScreen(<StaffAccountsView />);
    const table = await screen.findByRole('table', { name: 'Invitations waiting' });
    const chidi = (await within(table).findByText('Chidi Eze')).closest('tr') as HTMLElement;
    await user.click(within(chidi).getByRole('button', { name: 'Send again' }));
    await waitFor(() => expect(within(chidi).getByText(/sent 2 times/)).toBeInTheDocument());
    await user.click(within(chidi).getByRole('button', { name: 'Revoke' }));
    const dialog = await screen.findByRole('dialog', { name: 'Revoke the invitation for Chidi Eze?' });
    const confirm = within(dialog).getByRole('button', { name: 'Revoke invitation' });
    expect(confirm).toBeDisabled();
    await user.type(within(dialog).getByLabelText(/^Why/), 'Offer withdrawn');
    await user.click(confirm);
    await waitFor(() => expect(within(table).queryByText('Chidi Eze')).not.toBeInTheDocument());
    expect(within(table).getByText('Tunde Bakare')).toBeInTheDocument();
  });

  it('shows the list read-only to staff with access:read and no access:admin', async () => {
    asRoles('platform.auditor');
    await renderScreen(<StaffAccountsView />);
    const table = await screen.findByRole('table', { name: 'Invitations waiting' });
    expect(await within(table).findByText('Tunde Bakare')).toBeInTheDocument();
    expect(screen.queryByTestId('invite-open')).not.toBeInTheDocument();
    expect(within(table).queryByRole('button', { name: 'Send again' })).not.toBeInTheDocument();
    expect(within(table).queryByRole('button', { name: 'Revoke' })).not.toBeInTheDocument();
  });

  it('is axe-clean', async () => {
    const { container } = await renderScreen(<StaffAccountsView />);
    await screen.findByText('Tunde Bakare');
    const results = await axe.run(container, { rules: { 'color-contrast': { enabled: false } } });
    expect(results.violations.map((v) => v.id)).toEqual([]);
  });
});

describe('The invitation page (no staff navigation, no session)', () => {
  beforeEach(() => {
    resetStaffDb(new Date());
    asRoles(null);
  });
  const open = (token: string) => {
    window.history.replaceState(null, '', `/invite#token=${token}`);
    return renderWithProviders(<InviteAccept />);
  };

  it('says who the invitation is for, takes the invitee’s own password, and hides the token from the address bar', async () => {
    const user = userEvent.setup();
    const { container } = open('mock-token-1');
    expect(await screen.findByRole('heading', { name: 'Welcome, Tunde' })).toBeInTheDocument();
    expect(screen.getByTestId('invite-email')).toHaveTextContent('tunde@afrotransact.com');
    expect(screen.getByText(/as Customer Support/)).toBeInTheDocument();
    expect(window.location.hash).toBe('');
    // no staff navigation on this page
    expect(screen.queryByRole('navigation')).not.toBeInTheDocument();
    expect(screen.queryByText('Settings')).not.toBeInTheDocument();
    const results = await axe.run(container, { rules: { 'color-contrast': { enabled: false } } });
    expect(results.violations.map((v) => v.id)).toEqual([]);

    await user.type(screen.getByLabelText(/^Choose a password/), 'short');
    await user.type(screen.getByLabelText(/^Type it again/), 'short');
    await user.click(screen.getByRole('button', { name: 'Create my account' }));
    expect(await screen.findByText(/Use at least 12 characters/)).toBeInTheDocument();
    await user.clear(screen.getByLabelText(/^Choose a password/));
    await user.clear(screen.getByLabelText(/^Type it again/));
    await user.type(screen.getByLabelText(/^Choose a password/), 'Plantain-Jollof-2026');
    await user.type(screen.getByLabelText(/^Type it again/), 'Plantain-Jollof-2027');
    await user.click(screen.getByRole('button', { name: 'Create my account' }));
    expect(await screen.findByText(/The two passwords don't match/)).toBeInTheDocument();
    await user.clear(screen.getByLabelText(/^Type it again/));
    await user.type(screen.getByLabelText(/^Type it again/), 'Plantain-Jollof-2026');
    await user.click(screen.getByRole('button', { name: 'Create my account' }));
    expect(await screen.findByRole('heading', { name: 'Your account is ready' })).toBeInTheDocument();
    expect(screen.getByTestId('invite-signin')).toHaveAttribute('href', expect.stringContaining('/v1/auth/login'));
    expect(container.textContent).not.toContain('Plantain-Jollof');
  });

  it('keeps the token when React runs the effect twice (development StrictMode)', async () => {
    window.history.replaceState(null, '', '/invite#token=mock-token-3');
    renderWithProviders(
      <StrictMode>
        <InviteAccept />
      </StrictMode>
    );
    expect(await screen.findByRole('heading', { name: 'Welcome, Ngozi' })).toBeInTheDocument();
  });

  it('says what to do for a link that is not valid, was used, or was withdrawn', async () => {
    window.history.replaceState(null, '', '/invite');
    const none = renderWithProviders(<InviteAccept />);
    expect(await screen.findByRole('heading', { name: "This link isn't valid" })).toBeInTheDocument();
    none.unmount();
    open('no-such-token');
    expect(await screen.findByRole('heading', { name: "This link isn't valid" })).toBeInTheDocument();
  });

  it('a link used once cannot be used again', async () => {
    const user = userEvent.setup();
    const first = open('mock-token-1');
    await user.type(await screen.findByLabelText(/^Choose a password/), 'Plantain-Jollof-2026');
    await user.type(screen.getByLabelText(/^Type it again/), 'Plantain-Jollof-2026');
    await user.click(screen.getByRole('button', { name: 'Create my account' }));
    await screen.findByRole('heading', { name: 'Your account is ready' });
    first.unmount();
    open('mock-token-1');
    expect(await screen.findByRole('heading', { name: 'This invitation was already used' })).toBeInTheDocument();
  });

  it('an expired link says so and what to do, and offers no password form', async () => {
    open('mock-token-2');
    expect(await screen.findByRole('heading', { name: 'This invitation has expired' })).toBeInTheDocument();
    expect(screen.getByText(/ask the person who invited you to send it again/)).toBeInTheDocument();
    expect(screen.queryByLabelText(/^Choose a password/)).not.toBeInTheDocument();
  });
});
