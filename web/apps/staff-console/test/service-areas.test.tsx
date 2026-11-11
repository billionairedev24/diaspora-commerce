import { resetStaffDb } from '@afrotransact/api-client/mocks/staff';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it } from 'vitest';

import { ResourceTable } from '@/components/resources/ResourceTable';

import { asRoles } from './mock-api';
import { renderScreen } from './render';
import { nav } from './setup';

/* Settings > Markets & locations > Service areas: the default search radius by category and the metro zones, every change with a reason. */

describe('service areas (staff)', () => {
  beforeEach(() => {
    resetStaffDb(new Date('2026-11-11T12:00:00Z'));
    asRoles(null);
    nav.set('/markets');
  });

  it('lists the default search radius per category, with no customer travel limit anywhere', async () => {
    await renderScreen(<ResourceTable resource="service-area-rules" urlPrefix="service-area-rules" />);
    const table = await screen.findByRole('table', { name: 'Default search radius by category' });
    const hair = within(table).getByRole('row', { name: /Hair & beauty/ });
    expect(within(hair).getByText('20')).toBeInTheDocument();
    expect(within(hair).getByText('40')).toBeInTheDocument();
    for (const [name, miles] of [['Catering', '40'], ['Tailoring', '25'], ['Tax & legal', '30'], ['Any other category', '25']] as const) {
      expect(within(within(table).getByRole('row', { name: new RegExp(name) })).getAllByText(miles).length).toBeGreaterThan(0);
    }
    expect(within(table).getByText('Default search radius (mi)')).toBeInTheDocument();
    expect(within(table).getByText('Provider maximum (mi)')).toBeInTheDocument();
    expect(within(table).queryByText('Platform limits')).not.toBeInTheDocument();
    expect(table.textContent).not.toMatch(/travel limit|travel up to|nearest suggestion/i);
    expect(document.body.textContent).not.toMatch(/Customer travel limit|Nearest suggestions up to|Service radius rules/);
  });

  it('changes a rule only with a reason, and shows it on the record', async () => {
    const user = userEvent.setup();
    nav.set('/markets?open=catering');
    await renderScreen(<ResourceTable resource="service-area-rules" urlPrefix="service-area-rules" />);
    const sheet = await screen.findByRole('dialog', { name: /Catering/ });
    await user.click(within(sheet).getByRole('button', { name: 'Edit' }));
    const form = await screen.findByRole('dialog', { name: 'Default search radius for Catering' });
    expect(within(form).getByText(/it is not a limit, shoppers may pick any distance/)).toBeInTheDocument();
    expect(form.textContent).not.toMatch(/travel limit/i);
    const max = within(form).getByLabelText('Provider maximum radius (miles)');
    await user.clear(max);
    await user.type(max, '90');
    await user.click(within(form).getByRole('button', { name: 'Save' }));
    expect(within(form).getAllByText('Required').length).toBeGreaterThan(0);
    await user.type(within(form).getByLabelText('Reason (kept in the audit trail)'), 'Caterers rarely go beyond 90 miles');
    await user.click(within(form).getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Default search radius for Catering' })).not.toBeInTheDocument());
    expect(await screen.findByText(/Changed: Caterers rarely go beyond 90 miles/)).toBeInTheDocument();
  });

  it('lists the metro zones and creates one from “City, ST” lines', async () => {
    const user = userEvent.setup();
    await renderScreen(<ResourceTable resource="service-zones" urlPrefix="service-zones" />);
    const table = await screen.findByRole('table', { name: 'Metro zones' });
    const austin = within(table).getByRole('row', { name: /Greater Austin/ });
    expect(within(austin).getByText('greater-austin')).toBeInTheDocument();
    expect(within(austin).getByText(/Georgetown, TX/)).toBeInTheDocument();

    await user.click(await screen.findByRole('button', { name: 'New' }));
    const dialog = await screen.findByRole('dialog', { name: 'New metro zone' });
    await user.type(within(dialog).getByLabelText(/^Key/), 'san-antonio');
    await user.type(within(dialog).getByLabelText('Zone name'), 'San Antonio');
    await user.type(within(dialog).getByLabelText(/^Cities/), 'San Antonio TX');
    await user.type(within(dialog).getByLabelText('Reason (kept in the audit trail)'), 'New metro for Texas launch');
    await user.click(within(dialog).getByRole('button', { name: 'Create zone' }));
    const cities = within(dialog).getByLabelText(/^Cities/);
    await waitFor(() => expect(cities).toHaveAttribute('aria-invalid', 'true'));
    await user.clear(cities);
    await user.type(cities, 'San Antonio, TX');
    await user.click(within(dialog).getByRole('button', { name: 'Create zone' }));
    await waitFor(() => expect(nav.search).toContain('open=san-antonio'));
  });

  it('is read only for a role without the markets edit permission', async () => {
    asRoles('platform.operations_manager');
    await renderScreen(<ResourceTable resource="service-zones" urlPrefix="service-zones" />);
    await screen.findByRole('table', { name: 'Metro zones' });
    expect(screen.queryByRole('button', { name: 'New' })).not.toBeInTheDocument();
  });
});
