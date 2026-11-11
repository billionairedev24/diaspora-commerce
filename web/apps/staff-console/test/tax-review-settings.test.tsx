import { resetStaffDb } from '@afrotransact/api-client/mocks/staff';
import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { TaxReviewSettingsPanel } from '@/components/tax/TaxReviewSettingsPanel';

import { asRoles, interceptStaff } from './mock-api';
import { renderScreen } from './render';
import { nav } from './setup';

const NOW = new Date('2026-11-09T12:00:00Z');

describe('Tax review settings', () => {
  beforeEach(() => {
    resetStaffDb(NOW);
    asRoles('platform.super_admin');
    nav.set('/fees?tab=tax-review-settings');
  });
  afterEach(() => interceptStaff(null));

  it('is off by default: every item is reviewed, and a plain warning says to wait for the tax professional', async () => {
    await renderScreen(<TaxReviewSettingsPanel />);
    expect(await screen.findByRole('heading', { name: 'Review every item is in force' })).toBeInTheDocument();
    const group = screen.getByRole('group', { name: 'How tax review works' });
    expect(within(group).getByRole('radio', { name: /Review every item \(in use now\)/ })).toBeChecked();
    expect(within(group).getByRole('radio', { name: /Review by risk/ })).not.toBeChecked();
    expect(screen.getByText('Switch on only after the tax professional agrees')).toBeInTheDocument();
    expect(screen.getByText(/Only switch to Review by risk after the Texas tax professional has agreed/)).toBeInTheDocument();
    expect(screen.getByRole('spinbutton', { name: /Audit sample/ })).toHaveValue(5);
  });

  it('shows the always-review categories by name, with the default list ticked and gated categories locked', async () => {
    await renderScreen(<TaxReviewSettingsPanel />);
    const list = await screen.findByRole('list', { name: 'Categories' });
    for (const name of ['Dietary supplements', 'OTC medicines', 'Medicated skincare', 'Medical devices', 'Baby food & formula', 'Gift cards']) {
      expect(within(list).getByRole('checkbox', { name: new RegExp(name) })).toBeChecked();
    }
    expect(within(list).getByRole('checkbox', { name: /Skincare & cosmetics/ })).not.toBeChecked();
    const gated = within(list).getByRole('checkbox', { name: /Audiobooks/ });
    expect(gated).toBeChecked();
    expect(gated).toBeDisabled();
    expect(within(list).getAllByText(/Always reviewed:/).length).toBeGreaterThan(5);
    expect(list).not.toHaveTextContent(/dietary-supplements|otc-medicines/);
    await userEvent.setup().type(screen.getByLabelText('Find a category'), 'haircare');
    expect(within(list).getAllByRole('checkbox')).toHaveLength(1);
  });

  it('switches to review by risk only with a reason of ten characters, then records it in the history by name', async () => {
    const user = userEvent.setup();
    await renderScreen(<TaxReviewSettingsPanel />);
    await screen.findByRole('heading', { name: 'Review every item is in force' });
    const save = screen.getByRole('button', { name: 'Save changes…' });
    expect(save).toBeDisabled();
    await user.click(screen.getByRole('radio', { name: /Review by risk/ }));
    await user.click(within(screen.getByRole('list', { name: 'Categories' })).getByRole('checkbox', { name: /Haircare/ }));
    const percent = screen.getByRole('spinbutton', { name: /Audit sample/ });
    await user.clear(percent);
    await user.type(percent, '10');
    await user.click(save);
    const dialog = await screen.findByRole('dialog', { name: 'Change how tax review works?' });
    expect(within(dialog).getByText('Has the tax professional agreed?')).toBeInTheDocument();
    const change = within(dialog).getByRole('button', { name: 'Change' });
    expect(change).toBeDisabled();
    await user.type(within(dialog).getByLabelText('Reason'), 'Short');
    expect(change).toBeDisabled();
    await user.type(within(dialog).getByLabelText('Reason'), ' and agreed with the tax professional');
    expect(change).toBeEnabled();
    await user.click(change);
    expect(await screen.findByRole('heading', { name: 'Review by risk is in force' })).toBeInTheDocument();
    const history = screen.getByRole('region', { name: 'History' });
    expect(within(history).getByText(/Review by risk, 10% audit sample, always review: .*Haircare/)).toBeInTheDocument();
    expect(within(history).getByText(/Before: Review every item/)).toBeInTheDocument();
    expect(within(history).getByText(/Short and agreed with the tax professional/)).toBeInTheDocument();
    expect(history).not.toHaveTextContent(/haircare,|usr_|staff_/);
  });

  it('refuses a percentage that is not a whole number from 0 to 100', async () => {
    const user = userEvent.setup();
    await renderScreen(<TaxReviewSettingsPanel />);
    const percent = await screen.findByRole('spinbutton', { name: /Audit sample/ });
    await user.clear(percent);
    await user.type(percent, '101');
    expect(screen.getByText('Use a whole number from 0 to 100.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Save changes…' })).toBeDisabled();
  });

  it('is read only without tax:classify-admin', async () => {
    asRoles('platform.auditor');
    await renderScreen(<TaxReviewSettingsPanel />);
    expect(await screen.findByText('Read only')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Save changes…' })).not.toBeInTheDocument();
    expect(screen.getByRole('radio', { name: /Review by risk/ })).toBeDisabled();
  });
});
