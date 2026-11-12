import { getSellerDb, MOCK_FASHION_ORG, MOCK_FOOD_ORG, resetSellerDb } from '@afrotransact/api-client/mocks/seller';
import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, describe, expect, it } from 'vitest';

import { OfferServices } from '@/components/account/OfferServices';
import { BulkUploadView } from '@/components/listings/bulk/BulkUploadView';
import { ProductForm } from '@/components/listings/ProductForm';
import { PortalShell } from '@/components/shell/PortalShell';

import { asRole, mockApi } from './mock-api';
import { renderScreen, renderWithProviders } from './render';
import { nav } from './setup';

beforeEach(() => {
  resetSellerDb(new Date('2026-10-10T15:00:00Z'));
  asRole(null);
  nav.pathname = '/listings';
});

const sidebarHrefs = async () => {
  const sidebar = (await screen.findAllByRole('navigation', { name: 'Back office' }))[0];
  if (!sidebar) throw new Error('sidebar');
  return [...sidebar.querySelectorAll('a')].map((a) => a.getAttribute('href'));
};

describe('size charts are a step of the product form, not a page', () => {
  it('the sidebar never lists Size charts, whatever the store sells', async () => {
    await mockApi().setActiveOrg(MOCK_FASHION_ORG);
    renderWithProviders(<PortalShell><p>body</p></PortalShell>);
    await waitFor(async () => expect(await sidebarHrefs()).toContain('/listings'));
    await mockApi().getCatalogScope();
    await new Promise((r) => setTimeout(r, 60));
    expect(await sidebarHrefs()).not.toContain('/listings/size-charts');
  });

  it('the size guide step appears only for a category with sizes', async () => {
    await mockApi().setActiveOrg(MOCK_FASHION_ORG);
    await renderScreen(<ProductForm />);
    fireEvent.change(await screen.findByRole('combobox', { name: 'Category' }), { target: { value: 'kaftans' } });
    fireEvent.click(await screen.findByRole('option', { name: /Kaftans & shirts/ }));
    expect(await screen.findByRole('radiogroup', { name: /Size chart/ })).toBeInTheDocument();
  });
});

describe('A seller is not limited to the departments it registered for', () => {
  it('shows a food-only store the Fashion categories too, with no request-a-department row', async () => {
    await mockApi().setActiveOrg(MOCK_FOOD_ORG);
    await renderScreen(<ProductForm />);
    const box = await screen.findByRole('combobox', { name: 'Category' });
    fireEvent.change(box, { target: { value: 'kaftans' } });
    expect(await screen.findByRole('option', { name: /Kaftans & shirts/ })).toBeInTheDocument();
    expect(screen.queryByText(/Request a department/)).not.toBeInTheDocument();
  });

  it('still loads the categories when the department lookup is down', async () => {
    getSellerDb().scopeUnavailable = true;
    await renderScreen(<ProductForm />);
    expect(await screen.findByRole('combobox', { name: 'Category' })).toBeInTheDocument();
    expect(screen.queryByText('We couldn’t load the categories')).not.toBeInTheDocument();
  });

  it('has no Departments settings, only the separate way to apply for services', async () => {
    await mockApi().setActiveOrg(MOCK_FASHION_ORG);
    await renderScreen(<OfferServices />);
    expect(screen.queryByRole('region', { name: 'Departments' })).not.toBeInTheDocument();
    expect(await screen.findByRole('region', { name: 'Provide services' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Open Provider Hub' })).toBeInTheDocument();
    expect((await mockApi().getCatalogScope()).exempt).toBe(true);
  });

  it('a seller with no services account is offered the separate application on the same sign-in', async () => {
    getSellerDb().orgs = getSellerDb().orgs.filter((o) => o.kind === 'SELLER');
    await mockApi().setActiveOrg(MOCK_FASHION_ORG);
    await renderScreen(<OfferServices />);
    expect(await screen.findByRole('button', { name: 'Apply to provide services' })).toBeInTheDocument();
  });

  it('offers a food-only store every department’s upload template in bulk upload', async () => {
    await mockApi().setActiveOrg(MOCK_FOOD_ORG);
    await renderScreen(<BulkUploadView />);
    const select = await screen.findByLabelText('Template for');
    await waitFor(() => expect(within(select).getAllByRole('option').length).toBeGreaterThan(1));
    const labels = within(select).getAllByRole('option').map((o) => o.textContent ?? '');
    expect(labels.some((l) => l.startsWith('Groceries'))).toBe(true);
    expect(labels.some((l) => l.startsWith('Fashion'))).toBe(true);
    expect(screen.queryByRole('button', { name: 'Need another department? Request it' })).not.toBeInTheDocument();
  });
});

describe('the category list fails on its own, in words about categories', () => {
  it('says the categories could not be loaded, and retries', async () => {
    getSellerDb().categoriesUnavailable = true;
    await renderScreen(<ProductForm />);
    expect(await screen.findByText('We couldn’t load the categories')).toBeInTheDocument();
    getSellerDb().categoriesUnavailable = false;
    fireEvent.click(screen.getByRole('button', { name: 'Retry' }));
    expect(await screen.findByRole('combobox', { name: 'Category' })).toBeInTheDocument();
  });
});
