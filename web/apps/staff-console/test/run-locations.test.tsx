import { resetStaffDb } from '@afrotransact/api-client/mocks/staff';
import { cleanup, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { RunsView } from '@/components/runs/RunsView';

import { asRoles } from './mock-api';
import { renderScreen } from './render';

/* Delivery-run locations from the staff console (S262) against the staff mocks: add a town with a radius and defaults,
   change it, switch it off with an audited reason (refused while a run is live), switch it back on, and read-only for
   everyone else. */

const NOW = new Date('2026-10-10T15:00:00Z');

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(NOW);
  resetStaffDb(NOW);
  asRoles(null);
});

afterEach(() => {
  cleanup();
  vi.useRealTimers();
});

const fill = async (user: ReturnType<typeof userEvent.setup>, form: HTMLElement, label: string, value: string) => {
  const field = within(form).getByLabelText(label);
  await user.clear(field);
  if (value) await user.type(field, value);
};

describe('adding and changing a location', () => {
  it('refuses a town field by field, then adds it with its radius and defaults and shows it in the list', async () => {
    const user = userEvent.setup();
    await renderScreen(<RunsView />);
    await user.click(await screen.findByRole('button', { name: 'Add location' }));
    const form = await screen.findByTestId('location-form');
    await user.click(within(form).getByRole('button', { name: 'Add location' }));
    expect(within(form).getAllByText('This is required').length).toBeGreaterThan(0);
    await fill(user, form, 'Town name', 'Bastrop');
    await fill(user, form, 'State or region', 'TX');
    await fill(user, form, 'Centre latitude', '30.1105');
    await fill(user, form, 'Centre longitude', '-97.3153');
    await fill(user, form, 'Radius (miles)', '120');
    await user.click(within(form).getByRole('button', { name: 'Add location' }));
    expect(await within(form).findByText('Outside the allowed range')).toBeInTheDocument();
    await fill(user, form, 'Radius (miles)', '12');
    await fill(user, form, 'Minimum orders', '10');
    await fill(user, form, 'Community (WhatsApp) link', 'https://chat.whatsapp.com/AbCdEf123');
    await user.click(within(form).getByRole('button', { name: 'Add location' }));
    await waitFor(() => expect(screen.queryByTestId('location-form')).not.toBeInTheDocument());
    // The new town is chosen, and its name (never an id) reads in the list.
    expect(await screen.findByRole('combobox', { name: 'Location' })).toHaveDisplayValue('Bastrop');
    expect(await screen.findByRole('heading', { name: /Bastrop/ })).toBeInTheDocument();
  });

  it('changes a town without switching it, and saves only once something changed', async () => {
    const user = userEvent.setup();
    await renderScreen(<RunsView />);
    await user.click(await screen.findByRole('button', { name: 'Edit location' }));
    const form = await screen.findByTestId('location-form');
    expect(within(form).getByLabelText('Town name')).toHaveValue('Lubbock');
    expect(within(form).getByLabelText('Radius (miles)')).toHaveValue('15');
    expect(within(form).getByRole('button', { name: 'Save changes' })).toBeDisabled();
    await fill(user, form, 'Minimum orders', '14');
    await user.click(within(form).getByRole('button', { name: 'Save changes' }));
    await waitFor(() => expect(screen.queryByTestId('location-form')).not.toBeInTheDocument());
    await user.click(await screen.findByRole('button', { name: 'Edit location' }));
    expect(await within(await screen.findByTestId('location-form')).findByLabelText('Minimum orders')).toHaveValue('14');
    expect(screen.queryByTestId('location-off-notice')).not.toBeInTheDocument();
  });

  it('keeps a saved drawn area unless a new outline is pasted', async () => {
    const user = userEvent.setup();
    await renderScreen(<RunsView />);
    await user.selectOptions(await screen.findByRole('combobox', { name: 'Location' }), 'Amarillo');
    await user.click(await screen.findByRole('button', { name: 'Edit location' }));
    const form = await screen.findByTestId('location-form');
    expect(within(form).getByRole('checkbox', { name: 'Keep the saved area' })).toBeChecked();
    expect(within(form).queryByLabelText('Area outline (GeoJSON)')).not.toBeInTheDocument();
    await user.click(within(form).getByRole('checkbox', { name: 'Keep the saved area' }));
    await user.click(within(form).getByRole('button', { name: 'Save changes' }));
    expect(await within(form).findByText('Paste the area outline, or keep the saved area')).toBeInTheDocument();
  });
});

describe('switching a location off and on', () => {
  it('needs a reason, is refused while a run is live, and works once the town has no live run', async () => {
    const user = userEvent.setup();
    await renderScreen(<RunsView />);
    await user.click(await screen.findByRole('button', { name: 'Add location' }));
    const form = await screen.findByTestId('location-form');
    await fill(user, form, 'Town name', 'Bastrop');
    await fill(user, form, 'Centre latitude', '30.1105');
    await fill(user, form, 'Centre longitude', '-97.3153');
    await user.click(within(form).getByRole('button', { name: 'Add location' }));
    await waitFor(() => expect(screen.queryByTestId('location-form')).not.toBeInTheDocument());

    await user.click(await screen.findByRole('button', { name: 'Switch off' }));
    const dialog = await screen.findByRole('dialog', { name: 'Switch off Bastrop?' });
    await user.click(within(dialog).getByRole('button', { name: 'Switch off location' }));
    expect(await within(dialog).findByText('Enter a reason.')).toBeInTheDocument();
    await user.type(within(dialog).getByLabelText('Why is it being switched off?'), 'Driver moved away');
    await user.click(within(dialog).getByRole('button', { name: 'Switch off location' }));
    expect(await screen.findByTestId('location-off-notice')).toHaveTextContent('Bastrop is switched off');
    expect(screen.getByRole('combobox', { name: 'Location' })).toHaveDisplayValue('Bastrop (switched off)');

    await user.click(screen.getByRole('button', { name: 'Switch on' }));
    const back = await screen.findByRole('dialog', { name: 'Switch Bastrop back on?' });
    await user.click(within(back).getByRole('button', { name: 'Switch on location' }));
    await waitFor(() => expect(screen.queryByTestId('location-off-notice')).not.toBeInTheDocument());

    // Lubbock has a run taking orders: it stays on.
    await user.selectOptions(screen.getByRole('combobox', { name: 'Location' }), 'Lubbock');
    await user.click(await screen.findByRole('button', { name: 'Switch off' }));
    const refused = await screen.findByRole('dialog', { name: 'Switch off Lubbock?' });
    await user.type(within(refused).getByLabelText('Why is it being switched off?'), 'Closing');
    await user.click(within(refused).getByRole('button', { name: 'Switch off location' }));
    expect(await within(refused).findByTestId('action-failure')).toHaveTextContent(/announced, takes orders or is on the road/i);
  });

  it('is read-only for roles that cannot manage pre-orders', async () => {
    asRoles('platform.finance');
    await renderScreen(<RunsView />);
    for (const name of ['Add location', 'Edit location', 'Switch off']) {
      const button = await screen.findByRole('button', { name });
      expect(button).toBeDisabled();
      expect(button).toHaveAttribute('title', 'Your role can read locations but not change them.');
    }
  });
});
