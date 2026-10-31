import { resetStaffDb } from '@afrotransact/api-client/mocks/staff';
import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it } from 'vitest';

import { IdentityAdminView } from '@/components/identity/IdentityAdminView';
import { columnLabel } from '@/components/resources/cells';
import { messagesFor } from '@/i18n/catalogs';
import { tEn, translate } from '@/i18n/t';

import { asRoles } from './mock-api';
import { renderScreen } from './render';
import { nav } from './setup';

describe('Identity sessions › Device column (S291)', () => {
  beforeEach(() => {
    resetStaffDb(new Date());
    asRoles(null);
    nav.set('/identity');
  });

  it('shows the parsed browser and system of each sign-in, and a dash when none was recorded', async () => {
    const user = userEvent.setup();
    await renderScreen(<IdentityAdminView />);
    await user.click(await screen.findByRole('tab', { name: 'Sessions & tokens' }));
    const table = await screen.findByRole('table', { name: 'Sessions & tokens' });
    expect(await within(table).findByRole('columnheader', { name: /Device/ })).toBeInTheDocument();
    expect((await within(table).findAllByText('Chrome on macOS')).length).toBeGreaterThanOrEqual(2);
    expect(within(table).getByText('Safari on iOS')).toBeInTheDocument();
    expect(within(table).getByText('Firefox on Windows')).toBeInTheDocument();
  });

  it('opens the sheet with the device and the raw user agent', async () => {
    const user = userEvent.setup();
    await renderScreen(<IdentityAdminView />);
    await user.click(await screen.findByRole('tab', { name: 'Sessions & tokens' }));
    const table = await screen.findByRole('table', { name: 'Sessions & tokens' });
    await user.click(await within(table).findByText('Kwame Mensah'));
    expect(await screen.findByText(/Firefox\/125\.0/)).toBeInTheDocument();
  });

  it('names the column and joins browser and system in every language', () => {
    const expected = { en: 'Chrome on macOS', fr: 'Chrome sur macOS', es: 'Chrome en macOS', ar: 'Chrome على macOS', yo: 'Chrome lórí macOS' } as const;
    for (const [locale, text] of Object.entries(expected) as [keyof typeof expected, string][]) {
      expect(translate(locale, 'st.device.on', { browser: 'Chrome', os: 'macOS' }, messagesFor(locale))).toBe(text);
      expect(translate(locale, 'st.col.device', undefined, messagesFor(locale))).not.toBe('');
    }
    expect(columnLabel(tEn, { key: 'device', label: 'Device' })).toBe('Device');
    expect(columnLabel(tEn, { key: 'ipAddress', label: 'IP address' })).toBe('IP address');
  });
});
