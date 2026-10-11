import { resetStaffDb } from '@afrotransact/api-client/mocks/staff';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it } from 'vitest';

import { AccountEnv } from '@/components/account/AccountEnv';
import { StaffSecurityPage } from '@/components/account/AccountPages';
import { ConsoleShell } from '@/components/shell/ConsoleShell';

import { asRoles } from './mock-api';
import { renderWithProviders } from './render';
import { nav } from './setup';

const navHrefs = async () => {
  const sidebar = (await screen.findAllByRole('navigation', { name: 'Staff console' }))[0];
  if (!sidebar) throw new Error('sidebar');
  return [...sidebar.querySelectorAll('a')].map((a) => a.getAttribute('href'));
};

describe('staff console shell', () => {
  beforeEach(() => {
    resetStaffDb(new Date());
    asRoles(null);
    nav.set('/');
  });

  it('groups every module for a super admin and opens the dashboard by default', async () => {
    renderWithProviders(
      <ConsoleShell>
        <p>dashboard body</p>
      </ConsoleShell>
    );
    const hrefs = await navHrefs();
    expect(hrefs[0]).toBe('/');
    expect(hrefs).toEqual(expect.arrayContaining(['/users', '/fees', '/support', '/site-content', '/developers']));
    const groups = screen.getAllByRole('button', { expanded: false }).map((b) => b.textContent);
    expect(groups.join(' ')).toMatch(/Commerce/);
    expect(screen.getByRole('button', { name: /overview/i })).toHaveAttribute('aria-expanded', 'true');
    expect(screen.getByText('dashboard body')).toBeInTheDocument();
  });

  it('hides menu items the role has no access to and blocks direct visits', async () => {
    asRoles('platform.customer_support');
    nav.set('/users');
    renderWithProviders(
      <ConsoleShell>
        <p>users body</p>
      </ConsoleShell>
    );
    const hrefs = await navHrefs();
    expect(hrefs).toEqual(expect.arrayContaining(['/', '/support', '/orders', '/disputes']));
    expect(hrefs).not.toContain('/users');
    expect(hrefs).not.toContain('/fees');
    expect(screen.queryByText('users body')).not.toBeInTheDocument();
    expect(screen.getByText('You don’t have access to this page')).toBeInTheDocument();
  });

  it('turns customers away and asks guests to sign in', async () => {
    asRoles('customer');
    const { unmount } = renderWithProviders(
      <ConsoleShell>
        <p>x</p>
      </ConsoleShell>
    );
    expect(await screen.findByRole('heading', { name: 'Staff only' })).toBeInTheDocument();
    unmount();
    asRoles('guest');
    renderWithProviders(
      <ConsoleShell>
        <p>x</p>
      </ConsoleShell>
    );
    expect(await screen.findByRole('heading', { name: /sign in to the staff console/i })).toBeInTheDocument();
  });
});

describe('My account in the staff console', () => {
  beforeEach(() => {
    resetStaffDb();
    asRoles('platform.customer_support');
  });

  it('opens for any role, with in-console account links, and keeps two-step verification mandatory', async () => {
    nav.set('/account/security');
    const user = userEvent.setup();
    renderWithProviders(
      <AccountEnv>
        <ConsoleShell>
          <StaffSecurityPage />
        </ConsoleShell>
      </AccountEnv>
    );
    await waitFor(() => expect(screen.getByTestId('two-step')).toHaveTextContent('Required'), { timeout: 5000 });
    // SMS is the only method: it can't be turned off; the authenticator can be set up.
    expect(within(screen.getByTestId('mfa-sms')).getByRole('button', { name: 'Turn off' })).toBeDisabled();
    expect(within(screen.getByTestId('mfa-totp')).getByRole('button', { name: 'Set up' })).toBeEnabled();
    await user.click(screen.getByRole('button', { name: /Account menu for/ }));
    const items = await screen.findAllByRole('menuitem');
    expect(items.find((i) => i.textContent?.includes('My account'))).toHaveAttribute('href', '/account');
    expect(items.find((i) => i.textContent?.includes('Open the storefront'))).toHaveAttribute('target', '_blank');
  });
});
