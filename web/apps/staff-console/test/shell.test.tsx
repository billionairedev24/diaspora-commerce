import { resetStaffDb } from '@afrotransact/api-client/mocks/staff';
import { screen } from '@testing-library/react';
import { beforeEach, describe, expect, it } from 'vitest';

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
