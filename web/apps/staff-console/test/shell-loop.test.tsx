import { resetStaffDb } from '@afrotransact/api-client/mocks/staff';
import { act, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { ConsoleLoading, ConsoleShell } from '@/components/shell/ConsoleShell';
import { navGuard } from '@/lib/nav-guard';

import { asRoles } from './mock-api';
import { renderWithProviders } from './render';
import { nav } from './setup';

/* The console never sits on its loader: a slow load says so within 10 s even when the loader keeps being mounted
   again (a redirect loop), and redirects that go round in circles stop with a panel naming where they went. */
describe('staff console loading and redirect loops', () => {
  const assign = vi.fn();
  beforeEach(() => {
    resetStaffDb(new Date());
    asRoles(null);
    nav.set('/');
    assign.mockReset();
    window.sessionStorage.clear();
    navGuard.reset();
    vi.stubGlobal('location', { ...window.location, origin: 'http://localhost:3002', href: 'http://localhost:3002/', assign, reload: vi.fn() });
  });
  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });

  it('the slow note counts from the first loader of the streak', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'setInterval', 'clearInterval', 'Date'] });
    const first = renderWithProviders(<ConsoleLoading label="Loading the staff console…" onRetry={() => undefined} />);
    await act(async () => {
      vi.advanceTimersByTime(7_000);
    });
    expect(screen.queryByText('This is taking longer than usual')).not.toBeInTheDocument();
    first.unmount();
    renderWithProviders(<ConsoleLoading label="Loading the staff console…" onRetry={() => undefined} />);
    await act(async () => {
      vi.advanceTimersByTime(3_500);
    });
    expect(screen.getByText('This is taking longer than usual')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Try again' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Go to the shop' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Sign out' })).toBeInTheDocument();
  });

  it('a sign-in that keeps coming back signed out stops with the loop panel', async () => {
    asRoles('guest');
    const login = '/api/bff/v1/auth/login?returnTo=http%3A%2F%2Flocalhost%3A3002%2F';
    window.sessionStorage.setItem('at.st.nav.redirects', JSON.stringify({ [login]: [Date.now() - 3_000, Date.now() - 2_000, Date.now() - 1_000] }));
    renderWithProviders(
      <ConsoleShell>
        <p>x</p>
      </ConsoleShell>
    );
    const heading = await screen.findByRole('heading', { name: 'The staff console keeps redirecting' });
    expect(assign).not.toHaveBeenCalled();
    const panel = heading.closest('[role="alert"]');
    if (!(panel instanceof HTMLElement)) throw new Error('panel');
    expect(within(panel).getByText(/sent to \/api\/bff\/v1\/auth\/login\?returnTo=.* 4 times in 30 seconds/)).toBeInTheDocument();
    expect(within(panel).getByRole('link', { name: 'Go to the shop' })).toBeInTheDocument();
    expect(within(panel).getByRole('button', { name: 'Sign out' })).toBeInTheDocument();
    await userEvent.setup().click(within(panel).getByRole('button', { name: 'Try again' }));
    expect(window.location.reload).toHaveBeenCalled();
  });
});
