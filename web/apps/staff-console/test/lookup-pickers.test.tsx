import { resetStaffDb } from '@afrotransact/api-client/mocks/staff';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { FormFields } from '@/components/resources/FormFields';
import { ResourceTable } from '@/components/resources/ResourceTable';

import { asRoles, mockApi } from './mock-api';
import { renderScreen } from './render';
import { nav } from './setup';

/* Forms that need a driver or a seller pick them by name (searchable), not by typed id: Assign driver on a route and the
   Stripe lookup's seller. The form still sends the id. */

beforeEach(() => {
  resetStaffDb(new Date());
  asRoles(null);
});

describe('searchable pickers in forms', () => {
  it('Assign driver finds the driver by name and sends the id', async () => {
    const user = userEvent.setup();
    const api = mockApi();
    const record = await api.getRecord('routes', 'rte-1000');
    const assign = record.actions.find((a) => a.key === 'assign');
    expect(assign?.form?.fields.find((f) => f.key === 'driverId')).toMatchObject({ lookup: 'drivers', label: 'Driver', required: true });
    const onSubmit = vi.fn();
    await renderScreen(<FormFields form={assign!.form!} onSubmit={onSubmit} />);

    // Nothing is chosen yet: the form refuses, in words about the driver.
    await user.click(screen.getByRole('button', { name: 'Assign' }));
    expect(onSubmit).not.toHaveBeenCalled();

    const picker = screen.getByTestId('lookup-driverId');
    await user.type(within(picker).getByRole('textbox', { name: /Driver/ }), 'zain');
    await waitFor(() => expect(within(within(picker).getByTestId('lookup-hits')).getAllByRole('button')).toHaveLength(1));
    await user.click(within(picker).getByRole('button', { name: /Zainab Yusuf/ }));
    // The name shows, not the id, with a way to choose another.
    expect(within(screen.getByTestId('lookup-driverId')).getByText('Zainab Yusuf')).toBeInTheDocument();
    expect(screen.getByTestId('lookup-driverId')).not.toHaveTextContent('drv-3');
    await user.click(screen.getByRole('button', { name: 'Assign' }));
    await waitFor(() => expect(onSubmit).toHaveBeenCalledWith({ driverId: 'drv-3' }));

    await user.click(screen.getByRole('button', { name: 'Choose another driver' }));
    expect(within(screen.getByTestId('lookup-driverId')).getByRole('textbox', { name: /Driver/ })).toBeInTheDocument();
  });

  it('lists the first matches before anything is typed, and says when nothing matches', async () => {
    const user = userEvent.setup();
    const form = (await mockApi().getRecord('routes', 'rte-1000')).actions.find((a) => a.key === 'assign')!.form!;
    await renderScreen(<FormFields form={form} onSubmit={vi.fn()} />);
    const picker = screen.getByTestId('lookup-driverId');
    expect(await within(picker).findAllByRole('button')).toHaveLength(3);
    await user.type(within(picker).getByRole('textbox', { name: /Driver/ }), 'nobody');
    expect(await within(picker).findByText(/No driver found/)).toBeInTheDocument();
  });

  it('the Stripe lookup asks for the seller by name, never offering the retail account', async () => {
    const user = userEvent.setup();
    nav.set('/payouts?tab=stripe-lookup');
    const form = (await mockApi().listResource('stripe-lookup', { page: 0, size: 10 })).createForm;
    expect(form?.fields.find((f) => f.key === 'orgId')).toMatchObject({ lookup: 'sellers', required: false });
    await renderScreen(<FormFields form={form!} onSubmit={vi.fn()} />);
    const picker = screen.getByTestId('lookup-orgId');
    const hits = await within(picker).findByTestId('lookup-hits');
    expect(within(hits).queryByText(/Retail/)).not.toBeInTheDocument();
    await user.type(within(picker).getByRole('textbox', { name: /Seller/ }), 'kente');
    await waitFor(() => expect(within(screen.getByTestId('lookup-hits')).getAllByRole('button')).toHaveLength(1));
  });

  it('keeps people without the module out', async () => {
    asRoles('platform.trust_safety');
    await expect(mockApi().lookup('drivers', 'tun')).rejects.toMatchObject({ status: 403 });
    await expect(mockApi().lookup('sellers', '')).rejects.toMatchObject({ status: 403 });
  });

  it('is not part of tables that have no pickers', async () => {
    nav.set('/payouts');
    await renderScreen(<ResourceTable resource="payouts" />);
    expect(screen.queryByTestId('lookup-hits')).not.toBeInTheDocument();
  });
});
