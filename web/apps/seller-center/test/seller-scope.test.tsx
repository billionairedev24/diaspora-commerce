import { getSellerDb, MOCK_FASHION_ORG, MOCK_FOOD_ORG, MOCK_ORG_DEPARTMENTS, resetSellerDb, SELLER_ORG } from '@afrotransact/api-client/mocks/seller';
import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, describe, expect, it } from 'vitest';

import { DepartmentsSettings } from '@/components/account/DepartmentsSettings';
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

describe('Departments in the product form', () => {
  it('always ends the category list with a row to request a department', async () => {
    await mockApi().setActiveOrg(MOCK_FASHION_ORG);
    await renderScreen(<ProductForm />);
    const box = await screen.findByRole('combobox', { name: 'Category' });
    fireEvent.focus(box);
    const rows = await screen.findAllByRole('option');
    expect(rows.at(-1)).toHaveTextContent('Don’t see your category? Request a department');
    fireEvent.change(box, { target: { value: 'kaftans' } });
    const typed = await screen.findAllByRole('option');
    expect(typed.at(-1)).toHaveTextContent('Request a department');
    fireEvent.click(typed.at(-1) as HTMLElement);
    expect(await screen.findByRole('dialog', { name: 'Request another department' })).toBeInTheDocument();
  });

  it('is the main action when the store has no department yet', async () => {
    await mockApi().setActiveOrg(MOCK_FOOD_ORG);
    const saved = MOCK_ORG_DEPARTMENTS[MOCK_FOOD_ORG];
    MOCK_ORG_DEPARTMENTS[MOCK_FOOD_ORG] = [];
    try {
      await renderScreen(<ProductForm />);
      expect(await screen.findByText(/Your store has no department yet/)).toBeInTheDocument();
      expect(screen.queryByRole('combobox', { name: 'Category' })).not.toBeInTheDocument();
      fireEvent.click(screen.getByRole('button', { name: 'Request a department' }));
      expect(await screen.findByRole('dialog', { name: 'Request another department' })).toBeInTheDocument();
    } finally {
      MOCK_ORG_DEPARTMENTS[MOCK_FOOD_ORG] = saved ?? [];
    }
  });

  it('says plainly that the departments could not be checked, and retries', async () => {
    getSellerDb().scopeUnavailable = true;
    await renderScreen(<ProductForm />);
    expect(await screen.findByText('We couldn’t check which departments you sell in')).toBeInTheDocument();
    expect(screen.queryByText('Loading the product form…')).not.toBeInTheDocument();
    getSellerDb().scopeUnavailable = false;
    fireEvent.click(screen.getByRole('button', { name: 'Retry' }));
    expect(await screen.findByRole('combobox', { name: 'Category' })).toBeInTheDocument();
  });
});

describe('Settings › Departments', () => {
  it('lists approved, pending and declined departments and lets the seller request another', async () => {
    await mockApi().setActiveOrg(MOCK_FASHION_ORG);
    await mockApi().requestDepartment({ departmentCode: 'groceries' });
    getSellerDb().departmentRequests[MOCK_FASHION_ORG]?.push({ departmentCode: 'books', departmentName: 'Books', status: 'REJECTED', needs: [] });
    await renderScreen(<DepartmentsSettings />);
    const section = within(await screen.findByRole('region', { name: 'Departments' }));
    const rowOf = (name: string) => section.getByText(name).closest('li') as HTMLElement;
    await waitFor(() => expect(within(rowOf('Fashion')).getByText('Approved')).toBeInTheDocument());
    expect(within(rowOf('Groceries')).getByText('Pending')).toBeInTheDocument();
    expect(within(rowOf('Books')).getByText('Declined')).toBeInTheDocument();
    fireEvent.click(section.getByRole('button', { name: 'Request a department' }));
    expect(await screen.findByRole('dialog', { name: 'Request another department' })).toBeInTheDocument();
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
