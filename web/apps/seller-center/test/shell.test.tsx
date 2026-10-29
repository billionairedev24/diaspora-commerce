import { PENDING_ORG, PROVIDER_ORG, resetSellerDb } from '@afrotransact/api-client/mocks/seller';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it } from 'vitest';

import { AccountEnv } from '@/components/account/AccountEnv';
import { SellerSecurityPage } from '@/components/account/AccountPages';
import { PortalShell } from '@/components/shell/PortalShell';

import { asRole, mockApi } from './mock-api';
import { renderWithProviders } from './render';
import { nav } from './setup';

const navHrefs = async () => {
  const sidebar = (await screen.findAllByRole('navigation', { name: 'Back office' }))[0];
  if (!sidebar) throw new Error('sidebar');
  return [...sidebar.querySelectorAll('a')].map((a) => a.getAttribute('href'));
};

describe('portal shell', () => {
  beforeEach(() => {
    resetSellerDb(new Date());
    asRole(null);
    nav.pathname = '/';
    nav.replace.mockReset();
  });

  it('shows the owner every seller module, grouped, with the payouts-paused banner', async () => {
    renderWithProviders(<PortalShell><p>page body</p></PortalShell>);
    expect(await navHrefs()).toEqual(['/', '/orders', '/returns', '/disputes', '/listings', '/reviews', '/inventory', '/coupons', '/payouts', '/fees', '/billing', '/account-health', '/team']);
    expect(screen.getByRole('button', { name: /commerce/i })).toHaveAttribute('aria-expanded', 'false');
    expect(await screen.findByText('Payouts are paused.')).toBeInTheDocument();
    expect(screen.getByText('page body')).toBeInTheDocument();
  });

  it('hides modules the role cannot open and shows an access notice on direct visits', async () => {
    asRole('seller.fulfilment');
    nav.pathname = '/billing';
    renderWithProviders(<PortalShell><p>billing body</p></PortalShell>);
    const hrefs = await navHrefs();
    expect(hrefs).toContain('/orders');
    expect(hrefs).not.toContain('/billing');
    expect(screen.queryByText('billing body')).not.toBeInTheDocument();
  });

  it('offers only Onboarding before approval and redirects there', async () => {
    await mockApi().setActiveOrg(PENDING_ORG);
    renderWithProviders(<PortalShell><p>home</p></PortalShell>);
    expect(await navHrefs()).toEqual(['/onboarding']);
    await waitFor(() => expect(nav.replace).toHaveBeenCalledWith('/onboarding'));
  });

  it('switches to Provider Hub tools for a service-provider organisation', async () => {
    await mockApi().setActiveOrg(PROVIDER_ORG);
    renderWithProviders(<PortalShell><p>home</p></PortalShell>);
    const hrefs = await navHrefs();
    expect(hrefs).toEqual(expect.arrayContaining(['/calendar', '/requests', '/hours', '/services', '/policy', '/earnings']));
    expect(hrefs).not.toContain('/orders');
  });
});

describe('My account in Seller Center', () => {
  beforeEach(() => {
    resetSellerDb(new Date());
    asRole(null);
    nav.replace.mockReset();
  });

  it('keeps the account menu inside Seller Center; only the AfroTransact link leaves it', async () => {
    nav.pathname = '/';
    const user = userEvent.setup();
    renderWithProviders(
      <AccountEnv>
        <PortalShell>
          <p>home</p>
        </PortalShell>
      </AccountEnv>
    );
    await user.click(await screen.findByRole('button', { name: /Account menu for/ }));
    const items = await screen.findAllByRole('menuitem');
    const links = Object.fromEntries(items.map((i) => [i.textContent?.trim() ?? '', i.getAttribute('href')]));
    expect(links).toMatchObject({ 'My account': '/account', 'Sign-in & security': '/account/security', Notifications: '/account/notifications', Preferences: '/account/preferences' });
    const storefront = items.find((i) => i.textContent?.includes('Open AfroTransact'));
    expect(storefront).toHaveAttribute('target', '_blank');
    expect(storefront?.getAttribute('href')).toMatch(/^http/);
  });

  it('keeps My account closed while the application is in progress: it redirects to onboarding', async () => {
    await mockApi().setActiveOrg(PENDING_ORG);
    nav.pathname = '/account/security';
    renderWithProviders(
      <AccountEnv>
        <PortalShell>
          <SellerSecurityPage />
        </PortalShell>
      </AccountEnv>
    );
    await waitFor(() => expect(nav.replace).toHaveBeenCalledWith('/onboarding'));
    expect(screen.queryByTestId('two-step')).not.toBeInTheDocument();
  });
});
