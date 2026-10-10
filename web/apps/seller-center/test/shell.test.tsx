import { PENDING_ORG, PROVIDER_ORG, resetSellerDb } from '@afrotransact/api-client/mocks/seller';
import { screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it } from 'vitest';

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
    expect(await navHrefs()).toEqual(['/', '/orders', '/listings', '/inventory', '/storefront', '/payouts', '/billing', '/account-health', '/team']);
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
    expect(hrefs).toEqual(expect.arrayContaining(['/calendar', '/requests', '/hours', '/services', '/policy', '/public-page', '/earnings']));
    expect(hrefs).not.toContain('/orders');
  });
});
