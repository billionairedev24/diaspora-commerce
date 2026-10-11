import { type AccountProfile, type AlertPreferences } from '@afrotransact/api-client';
import { readDefaultDensity } from '@afrotransact/data-table';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { accountSectionOf, alertChanges, AlertsScreen, PreferencesScreen, ProfileScreen } from '../src';
import { fakeApi, renderWith } from './helpers';

beforeEach(() => window.localStorage.clear());

describe('preferences', () => {
  it('saves language, zone, formats and density, and makes compact the default table density', async () => {
    const user = userEvent.setup();
    const saveAccountPreferences = vi.fn(async (p: unknown) => p);
    const onLanguageChanged = vi.fn();
    renderWith(
      <PreferencesScreen showDensity onLanguageChanged={onLanguageChanged} />,
      fakeApi({ getAccountPreferences: vi.fn(async () => ({ locale: null, timeZone: null, dateFormat: 'AUTO', numberFormat: 'AUTO', tableDensity: 'COMFORTABLE' })), saveAccountPreferences })
    );
    await user.selectOptions(await screen.findByLabelText('Language'), 'fr');
    await user.selectOptions(screen.getByLabelText('Time zone'), 'America/Chicago');
    await user.click(screen.getByRole('radio', { name: /Day, month, year/ }));
    await user.click(screen.getByRole('radio', { name: /1\.234,56/ }));
    await user.click(screen.getByRole('radio', { name: 'Compact' }));
    await user.click(screen.getByRole('button', { name: 'Save changes' }));
    await waitFor(() => expect(saveAccountPreferences).toHaveBeenCalledWith({ locale: 'fr-FR', timeZone: 'America/Chicago', dateFormat: 'DMY', numberFormat: 'DOT_COMMA', tableDensity: 'COMPACT' }));
    await waitFor(() => expect(readDefaultDensity()).toBe('compact'));
    expect(onLanguageChanged).toHaveBeenCalledWith('fr');
    expect(document.cookie).toContain('at_locale=fr');
  });
});

describe('alerts', () => {
  const data: AlertPreferences = {
    audience: 'staff',
    preferences: [
      { topic: 'staff.assigned', channel: 'email', enabled: true, locked: false },
      { topic: 'staff.assigned', channel: 'sms', enabled: true, locked: false },
      { topic: 'staff.escalations', channel: 'email', enabled: true, locked: true },
      { topic: 'staff.escalations', channel: 'sms', enabled: false, locked: false }
    ],
    quietHours: { enabled: false, start: '21:00', end: '07:00' }
  };

  it('sends only the switches that changed', () => {
    const draft = new Map([['staff.assigned/sms', false], ['staff.assigned/email', true], ['staff.escalations/email', false]]);
    expect(alertChanges(data.preferences, draft)).toEqual([{ topic: 'staff.assigned', channel: 'sms', enabled: false }]);
  });

  it('shows locked alerts as always on and saves changes with quiet hours', async () => {
    const user = userEvent.setup();
    const saveAlertPreferences = vi.fn(async () => data);
    renderWith(<AlertsScreen />, fakeApi({ getAlertPreferences: vi.fn(async () => data), saveAlertPreferences }));
    const row = await screen.findByTestId('alert-staff.escalations');
    expect(within(row).getByText('Always on')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Save changes' })).toBeDisabled();
    await user.click(screen.getByRole('switch', { name: 'Queues assigned to me by Text' }));
    await user.click(screen.getByRole('switch', { name: 'Use quiet hours' }));
    await user.click(screen.getByRole('button', { name: 'Save changes' }));
    await waitFor(() =>
      expect(saveAlertPreferences).toHaveBeenCalledWith({ preferences: [{ topic: 'staff.assigned', channel: 'sms', enabled: false }], quietHours: { enabled: true, start: '21:00', end: '07:00' } })
    );
  });
});

describe('profile', () => {
  const staff: AccountProfile = {
    id: 'stf-1', kind: 'staff', firstName: 'Ifeoma', lastName: 'Okafor', email: 'ifeoma@afrotransact.com', emailVerified: true, emailManaged: true, initials: 'IO',
    phone: '+17135550142', phoneVerified: true, jobTitle: 'Trust & Safety lead', memberships: [], roles: [{ key: 'platform.trust_safety', label: 'Trust & Safety' }]
  };

  it('shows a managed work email, platform roles and saves the job title', async () => {
    const user = userEvent.setup();
    const updateAccountProfile = vi.fn(async (b: Record<string, unknown>) => ({ ...staff, ...b }));
    renderWith(<ProfileScreen />, fakeApi({ getAccountProfile: vi.fn(async () => staff), updateAccountProfile }));
    expect(await screen.findByTestId('profile-email')).toHaveTextContent('ifeoma@afrotransact.com');
    expect(screen.getByText('Managed by AfroTransact IT. Ask IT to change it.')).toBeInTheDocument();
    expect(within(screen.getByTestId('staff-roles')).getByText('Trust & Safety')).toBeInTheDocument();
    expect(screen.queryByTestId('organisations')).not.toBeInTheDocument();
    const title = screen.getByLabelText('Job title');
    await user.clear(title);
    await user.type(title, 'Head of Trust');
    await user.click(screen.getByRole('button', { name: 'Save changes' }));
    await waitFor(() => expect(updateAccountProfile).toHaveBeenCalledWith({ firstName: 'Ifeoma', lastName: 'Okafor', jobTitle: 'Head of Trust' }));
  });

  it('lets seller members leave organisations they do not own', async () => {
    const user = userEvent.setup();
    const leaveOrganisation = vi.fn(async () => undefined);
    const seller: AccountProfile = {
      ...staff, kind: 'seller', emailManaged: false, email: 'tola@adeskitchen.com', roles: [],
      memberships: [
        { orgId: 'o1', name: 'Ade’s Kitchen', kind: 'SELLER', status: 'APPROVED', roleKey: 'seller.owner', roleLabel: 'Owner', owner: true, active: true },
        { orgId: 'o2', name: 'Ama’s Studio', kind: 'PROVIDER', status: 'APPROVED', roleKey: 'provider.practitioner', roleLabel: 'Practitioner', owner: false, active: false }
      ]
    };
    const onSwitchOrg = vi.fn();
    renderWith(<ProfileScreen onSwitchOrg={onSwitchOrg} />, fakeApi({ getAccountProfile: vi.fn(async () => seller), leaveOrganisation }));
    const [owner, member] = await screen.findAllByTestId('membership');
    expect(within(owner!).queryByRole('button', { name: 'Leave' })).not.toBeInTheDocument();
    await user.click(within(member!).getByRole('button', { name: 'Switch to this one' }));
    expect(onSwitchOrg).toHaveBeenCalledWith('o2');
    await user.click(within(member!).getByRole('button', { name: 'Leave' }));
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Leave' }));
    await waitFor(() => expect(leaveOrganisation).toHaveBeenCalledWith('o2'));
  });
});

describe('routes', () => {
  it('maps pathnames to account sections', () => {
    expect(accountSectionOf('/account')).toBe('profile');
    expect(accountSectionOf('/account/security')).toBe('security');
    expect(accountSectionOf('/account/nope')).toBeNull();
    expect(accountSectionOf('/orders')).toBeNull();
  });
});
