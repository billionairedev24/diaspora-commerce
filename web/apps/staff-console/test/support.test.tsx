import { resetStaffDb } from '@afrotransact/api-client/mocks/staff';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it } from 'vitest';

import { draftOf, valuesOf } from '@/components/resources/FormFields';
import { CustomerPanel } from '@/components/support/CustomerPanel';

import { asRoles, mockApi } from './mock-api';
import { renderScreen } from './render';
import { nav } from './setup';

describe('customer support console', () => {
  beforeEach(() => {
    resetStaffDb(new Date());
    asRoles('platform.customer_support');
    nav.set('/support?customer=cus-1000');
  });

  it('keeps refunds within the role limit and records them', async () => {
    const user = userEvent.setup();
    await renderScreen(<CustomerPanel customerId="cus-1000" />);
    expect(await screen.findByText(/Refund limit: \$250\.00/)).toBeInTheDocument();
    expect(screen.getByText(/bookings\. Those sections stay empty/)).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Refund…' }));
    const dialog = await screen.findByRole('dialog', { name: 'Refund the customer' });
    await user.type(within(dialog).getByLabelText('Amount ($)'), '300');
    await user.type(within(dialog).getByLabelText(/Reason/), 'Late delivery');
    await user.click(within(dialog).getByRole('button', { name: 'Refund' }));
    expect(within(dialog).getByRole('link', { name: 'Your roles can refund up to $250.00' })).toHaveAttribute('href', '#refund-amount');
    await user.clear(within(dialog).getByLabelText('Amount ($)'));
    await user.type(within(dialog).getByLabelText('Amount ($)'), '12.50');
    await user.click(within(dialog).getByRole('button', { name: 'Refund' }));
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Refund the customer' })).not.toBeInTheDocument());
    const c360 = await mockApi().getCustomer('cus-1000');
    expect(c360.audit[0]?.action).toBe('refund.issued');
  }, 20_000); // many keystrokes: generous under a parallel `pnpm -r test`

  it('hides actions the roles cannot run', async () => {
    asRoles('platform.auditor');
    await renderScreen(<CustomerPanel customerId="cus-1000" />);
    await screen.findByText(/Refund limit/);
    expect(screen.queryByRole('button', { name: 'Refund…' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Resend' })).not.toBeInTheDocument();
  });
});

describe('form values', () => {
  it('turns dollars into minor units and local date-times into ISO', () => {
    const form = {
      title: 't',
      submitLabel: 's',
      fields: [
        { key: 'fee', label: 'Fee', type: 'money' as const, required: true, value: 1250 },
        { key: 'at', label: 'At', type: 'datetime' as const, required: true },
        { key: 'on', label: 'On', type: 'toggle' as const, required: false, value: true },
        { key: 'note', label: 'Note', type: 'text' as const, required: false }
      ]
    };
    const draft = draftOf(form);
    expect(draft.fee).toBe('12.50');
    const values = valuesOf(form, { ...draft, at: '2026-10-10T09:30', fee: '13.00' });
    expect(values.fee).toBe(1300);
    expect(typeof values.at === 'string' && values.at.endsWith('Z')).toBe(true);
    expect(values.on).toBe(true);
    expect('note' in values).toBe(false);
  });
});
