import { resetStaffDb } from '@afrotransact/api-client/mocks/staff';
import { screen, within } from '@testing-library/react';
import axe from 'axe-core';
import { beforeEach, describe, expect, it } from 'vitest';

import { SettingsHome } from '@/components/settings/SettingsHome';
import { SettingsSectionScreen } from '@/components/settings/SettingsSectionScreen';
import { SettingsShell } from '@/components/settings/SettingsShell';
import { ConsoleShell } from '@/components/shell/ConsoleShell';
import { LEGACY, SETTINGS_SECTIONS, settingsHref, settingsSectionOf, visibleSections } from '@/lib/settings-sections';

import { asRoles, mockApi } from './mock-api';
import { renderScreen, renderWithProviders } from './render';
import { nav } from './setup';

const home = () =>
  renderWithProviders(
    <ConsoleShell>
      <SettingsShell>
        <SettingsHome />
      </SettingsShell>
    </ConsoleShell>
  );

const rowKeys = () => screen.getAllByTestId(/^settings-row-/).map((a) => a.getAttribute('data-testid')?.replace('settings-row-', ''));

describe('Settings home', () => {
  beforeEach(() => {
    resetStaffDb(new Date());
    asRoles(null);
    nav.set('/settings');
  });

  it('is one flat list of sections, each with a name and one line, and no second sidebar or cards', async () => {
    home();
    const list = await screen.findByTestId('settings-list');
    const row = within(list).getByTestId('settings-row-access');
    expect(row).toHaveAttribute('href', '/settings/access');
    expect(within(row).getByText('Roles and permissions')).toBeInTheDocument();
    expect(within(row).getByText(/What each role can do, who has it/)).toBeInTheDocument();
    expect(screen.queryByRole('navigation', { name: 'Settings sections' })).not.toBeInTheDocument();
    // flat rows (list items separated by a rule), not bordered cards
    expect(list.querySelectorAll('li.border-b').length).toBe(rowKeys().length);
    expect(list.querySelector('.rounded-md.border')).toBeNull();
  });

  it('lists the sections the owner asked for, and keeps every existing settings screen', async () => {
    home();
    await screen.findByTestId('settings-list');
    const keys = rowKeys();
    for (const k of ['staff', 'access', 'security', 'ai', 'audit', 'markets', 'notifications', 'tax-review', 'payments', 'fees', 'delivery-fees', 'run-economics', 'exchange-rates', 'application-review', 'published-pages', 'general', 'sign-in', 'language']) {
      expect(keys).toContain(k);
    }
    // sales tax review settings are linked, not duplicated: the row opens the existing tab
    expect(screen.getByTestId('settings-row-tax-review')).toHaveAttribute('href', '/tax?tab=tax-review-settings');
    expect(screen.getByTestId('settings-row-audit')).toHaveAttribute('href', '/audit');
  });

  it('shows a live status from saved state, and says what it cannot know', async () => {
    home();
    expect(await screen.findByTestId('settings-status-access')).toHaveTextContent('15 platform roles');
    expect(await screen.findByTestId('settings-status-ai')).toHaveTextContent('USD 2.00 a day');
    expect(await screen.findByTestId('settings-status-tax-review')).toBeInTheDocument();
    expect(await screen.findByTestId('settings-status-markets')).toBeInTheDocument();
    expect(screen.getByTestId('settings-status-language')).toBeInTheDocument();
  });

  it('is axe-clean', async () => {
    const { container } = await renderScreen(<SettingsHome />);
    await screen.findByTestId('settings-status-access');
    const r = await axe.run(container, { rules: { 'color-contrast': { enabled: false } } });
    expect(r.violations.map((v) => `${v.id}: ${v.nodes.map((n) => n.target.join(' ')).join(' | ')}`)).toEqual([]);
  });
});

describe('who sees what', () => {
  beforeEach(() => resetStaffDb(new Date()));
  const keysFor = async (role: string | null) => {
    asRoles(role);
    return visibleSections(await mockApi().getSession()).map((s) => s.key);
  };

  it('Super Admin sees every section there is', async () => {
    const keys = await keysFor('platform.super_admin');
    expect(keys.length).toBe(SETTINGS_SECTIONS.length);
  });

  it('Roles and permissions needs access:read: finance does not see it, the auditor and the product owner do', async () => {
    expect(await keysFor('platform.finance')).not.toContain('access');
    expect(await keysFor('platform.finance')).not.toContain('staff');
    expect(await keysFor('platform.auditor')).toContain('access');
    expect(await keysFor('platform.product_owner')).toContain('access');
    expect(await keysFor('platform.customer_support')).not.toContain('access');
  });

  it('the AI spend cap is for ai:manage', async () => {
    expect(await keysFor('platform.product_owner')).toContain('ai');
    expect(await keysFor('platform.finance')).not.toContain('ai');
  });

  it('the audit log row follows the Audit log module, security follows Identity & access', async () => {
    expect(await keysFor('platform.auditor')).toContain('audit');
    expect(await keysFor('platform.marketing')).not.toContain('audit');
    expect(await keysFor('platform.developer')).not.toContain('security');
    expect(await keysFor('platform.super_admin')).toContain('security');
  });

  it('the home hides the rows of a role that cannot open them, and the auditor’s view-only sections say so', async () => {
    asRoles('platform.auditor');
    nav.set('/settings');
    home();
    await screen.findByTestId('settings-list');
    expect(rowKeys()).toContain('access');
    expect(rowKeys()).toContain('payments');
    expect(rowKeys()).not.toContain('ai');
    expect(await screen.findByTestId('settings-status-staff')).toHaveTextContent('View only');
  });
});

describe('a section page', () => {
  beforeEach(() => resetStaffDb(new Date()));
  const page = (section: Parameters<typeof SettingsSectionScreen>[0]['section']) =>
    renderWithProviders(
      <ConsoleShell>
        <SettingsShell>
          <SettingsSectionScreen section={section} />
        </SettingsShell>
      </ConsoleShell>
    );

  it('has a Settings link back, one content column and its own title', async () => {
    asRoles(null);
    nav.set('/settings/ai');
    page('ai');
    expect(await screen.findByRole('heading', { name: 'AI and MCP spend cap', level: 1 })).toBeInTheDocument();
    expect(screen.getByTestId('settings-back')).toHaveAttribute('href', '/settings');
    expect(screen.getByTestId('settings-back')).toHaveTextContent('Settings');
    expect(screen.queryByRole('navigation', { name: 'Settings sections' })).not.toBeInTheDocument();
    expect(await screen.findByText(/USD 2.00 per UTC day/)).toBeInTheDocument();
  });

  it('says “You don’t have access” for a section the role cannot open, even from the address', async () => {
    asRoles('platform.finance');
    nav.set('/settings/access');
    page('access');
    expect(await screen.findByText('You don’t have access to this page')).toBeInTheDocument();
    expect(screen.queryByTestId('access-view')).not.toBeInTheDocument();
  });

  it('says a section is read-only when the role can view but not change it', async () => {
    asRoles('platform.auditor');
    nav.set('/settings/payments');
    page('payments');
    expect(await screen.findByText('Read-only')).toBeInTheDocument();
    expect(screen.getByText(/Auditor \(read-only\) can view payments and payouts but not change it\./)).toBeInTheDocument();
  });
});

describe('addresses', () => {
  it('the old ?section= keys open their new pages', () => {
    expect(settingsHref('identity')).toBe('/settings/security');
    expect(settingsHref('signin')).toBe('/settings/sign-in');
    expect(settingsHref('fees')).toBe('/settings/fees');
    expect(settingsHref('nonsense')).toBe('/settings');
    expect(Object.keys(LEGACY).sort()).toEqual(['identity', 'signin']);
  });

  it('knows which section a path is in, and the rows that link elsewhere are not sections of their own', () => {
    expect(settingsSectionOf('/settings/access')?.key).toBe('access');
    expect(settingsSectionOf('/settings')).toBeNull();
    expect(settingsSectionOf('/settings/pages/legal/terms')).toBeNull();
    expect(settingsSectionOf('/settings/audit')).toBeNull();
  });
});
