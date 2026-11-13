import { resetStaffDb } from '@afrotransact/api-client/mocks/staff';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it } from 'vitest';

import { ResourceTable } from '@/components/resources/ResourceTable';

import { asRoles } from './mock-api';
import { renderScreen } from './render';
import { nav } from './setup';

/* Seeded products, in order: 0 in review, 1 held for tax, 2-4 live, 5 rejected, 6 taken down, 7 paused, 8-9 in review, 10-12 live, 13 rejected. */
describe('Products: bulk actions follow the catalog state machine', () => {
  beforeEach(() => {
    resetStaffDb(new Date());
    asRoles('platform.trust_safety');
    nav.set('/catalog');
  });

  const rowBoxes = () => screen.getAllByRole('checkbox').slice(1);

  it('offers Approve N of M for a mixed selection and names what is skipped', async () => {
    const user = userEvent.setup();
    await renderScreen(<ResourceTable resource="products" />);
    await screen.findByRole('table', { name: 'Products' });
    await user.click(await screen.findByRole('checkbox', { name: 'Select all rows on this page' }));
    const bar = within(screen.getByRole('region', { name: 'Selection' }));
    expect(bar.getByRole('button', { name: 'Approve 4 of 14' })).toBeEnabled();
    expect(bar.getByRole('button', { name: 'Restore 1 of 14' })).toBeEnabled();
    await user.click(bar.getByRole('button', { name: 'Approve 4 of 14' }));
    const dialog = await screen.findByRole('dialog', { name: 'Approve 4 of 14' });
    expect(within(dialog).getByText(/Skipping .*6 already live/)).toBeInTheDocument();
  });

  it('disables Approve with a plain reason when every selected product is already live', async () => {
    const user = userEvent.setup();
    await renderScreen(<ResourceTable resource="products" />);
    await screen.findByRole('table', { name: 'Products' });
    await user.click(rowBoxes()[2]!);
    await user.click(rowBoxes()[3]!);
    const approve = within(screen.getByRole('region', { name: 'Selection' })).getByRole('button', { name: 'Approve' });
    expect(approve).toBeDisabled();
    expect(approve).toHaveAttribute('title', 'Approve is not possible for the selected rows: 2 already live.');
  });

  it('approves only the eligible rows and reports each by name', async () => {
    const user = userEvent.setup();
    await renderScreen(<ResourceTable resource="products" />);
    await screen.findByRole('table', { name: 'Products' });
    await user.click(rowBoxes()[0]!);
    await user.click(rowBoxes()[2]!);
    const bar = within(screen.getByRole('region', { name: 'Selection' }));
    await user.click(bar.getByRole('button', { name: 'Approve 1 of 2' }));
    const dialog = await screen.findByRole('dialog', { name: 'Approve 1 of 2' });
    await user.click(within(dialog).getByRole('button', { name: 'Approve' }));
    const result = await screen.findByTestId('bulk-result');
    expect(within(result).getByText('Done (1)')).toBeInTheDocument();
    expect(within(result).getByText('Skipped (1)')).toBeInTheDocument();
    expect(within(result).getByText(/— already live/)).toBeInTheDocument();
    await waitFor(() => expect(within(result).queryByText(/Failed/)).toBeNull());
  });
});
