import { type Me, type SiteNavigation } from '@afrotransact/api-client';
import { I18nProvider } from '@afrotransact/i18n/react';
import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import axe from 'axe-core';
import { type ReactElement } from 'react';
import { beforeAll, describe, expect, it, vi } from 'vitest';

import { AllMenu, type AllMenuProps } from '@/components/shell/AllMenu';
import { messagesFor } from '@/i18n/catalogs';
import { browserApi } from '@/lib/api/browser';

import { installMockBff } from './mockBff';
import { renderWithProviders } from './render';

installMockBff();

const guest: Me = { authenticated: false, user: null, counts: null };
const ada: Me = {
  authenticated: true,
  user: { id: 'u1', firstName: 'Adaeze', lastName: 'Nwosu', email: 'adaeze@example.com', initials: 'AN', roles: ['customer'], backOffices: [] },
  counts: { orders: 2, addresses: 2, paymentMethods: 3 }
};

let nav: SiteNavigation;
beforeAll(async () => {
  // The mock BFF's live (eligible-only) navigation: the same call the header makes.
  nav = await browserApi().getNavigation();
});

const props = (over: Partial<AllMenuProps> = {}): AllMenuProps => ({
  navigation: nav,
  locale: 'en',
  me: guest,
  preorder: null,
  deliverLabel: 'Austin 78701',
  onAskLola: vi.fn(),
  onDeliverTo: vi.fn(),
  ...over
});
const mount = (over: Partial<AllMenuProps> = {}, wrap: (ui: ReactElement) => ReactElement = (ui) => ui) =>
  renderWithProviders(
    wrap(
      <ul>
        <AllMenu {...props(over)} />
      </ul>
    )
  );
const openMenu = () => fireEvent.click(screen.getByRole('button', { name: 'All' }));
const labels = () => screen.getAllByRole('menuitem').map((i) => i.textContent);

describe('All menu drawer', () => {
  it('is closed until the All button is used, then a modal dialog with the greeting bar, a close button and a locked page', async () => {
    mount();
    const all = screen.getByRole('button', { name: 'All' });
    expect(all).toHaveAttribute('aria-expanded', 'false');
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    openMenu();
    const dialog = await screen.findByRole('dialog', { name: 'All menu' });
    expect(all).toHaveAttribute('aria-expanded', 'true');
    expect(within(dialog).getByRole('link', { name: 'Hello, sign in' })).toHaveAttribute('href', expect.stringContaining('/api/bff/v1/auth/login'));
    expect(within(dialog).getByRole('button', { name: 'Close menu' })).toBeInTheDocument();
    expect(document.body).toHaveAttribute('data-scroll-locked');
    // Focus goes to the first row, not the close button.
    expect(document.activeElement).toBe(screen.getAllByRole('menuitem')[0]);
  });

  it('Escape closes it, restores focus to All and unlocks the page', async () => {
    mount();
    openMenu();
    const dialog = await screen.findByRole('dialog');
    fireEvent.keyDown(dialog, { key: 'Escape' });
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    await waitFor(() => expect(document.activeElement).toBe(screen.getByRole('button', { name: 'All' })));
    expect(document.body).not.toHaveAttribute('data-scroll-locked');
  });

  it('lists the gateway departments (eligible only, with chevrons), then programs, then help and settings, in three sections', async () => {
    mount();
    openMenu();
    const menu = await screen.findByRole('menu', { name: 'All menu' });
    const groups = within(menu).getAllByRole('group');
    expect(groups.map((g) => g.getAttribute('aria-labelledby') && document.getElementById(g.getAttribute('aria-labelledby') as string)?.textContent)).toEqual([
      'Shop by department',
      'Programs & features',
      'Help & settings'
    ]);
    const departments = within(groups[0]!)
      .getAllByRole('menuitem')
      .filter((i) => i.hasAttribute('data-drill'));
    // Six at first, the rest behind "See all"; every one is a department the navigation lists, in its order.
    expect(departments.length).toBe(Math.min(6, nav.all.length));
    expect(departments.map((d) => d.textContent)).toEqual(nav.all.slice(0, 6).map((d) => d.labels.en || d.label));
    if (nav.all.length > 6) {
      fireEvent.click(within(menu).getByRole('menuitem', { name: 'See all' }));
      expect(
        within(menu)
          .getAllByRole('menuitem')
          .filter((i) => i.hasAttribute('data-drill'))
      ).toHaveLength(nav.all.length);
      expect(within(menu).getByRole('menuitem', { name: 'See less' })).toHaveAttribute('aria-expanded', 'true');
    }
    expect(
      within(groups[1]!)
        .getAllByRole('menuitem')
        .map((i) => i.textContent)
    ).toEqual(expect.arrayContaining(['Lola AI', 'Gift cards', 'Sell on AfroTransact', 'Offer your services']));
    expect(
      within(groups[2]!)
        .getAllByRole('menuitem')
        .map((i) => i.textContent)
    ).toEqual(['Your account', 'Deliver to Austin 78701', 'Customer service', 'Sign in']);
  });

  it('shows only the programs that exist now', async () => {
    const none: SiteNavigation = { ...nav, deals: { available: false }, brand: { available: false }, services: { available: false, href: '/services' } };
    const { unmount } = mount({ navigation: none });
    openMenu();
    await screen.findByRole('menu');
    expect(labels()).not.toEqual(expect.arrayContaining(['Pre-order']));
    for (const gone of ["Today's Deals", 'AfroTransact Brand', 'Services', 'Pre-order']) expect(screen.queryByRole('menuitem', { name: gone })).not.toBeInTheDocument();
    unmount();
    const all: SiteNavigation = { ...nav, deals: { available: true }, brand: { available: true }, services: { available: true, href: '/services' } };
    mount({ navigation: all, preorder: { code: 'PO1' } });
    openMenu();
    await screen.findByRole('menu');
    const programs = labels();
    expect(programs.slice(programs.indexOf('Pre-order'), programs.indexOf('Offer your services') + 1)).toEqual([
      'Pre-order',
      'Lola AI',
      "Today's Deals",
      'AfroTransact Brand',
      'Services',
      'Gift cards',
      'Sell on AfroTransact',
      'Offer your services'
    ]);
    expect(screen.getByRole('menuitem', { name: 'Services' })).toHaveAttribute('href', '/services');
  });

  it('drills into a department in the same panel: Main menu row, heading, See all in it, and its communities; Main menu goes back', async () => {
    mount();
    openMenu();
    await screen.findByRole('menu');
    const first = nav.all[0]!;
    const row = screen.getAllByRole('menuitem')[0]!;
    fireEvent.click(row);
    const menu = await screen.findByRole('menu', { name: first.labels.en || first.label });
    expect(within(menu).getAllByRole('menuitem')[0]).toHaveTextContent('Main menu');
    expect(within(menu).getByText(first.labels.en || first.label)).toBeInTheDocument();
    expect(within(menu).getByRole('menuitem', { name: `See all in ${first.labels.en || first.label}` })).toHaveAttribute('href', first.href);
    expect(document.activeElement).toBe(within(menu).getAllByRole('menuitem')[0]);
    if (nav.communities.length > 0) {
      await within(menu).findByText('Shop by community');
      const links = within(menu)
        .getAllByRole('menuitem')
        .filter((i) => (i.getAttribute('href') ?? '').includes('community='));
      expect(links.length).toBeGreaterThan(0);
      for (const l of links) expect(l.getAttribute('href')).toContain(`dept=${encodeURIComponent(first.name)}`);
    }
    fireEvent.click(within(menu).getByRole('menuitem', { name: 'Main menu' }));
    await screen.findByRole('menu', { name: 'All menu' });
    expect(document.activeElement).toBe(screen.getAllByRole('menuitem')[0]);
  });

  it('keyboard: arrows, Home and End move between rows; the forward arrow opens a department and the back arrow returns', async () => {
    mount();
    openMenu();
    const menu = await screen.findByRole('menu');
    const rows = () => screen.getAllByRole('menuitem');
    expect(document.activeElement).toBe(rows()[0]);
    fireEvent.keyDown(menu, { key: 'ArrowDown' });
    expect(document.activeElement).toBe(rows()[1]);
    fireEvent.keyDown(menu, { key: 'ArrowUp' });
    fireEvent.keyDown(menu, { key: 'ArrowUp' });
    expect(document.activeElement).toBe(rows().at(-1));
    fireEvent.keyDown(menu, { key: 'Home' });
    expect(document.activeElement).toBe(rows()[0]);
    fireEvent.keyDown(menu, { key: 'End' });
    expect(document.activeElement).toBe(rows().at(-1));
    // Roving tabindex: exactly one row is in the tab order.
    expect(rows().filter((r) => r.tabIndex === 0)).toHaveLength(1);
    fireEvent.keyDown(menu, { key: 'Home' });
    fireEvent.keyDown(menu, { key: 'ArrowRight' });
    const sub = await screen.findByRole('menu', { name: nav.all[0]!.labels.en || nav.all[0]!.label });
    fireEvent.keyDown(sub, { key: 'ArrowLeft' });
    await screen.findByRole('menu', { name: 'All menu' });
    expect(document.activeElement).toBe(screen.getAllByRole('menuitem')[0]);
  });

  it('signed in: "Hello, first name" linking to the account, and Sign out instead of Sign in', async () => {
    mount({ me: ada });
    openMenu();
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByRole('link', { name: 'Hello, Adaeze' })).toHaveAttribute('href', '/account');
    expect(screen.getByRole('menuitem', { name: 'Sign out' })).toBeInTheDocument();
    expect(screen.queryByRole('menuitem', { name: 'Sign in' })).not.toBeInTheDocument();
  });

  it('Lola and Deliver to close the drawer and then run their action', async () => {
    const onAskLola = vi.fn();
    const onDeliverTo = vi.fn();
    mount({ onAskLola, onDeliverTo });
    openMenu();
    await screen.findByRole('menu');
    fireEvent.click(screen.getByRole('menuitem', { name: 'Deliver to Austin 78701' }));
    await waitFor(() => expect(onDeliverTo).toHaveBeenCalled());
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    openMenu();
    await screen.findByRole('menu');
    fireEvent.click(screen.getByRole('menuitem', { name: 'Lola AI' }));
    await waitFor(() => expect(onAskLola).toHaveBeenCalled());
  });

  it('Arabic: right-to-left labels, and the arrows swap (back is the right arrow)', async () => {
    const rtl = (ui: ReactElement) => (
      <I18nProvider locale="ar" messages={messagesFor('ar')}>
        {ui}
      </I18nProvider>
    );
    mount({ locale: 'ar' }, rtl);
    fireEvent.click(screen.getByRole('button', { name: 'الكل' }));
    const menu = await screen.findByRole('menu');
    expect(screen.getByText('تسوّق حسب القسم')).toBeInTheDocument();
    fireEvent.keyDown(menu, { key: 'ArrowLeft' });
    const sub = await screen.findByRole('menu', { name: (n) => n !== 'قائمة الكل' });
    fireEvent.keyDown(sub, { key: 'ArrowRight' });
    await screen.findByRole('menu', { name: 'قائمة الكل' });
  });

  it('is axe-clean: main menu, a department, signed in and out (colour contrast is checked in Playwright)', async () => {
    const { unmount } = mount();
    openMenu();
    await screen.findByRole('menu');
    const run = () => axe.run(document.body, { rules: { 'color-contrast': { enabled: false } } });
    expect((await run()).violations).toEqual([]);
    fireEvent.click(screen.getAllByRole('menuitem')[0]!);
    await screen.findByText('Shop by community').catch(() => undefined);
    expect((await run()).violations).toEqual([]);
    unmount();
    mount({ me: ada });
    openMenu();
    await screen.findByRole('menu');
    expect((await run()).violations).toEqual([]);
  });
});
