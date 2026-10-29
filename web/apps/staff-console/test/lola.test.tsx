import { resetStaffDb } from '@afrotransact/api-client/mocks/staff';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it } from 'vitest';

import { StaffLolaSheet } from '@/components/assistant/StaffLolaSheet';
import { recordHref } from '@/lib/modules';

import { renderScreen } from './render';

describe('Lola in the staff console', () => {
  beforeEach(() => resetStaffDb(new Date()));

  it('links a record to the page that shows its table, on the right tab', () => {
    expect(recordHref('orders', 'AT-2026-000123')).toBe('/orders?open=AT-2026-000123');
    expect(recordHref('connect-accounts', 'org-9')).toBe('/organisations?tab=connect-accounts&open=org-9');
    expect(recordHref('no-such-table', 'x')).toBeNull();
  });

  it('answers a payout question with what she looked up and a button to open the record', async () => {
    const user = userEvent.setup();
    await renderScreen(<StaffLolaSheet open onOpenChange={() => undefined} />);
    expect(await screen.findByText(/messages left today/)).toBeInTheDocument();
    await user.type(screen.getByLabelText('Ask Lola about the platform…'), 'Why has Kente Naturals not been paid?');
    await user.click(screen.getByRole('button', { name: 'Send' }));
    expect(await screen.findByText(/Payouts are paused: Stripe needs overdue information/)).toBeInTheDocument();
    expect(screen.getByText(/Checking payouts of Kente Naturals/)).toBeInTheDocument();
    const open = screen.getByRole('link', { name: 'Open Kente Naturals Stripe account' });
    expect(open.getAttribute('href')).toContain('open=org-9');
    await waitFor(() => expect(screen.getByTestId('lola-remaining')).toHaveTextContent('199 of 200'));
  });
});
