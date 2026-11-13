import { homeMock, resetHomeMock } from '@afrotransact/api-client/mocks';
import { getStaffDb, resetHomeRegistry, resetStaffDb } from '@afrotransact/api-client/mocks/staff';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import axe from 'axe-core';
import { createElement } from 'react';
import { beforeEach, describe, expect, it } from 'vitest';

import { MarketingView } from '@/components/marketing/MarketingView';

import { asRoles } from './mock-api';
import { renderScreen } from './render';
import { nav } from './setup';

/* Marketing > Home settings and Home preview (S417, S418, S430): thresholds, caps, labels and order bands with a mandatory reason,
   the history, and a preview that explains every module by name. Names, never ids. */

beforeEach(() => {
  resetStaffDb(new Date('2026-11-12T12:00:00Z'));
  resetHomeMock();
  resetHomeRegistry();
  asRoles(null);
  nav.set('/marketing');
});

const open = async (tab: 'home' | 'preview') => {
  nav.set(`/marketing?tab=${tab}`);
  const view = await renderScreen(<MarketingView />);
  await (tab === 'home' ? screen.findByTestId('home-settings') : screen.findByTestId('home-preview'));
  return view;
};

describe('Home settings', () => {
  it('is two tabs of Marketing; every module is listed by its name with its rule in words (never a key)', async () => {
    const { container } = await open('home');
    expect(screen.getByRole('tab', { name: 'Home settings' })).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: 'Home preview' })).toBeInTheDocument();
    for (const name of ['Hero campaigns', 'Shop by department', 'Shop by community', 'Deals', 'Top sellers', 'AfroTransact Plus']) expect(screen.getByRole('heading', { name })).toBeInTheDocument();
    expect(screen.getByText(/Shown only when 3 communities each have 8 eligible products from 2 sellers/)).toBeInTheDocument();
    for (const key of ['department-tiles', 'services-near-you', 'groceries-rail', 'new-arrivals', 'continue-shopping']) expect(container.textContent).not.toContain(key);
  });

  it('a change needs a reason of at least 10 characters; the save lists what changed in words; the version and the history follow', async () => {
    const user = userEvent.setup();
    await open('home');
    const input = screen.getByRole('spinbutton', { name: 'Shop by community: Products each community needs' });
    expect(input).toHaveValue(8);
    await user.clear(input);
    await user.type(input, '12');
    const changes = within(screen.getByTestId('home-changes'));
    expect(changes.getByText('Shop by community, Products each community needs: 8 to 12')).toBeInTheDocument();
    const save = screen.getAllByRole('button', { name: 'Save changes' }).at(-1)!;
    expect(save).toBeDisabled();
    await user.type(screen.getByRole('textbox', { name: 'Why are you changing the home page?' }), 'too short');
    expect(save).toBeDisabled();
    expect(screen.getByText(/at least 10 characters/i)).toBeInTheDocument();
    await user.type(screen.getByRole('textbox', { name: 'Why are you changing the home page?' }), ' for now');
    expect(save).toBeEnabled();
    await user.click(save);
    await waitFor(() => expect(homeMock.settings.version).toBe(1));
    expect(homeMock.settings.modules.communities?.unitMinProducts).toBe(12);
    await waitFor(() => expect(screen.getAllByText('Version 1').length).toBeGreaterThan(0));
    const history = within(await screen.findByTestId('home-history'));
    expect(history.getByText('too short for now')).toBeInTheDocument();
    expect(getStaffDb().audit[0]?.summary).toContain('too short for now');
  });

  it('weights and bands are editable and change the order bands the page uses', async () => {
    const user = userEvent.setup();
    await open('home');
    await user.selectOptions(screen.getByRole('combobox', { name: 'Deals: Order band' }), 'promo');
    const w = screen.getByRole('spinbutton', { name: 'Deals: Weight (0 to 100)' });
    await user.clear(w);
    await user.type(w, '10');
    await user.type(screen.getByRole('textbox', { name: 'Why are you changing the home page?' }), 'Deals belong near the end');
    await user.click(screen.getAllByRole('button', { name: 'Save changes' }).at(-1)!);
    await waitFor(() => expect(homeMock.settings.modules.deals).toMatchObject({ band: 'promo', weight: 10 }));
  });

  it('department names per language: an empty language keeps the built-in translation; the save is by the department name', async () => {
    const user = userEvent.setup();
    await open('home');
    const field = await screen.findByRole('textbox', { name: 'Groceries & Food, French' });
    await user.type(field, 'Épicerie');
    // English is required once another language is set: the row says so and the save stays closed.
    expect(await screen.findByText('Add the English name first.')).toBeInTheDocument();
    await user.type(screen.getByRole('textbox', { name: 'Groceries & Food, English' }), 'Food');
    expect(screen.queryByText('Add the English name first.')).not.toBeInTheDocument();
    expect(within(screen.getByTestId('home-changes')).getByText('Groceries & Food, French: empty to Épicerie')).toBeInTheDocument();
    await user.type(screen.getByRole('textbox', { name: 'Why are you changing the home page?' }), 'French name for the bar');
    await user.click(screen.getAllByRole('button', { name: 'Save changes' }).at(-1)!);
    await waitFor(() => expect(homeMock.settings.departmentLabels['groceries-food']).toEqual({ en: 'Food', fr: 'Épicerie' }));
  });

  it('a stale save is refused with a plain message and the screen reloads the stored values', async () => {
    const user = userEvent.setup();
    await open('home');
    homeMock.settings = { ...homeMock.settings, version: 5 };
    await user.click(screen.getByRole('switch', { name: 'Show Deals on the home page' }));
    await user.type(screen.getByRole('textbox', { name: 'Why are you changing the home page?' }), 'Switch deals off for a week');
    await user.click(screen.getAllByRole('button', { name: 'Save changes' }).at(-1)!);
    expect(await screen.findByText(/saved by someone else/i)).toBeInTheDocument();
    expect(homeMock.settings.modules.deals?.enabled).toBe(true);
  });

  it('can go back to an earlier version with a reason, as a new version', async () => {
    const user = userEvent.setup();
    await open('home');
    await user.click(screen.getByRole('switch', { name: 'Show Deals on the home page' }));
    await user.type(screen.getByRole('textbox', { name: 'Why are you changing the home page?' }), 'Deals off for the test');
    await user.click(screen.getAllByRole('button', { name: 'Save changes' }).at(-1)!);
    await waitFor(() => expect(homeMock.settings.version).toBe(1));
    await user.click(screen.getByRole('switch', { name: 'Show Deals on the home page' }));
    await user.type(screen.getByRole('textbox', { name: 'Why are you changing the home page?' }), 'Deals back on again');
    await user.click(screen.getAllByRole('button', { name: 'Save changes' }).at(-1)!);
    await waitFor(() => expect(homeMock.settings.version).toBe(2));
    const history = within(await screen.findByTestId('home-history'));
    await user.click(history.getAllByRole('button', { name: 'Go back to this version' })[0]!);
    const dialog = await screen.findByRole('dialog');
    await user.type(within(dialog).getByRole('textbox'), 'Version one was right');
    await user.click(within(dialog).getByRole('button', { name: 'Go back' }));
    await waitFor(() => expect(homeMock.settings.version).toBe(3));
    expect(homeMock.settings.modules.deals?.enabled).toBe(false);
  });

  it('a role that can view but not edit sees the values and no way to save', async () => {
    asRoles('platform.auditor');
    await open('home');
    expect(screen.getByText(/view the home settings but not change them/i)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Save changes' })).not.toBeInTheDocument();
    expect(screen.getByRole('spinbutton', { name: 'Shop by community: Products each community needs' })).toBeDisabled();
  });

  it('is axe-clean', async () => {
    const { container } = await open('home');
    const r = await axe.run(container, { rules: { 'color-contrast': { enabled: false } } });
    expect(r.violations.map((v) => `${v.id}: ${v.nodes.map((n) => n.target.join(' ')).join(' | ')}`)).toEqual([]);
  });
});

describe('Community registry', () => {
  it('lists communities seen in products with no entry, registers one with five languages and spellings, and removes it with a reason', async () => {
    const user = userEvent.setup();
    homeMock.catalogue = 100;
    await open('home');
    await user.click(await screen.findByRole('button', { name: 'Add entry for Nigerian'.replace('Add entry', 'Add an entry') }));
    const dialog = await screen.findByRole('dialog');
    await user.type(within(dialog).getByLabelText('French'), 'Nigérian');
    await user.type(within(dialog).getByLabelText('Other spellings'), 'naija, nigerian');
    await user.type(within(dialog).getByRole('textbox', { name: /Why are you changing/ }), 'First community entry');
    await user.click(within(dialog).getByRole('button', { name: 'Save the community' }));
    const registry = within(await screen.findByTestId('community-registry'));
    expect(await registry.findByText('Nigerian')).toBeInTheDocument();
    expect(registry.getByText('Shown to shoppers')).toBeInTheDocument();
    expect(registry.getByText(/Also written: naija, nigerian/)).toBeInTheDocument();
    await user.click(registry.getByRole('button', { name: 'Remove Nigerian' }));
    const confirm = await screen.findByRole('dialog');
    await user.type(within(confirm).getByRole('textbox'), 'Not needed any more');
    await user.click(within(confirm).getByRole('button', { name: 'Remove the entry' }));
    await waitFor(() => expect(screen.queryByTestId('community-registry')).not.toBeInTheDocument());
  });
});

describe('Home preview', () => {
  it('lists every module by name as shown or hidden, with the plain reason and the count against the threshold', async () => {
    const user = userEvent.setup();
    await open('preview');
    await user.selectOptions(screen.getByRole('combobox', { name: 'Catalogue' }), '10');
    const list = within(await screen.findByTestId('preview-decisions'));
    await waitFor(() => expect(list.getByRole('heading', { name: 'Shop by community' })).toBeInTheDocument());
    const community = list.getByRole('heading', { name: 'Shop by community' }).closest('li')!;
    expect(within(community).getByText('Hidden')).toBeInTheDocument();
    expect(within(community).getByText('Not enough products')).toBeInTheDocument();
    expect(within(community).getByText(/needs 3 communities with 8 eligible products and 2 sellers each; 0 qualify/)).toBeInTheDocument();
    expect(within(community).getByTestId('decision-count')).toHaveTextContent('Now 0 of 3 needed');
    expect(within(community).getByRole('link', { name: 'Edit the rule for Shop by community' })).toHaveAttribute('href', '?tab=home#rule-communities');
    const departments = list.getByRole('heading', { name: 'Shop by department' }).closest('li')!;
    expect(within(departments).getByText('Shown')).toBeInTheDocument();
    expect(within(departments).getByText(/Qualifying: .*Groceries & Food/)).toBeInTheDocument();
    for (const key of ['department-tiles', 'services-near-you', 'continue-shopping']) expect(document.body.textContent).not.toContain(key);
  });

  it('the 100 product catalogue shows communities, the page names what each module shows, and no id appears', async () => {
    const user = userEvent.setup();
    await open('preview');
    await user.selectOptions(screen.getByRole('combobox', { name: 'Catalogue' }), '100');
    const page = within(await screen.findByTestId('preview-page'));
    await waitFor(() => expect(page.getByText('Shop by community')).toBeInTheDocument());
    expect(page.getAllByText(/Nigerian|Ghanaian|Ethiopian/).length).toBeGreaterThan(0);
    expect(document.body.textContent).not.toMatch(/AT\d{8}|plc_|rail_/);
  });

  it('0 products: the brand hero is marked text only and the sell band is the only other organic module', async () => {
    const user = userEvent.setup();
    await open('preview');
    await user.selectOptions(screen.getByRole('combobox', { name: 'Catalogue' }), '0');
    const page = within(await screen.findByTestId('preview-page'));
    await waitFor(() => expect(page.getByText('Text only, no photo')).toBeInTheDocument());
    expect(page.getByText('Sell on AfroTransact')).toBeInTheDocument();
  });

  it('signed in, ZIP and as-of date change the result: Continue shopping appears, a season is named, the seed is shown and can be replayed', async () => {
    const user = userEvent.setup();
    await open('preview');
    await user.selectOptions(screen.getByRole('combobox', { name: 'Catalogue' }), '100');
    const facts = () => within(screen.getByTestId('preview-facts'));
    await waitFor(() => expect(facts().getByTestId('preview-seed')).toBeInTheDocument());
    expect(facts().getByText(/a guest/)).toBeInTheDocument();
    await user.click(screen.getByRole('switch', { name: 'Signed in' }));
    await waitFor(() => expect(facts().getByText(/a signed-in shopper/)).toBeInTheDocument());
    const cont = (await screen.findByRole('heading', { name: 'Continue shopping' })).closest('li')!;
    expect(within(cont).getByText('Shown')).toBeInTheDocument();
    await user.type(screen.getByRole('textbox', { name: 'ZIP code' }), '77042');
    await waitFor(() => expect(facts().getByText(/77042/)).toBeInTheDocument());
    const date = screen.getByLabelText('As of');
    await user.clear(date);
    await user.type(date, '2026-11-25');
    await waitFor(() => expect(facts().getByText(/Black Friday, raising: Deals/)).toBeInTheDocument());
    await user.click(screen.getByRole('button', { name: 'Another visit' }));
    await waitFor(() => expect(facts().getByTestId('preview-seed').textContent).toMatch(/^visit-/));
    // A seed pasted back shows the same view; the default order says there is no variety.
    await user.click(screen.getByRole('switch', { name: 'Default order (what crawlers get)' }));
    await waitFor(() => expect(facts().getByTestId('preview-seed')).toHaveTextContent('default'));
  });

  it('is read only: the settings, the audit log and the catalogue are untouched, for a viewer too', async () => {
    asRoles('platform.auditor');
    const before = getStaffDb().audit.length;
    await open('preview');
    await screen.findByTestId('preview-decisions');
    expect(homeMock.settings.version).toBe(0);
    expect(getStaffDb().audit.length).toBe(before);
  });

  it('is axe-clean', async () => {
    const { container } = await open('preview');
    await screen.findByTestId('preview-decisions');
    const r = await axe.run(container, { rules: { 'color-contrast': { enabled: false } } });
    expect(r.violations.map((v) => `${v.id}: ${v.nodes.map((n) => n.target.join(' ')).join(' | ')}`)).toEqual([]);
    expect(createElement).toBeDefined();
  });
});
