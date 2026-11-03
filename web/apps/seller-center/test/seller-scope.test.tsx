import { MOCK_FASHION_ORG, MOCK_FOOD_ORG, resetSellerDb, SELLER_ORG } from '@afrotransact/api-client/mocks/seller';
import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, describe, expect, it } from 'vitest';

import { BulkUploadView } from '@/components/listings/bulk/BulkUploadView';
import { PortalShell } from '@/components/shell/PortalShell';
import { SizeChartsGate } from '@/components/size-charts/SizeChartsGate';
import { SizeChartsView } from '@/components/size-charts/SizeChartsView';

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

describe('the sidebar follows the departments the store sells in', () => {
  it('a fashion store gets Size charts under Listings', async () => {
    await mockApi().setActiveOrg(MOCK_FASHION_ORG);
    renderWithProviders(<PortalShell><p>body</p></PortalShell>);
    await waitFor(async () => expect(await sidebarHrefs()).toContain('/listings/size-charts'));
  });

  it('a store that only sells food does not see Size charts at all', async () => {
    await mockApi().setActiveOrg(MOCK_FOOD_ORG);
    renderWithProviders(<PortalShell><p>body</p></PortalShell>);
    expect(await sidebarHrefs()).toContain('/listings');
    // Let the scope arrive, then look again.
    await mockApi().getCatalogScope();
    await new Promise((r) => setTimeout(r, 60));
    expect(await sidebarHrefs()).not.toContain('/listings/size-charts');
  });
});

describe('opening Size charts by its address', () => {
  it('explains, calmly and with the way forward, why a food-only store has nothing there', async () => {
    await mockApi().setActiveOrg(MOCK_FOOD_ORG);
    await renderScreen(<SizeChartsGate><SizeChartsView /></SizeChartsGate>);
    expect(await screen.findByText('Size charts are for clothing and shoes')).toBeInTheDocument();
    expect(screen.getByText(/Your store does not sell in a department with sized goods/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Need another department? Request it' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'New size chart' })).not.toBeInTheDocument();
  });

  it('opens as usual for a store with sized goods', async () => {
    await mockApi().setActiveOrg(MOCK_FASHION_ORG);
    await renderScreen(<SizeChartsGate><SizeChartsView /></SizeChartsGate>);
    expect(await screen.findByRole('button', { name: 'New size chart' })).toBeInTheDocument();
  });
});

describe('category pickers show only the store’s departments', () => {
  it('bulk upload offers the categories of Groceries to a food-only store, and Fashion as well to a multi-department one', async () => {
    await mockApi().setActiveOrg(MOCK_FOOD_ORG);
    const food = await renderScreen(<BulkUploadView />);
    const select = await screen.findByLabelText('Template for');
    await waitFor(() => expect(within(select).getAllByRole('option').length).toBeGreaterThan(1));
    const labels = within(select).getAllByRole('option').map((o) => o.textContent ?? '');
    expect(labels.some((l) => l.startsWith('Groceries'))).toBe(true);
    expect(labels.some((l) => l.startsWith('Fashion') || l.startsWith('Beauty') || l.startsWith('Books'))).toBe(false);
    food.unmount();

    await mockApi().setActiveOrg(SELLER_ORG);
    await renderScreen(<BulkUploadView />);
    const multi = await screen.findByLabelText('Template for');
    await waitFor(() => expect(within(multi).getAllByRole('option').some((o) => (o.textContent ?? '').startsWith('Fashion'))).toBe(true));
  });
});

describe('Need another department? Request it', () => {
  it('sends a request for a department the store does not have and says what it will also need', async () => {
    await mockApi().setActiveOrg(MOCK_FASHION_ORG);
    await renderScreen(<BulkUploadView />);
    fireEvent.click(await screen.findByRole('button', { name: 'Need another department? Request it' }));
    const dialog = within(await screen.findByRole('dialog'));
    const choose = await dialog.findByLabelText('Department');
    await waitFor(() => expect(within(choose).getAllByRole('option').length).toBeGreaterThan(1));
    // A department it already has is not offered.
    expect(within(choose).queryByRole('option', { name: 'Fashion' })).not.toBeInTheDocument();
    fireEvent.change(choose, { target: { value: 'groceries' } });
    fireEvent.click(dialog.getByRole('button', { name: 'Send request' }));
    expect(await dialog.findByText('To sell food you will also need to add your food permit.')).toBeInTheDocument();
    expect((await mockApi().getCatalogScope()).requests).toMatchObject([{ departmentCode: 'groceries', status: 'PENDING' }]);
  });
});
