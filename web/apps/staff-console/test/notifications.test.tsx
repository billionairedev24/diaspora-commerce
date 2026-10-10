import { resetStaffDb } from '@afrotransact/api-client/mocks/staff';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it } from 'vitest';

import { NotificationsView } from '@/components/notifications/NotificationsView';
import { diffLines } from '@/lib/diff';

import { asRoles } from './mock-api';
import { renderScreen } from './render';
import { nav } from './setup';

describe('line diff', () => {
  it('keeps common lines and marks removed and added ones', () => {
    expect(diffLines('a\nb\nc', 'a\nB\nc\nd')).toEqual([
      { kind: 'same', text: 'a' },
      { kind: 'removed', text: 'b' },
      { kind: 'added', text: 'B' },
      { kind: 'same', text: 'c' },
      { kind: 'added', text: 'd' }
    ]);
    expect(diffLines('', 'x')).toEqual([{ kind: 'added', text: 'x' }]);
    expect(diffLines('same', 'same').every((l) => l.kind === 'same')).toBe(true);
  });
});

describe('Notifications → Templates (S268)', () => {
  beforeEach(() => {
    resetStaffDb(new Date('2026-10-10T15:00:00Z'));
    nav.set('/notifications');
  });

  it('lists templates read-only for support and searches on the BFF', async () => {
    asRoles('platform.customer_support');
    const user = userEvent.setup();
    await renderScreen(<NotificationsView />);
    const table = await screen.findByRole('table', { name: 'Notification templates' });
    expect(within(table).getByRole('link', { name: 'order.confirmed' })).toHaveAttribute('href', '/notifications?key=order.confirmed');
    expect(screen.getByText(/can view notifications but not change it/)).toBeInTheDocument();
    await user.type(screen.getByRole('searchbox', { name: 'Search templates' }), 'security');
    await waitFor(() => expect(screen.getAllByTestId('template-row')).toHaveLength(1));
    expect(screen.getByRole('link', { name: 'identity.verification' })).toBeInTheDocument();
  });

  it('previews a template with its sample data, shows SMS segments and hides edits without templates:write', async () => {
    asRoles('platform.customer_support');
    const user = userEvent.setup();
    nav.set('/notifications?key=booking.confirmed');
    await renderScreen(<NotificationsView />);
    expect(await screen.findByTestId('template-key')).toHaveTextContent('booking.confirmed');
    expect(screen.getByText('booking.confirmed', { selector: 'li' })).toBeInTheDocument(); // sent on the booking.confirmed event
    expect(await screen.findByTestId('preview-subject')).toHaveTextContent('Booking confirmed: Knotless braids on Sat, Oct 17 · 10:00 AM');
    expect(screen.queryByRole('button', { name: 'New version' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Send test to me' })).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'SMS · en · v1' }));
    const count = await screen.findByTestId('sms-count');
    expect(count).toHaveTextContent('UCS-2');
    expect(count).toHaveTextContent('2 segments');
  });

  it('compares versions in the history', async () => {
    asRoles('platform.customer_support');
    nav.set('/notifications?key=order.confirmed');
    await renderScreen(<NotificationsView />);
    expect(await screen.findAllByTestId('template-version')).toHaveLength(2);
    const subject = within(screen.getByTestId('version-diff')).getByRole('region', { name: 'Subject' });
    expect(within(subject).getByText('Order {{.orderNumber}} confirmed', { exact: false, selector: 'del' })).toBeInTheDocument();
    expect(within(subject).getByText('Order {{.orderNumber}} is confirmed', { exact: false, selector: 'ins' })).toBeInTheDocument();
  });

  it('creates a version: live draft preview, placeholder check against the sample data, then saved', async () => {
    asRoles('platform.marketing');
    const user = userEvent.setup();
    nav.set('/notifications?key=order.confirmed');
    await renderScreen(<NotificationsView />);
    await user.click(await screen.findByRole('button', { name: 'SMS · en · v2' }));
    await user.click(screen.getByRole('button', { name: 'New version' }));
    const message = screen.getByRole('textbox', { name: 'Message' });
    await user.clear(message);
    await user.click(message);
    await user.paste('AfroTransact: {{.nickname}}, order {{.orderNumber}} ({{.total}}) is in.');
    expect(await screen.findByText('Not in the sample data: nickname')).toBeInTheDocument();
    await user.clear(message);
    await user.paste('AfroTransact: order {{.orderNumber}} ({{.total}}) is in. Reply STOP to opt out.');
    await waitFor(() => expect(screen.getByTestId('preview-sms')).toHaveTextContent('AfroTransact: order AT-2026-7KQ3MD ($48.00) is in.'));
    expect(screen.getByTestId('sms-count')).toHaveTextContent('GSM-7');
    await user.click(screen.getByRole('button', { name: 'Create version 3' }));
    expect(await screen.findByText('Version 3 created')).toBeInTheDocument();
    expect(await screen.findByRole('button', { name: 'SMS · en · v3' })).toBeInTheDocument();
  });
});
