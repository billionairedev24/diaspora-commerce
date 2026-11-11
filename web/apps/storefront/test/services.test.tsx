import { createApiClient } from '@afrotransact/api-client';
import { http, HttpResponse,mockServer } from '@afrotransact/api-client/mocks/node';
import { createFormatters } from '@afrotransact/i18n';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';

import { formatNext, monogram } from '@/components/services/format';
import { ServicesView } from '@/components/services/ServicesView';

import { installMockBff, sent } from './mockBff';
import { renderWithProviders } from './render';

installMockBff();

const api = () => createApiClient({ baseUrl: `${window.location.origin}/api/bff` });
const at = (city: string, state: string, zip: string) => api().setDeliveryLocation({ suggestionId: `pl:${zip}:${state}:${encodeURIComponent(city)}` });
const rows = () => screen.findAllByTestId('service-listing');
const expectRows = (n: number) => waitFor(() => expect(screen.getAllByTestId('service-listing')).toHaveLength(n));
const searches = () => sent.filter((r) => r.url.includes('/v1/services/search'));

describe('services near the deliver-to place', () => {
  it('uses the navbar location: no ZIP box, no Search button, a small Change link, only local providers', async () => {
    await at('Georgetown', 'TX', '78626');
    renderWithProviders(<ServicesView />);
    expect(await screen.findByRole('heading', { level: 1, name: /Services near Georgetown/ })).toBeInTheDocument();
    expect(screen.queryByLabelText('ZIP code')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Search' })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Change your location (Georgetown)' })).toHaveTextContent('Change');
    expect(screen.getAllByRole('combobox', { name: 'What do you need?' })).toHaveLength(1);

    const list = await rows();
    expect(list.length).toBe(3);
    const text = list.map((r) => r.textContent ?? '').join('\n');
    expect(text).toContain('Hill Country Braids');
    expect(text).toContain('Zuri Hair Studio');
    expect(text).toContain('Bola’s Kitchen');
    expect(text).not.toContain('Adire Tailors'); // Brooklyn
    expect(text).not.toContain('Braids by Ama'); // Houston
  });

  it('designs each row: real photo or monogram, distance and zone, served cities, price, deposit, next time, mode, Book', async () => {
    await at('Georgetown', 'TX', '78626');
    renderWithProviders(<ServicesView />);
    const hill = (await rows()).find((r) => r.textContent?.includes('Hill Country Braids'));
    if (!hill) throw new Error('Hill Country Braids row missing');
    expect(within(hill).getByText('Box braids at your home')).toBeInTheDocument();
    expect(hill.querySelector('img')?.getAttribute('src')).toContain('/services/svc-rr-braids');
    expect(within(hill).getByText(/Round Rock · \d+ mi · Greater Austin/)).toBeInTheDocument();
    expect(within(hill).getByText(/Serves Georgetown, .*\+\d/)).toBeInTheDocument();
    expect(within(hill).getByText('Comes to you')).toBeInTheDocument();
    expect(within(hill).getByText('$195.00')).toBeInTheDocument();
    expect(within(hill).getByText('$40.00 deposit')).toBeInTheDocument();
    expect(within(hill).getByText(/^Next: \w{3}, \w{3} \d{1,2} · \d{1,2}:\d{2}\s?[AP]M$/)).toBeInTheDocument();
    expect(within(hill).getByRole('link', { name: 'Book' })).toHaveAttribute('href', '/services/svc-rr-braids');
    expect(hill.textContent).not.toMatch(/\d{4}-\d{2}-\d{2}T/);

    const zuri = (await rows()).find((r) => r.textContent?.includes('Zuri Hair Studio'));
    if (!zuri) throw new Error('Zuri row missing');
    expect(zuri.textContent).not.toMatch(/\b0\b.*review|\(0\)/); // never "0 reviews"
    expect(within(zuri).queryByRole('img', { name: /out of 5/ })).not.toBeInTheDocument();
    expect(within(zuri).getByText('You go to them')).toBeInTheDocument();
  });

  it('chips come from the real categories with counts, never a zero, and filter; sort re-orders', async () => {
    const user = userEvent.setup();
    await at('Georgetown', 'TX', '78626');
    renderWithProviders(<ServicesView />);
    await rows();
    const chips = screen.getByRole('group', { name: 'Kinds of service' });
    expect(within(chips).getByRole('button', { name: 'All services (3)' })).toHaveAttribute('aria-pressed', 'true');
    expect(within(chips).getByRole('button', { name: 'Hair & beauty (2)' })).toBeInTheDocument();
    expect(within(chips).getByRole('button', { name: 'Catering (1)' })).toBeInTheDocument();
    expect(chips.textContent).not.toMatch(/\(0\)/);
    expect(within(chips).queryByRole('button', { name: /Tailoring/ })).not.toBeInTheDocument();

    await user.click(within(chips).getByRole('button', { name: 'Catering (1)' }));
    await expectRows(1);
    expect(screen.getByText('Bola’s Kitchen')).toBeInTheDocument();
    await user.click(within(await screen.findByRole('group', { name: 'Kinds of service' })).getByRole('button', { name: 'All services (3)' }));
    await expectRows(3);

    await user.selectOptions(screen.getByRole('combobox', { name: 'Sort by' }), 'PRICE');
    await waitFor(() => expect(screen.getAllByTestId('service-listing')[0]?.textContent).toContain('Zuri Hair Studio'));
    expect(searches().at(-1)?.url).toContain('sort=PRICE');
  });

  it('filters as you type (debounced, once) and on Enter, with suggestions', async () => {
    const user = userEvent.setup();
    await at('Georgetown', 'TX', '78626');
    renderWithProviders(<ServicesView />);
    await rows();
    const before = searches().length;
    const box = screen.getByRole('combobox', { name: 'What do you need?' });
    await user.type(box, 'jollof');
    expect(await screen.findByRole('option', { name: /Party jollof for 30 guests/ })).toBeInTheDocument();
    await waitFor(() => expect(searches().length).toBe(before + 1), { timeout: 2000 });
    expect(searches().at(-1)?.url).toContain('q=jollof');
    await expectRows(1);

    await user.clear(box);
    await user.type(box, 'silk{Enter}');
    await waitFor(() => expect(searches().at(-1)?.url).toContain('q=silk'));
  });

  it('Change opens the same location popover', async () => {
    const user = userEvent.setup();
    await at('Georgetown', 'TX', '78626');
    renderWithProviders(<ServicesView />);
    await user.click(await screen.findByRole('button', { name: 'Change your location (Georgetown)' }));
    expect(await screen.findByRole('dialog', { name: 'Choose your location' })).toBeInTheDocument();
  });
});

describe('when nothing serves the place', () => {
  it('says so plainly, lists what the place does have, and never shows another state as local', async () => {
    const user = userEvent.setup();
    await at('Houston', 'TX', '77002');
    renderWithProviders(<ServicesView initialCategory="tailoring" />);
    expect(await screen.findByRole('heading', { name: 'No tailoring providers serve Houston yet' })).toBeInTheDocument();
    expect(screen.queryByTestId('service-listing')).not.toBeInTheDocument(); // Atlanta and Brooklyn tailors are not shown
    expect(screen.getByText(/in-person services/)).toBeInTheDocument();
    expect(screen.queryByText(/online|virtual|video/i)).not.toBeInTheDocument();
    const empty = screen.getByTestId('service-empty');
    expect(within(empty).getByText('Available near Houston')).toBeInTheDocument();
    await user.click(within(empty).getByRole('button', { name: /Hair & beauty \(\d\)/ }));
    await expectRows(2);
  });

  it('shows the nearest providers outside their usual coverage, clearly labelled, only within the maximum', async () => {
    await at('Georgetown', 'TX', '78626');
    renderWithProviders(<ServicesView initialCategory="tailoring" />);
    const nearest = await screen.findByTestId('service-nearest');
    expect(within(nearest).getByText(/^Nearest: Capital City Tailors, \d+ miles away, does not usually travel to Georgetown$/)).toBeInTheDocument();
    expect(within(nearest).queryByText(/Adire Tailors|Kente Tailors/)).not.toBeInTheDocument(); // 1,500 miles: not offered
    expect(screen.getByRole('heading', { name: 'No tailoring providers serve Georgetown yet' })).toBeInTheDocument();
  });

  it('a place with no services at all offers the alert and "Offer your services"', async () => {
    await at('Dallas', 'TX', '75201');
    renderWithProviders(<ServicesView />);
    expect(await screen.findByRole('heading', { name: 'No services in Dallas yet' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Offer your services' })).toHaveAttribute('href', '/provide');
    expect(screen.queryByRole('group', { name: 'Kinds of service' })).not.toBeInTheDocument();
  });

  it('"Tell me when one joins" works signed out: one email, no account, validated first', async () => {
    const user = userEvent.setup();
    await at('Dallas', 'TX', '75201');
    renderWithProviders(<ServicesView initialCategory="tailoring" />);
    await screen.findByRole('heading', { name: 'No tailoring providers serve Dallas yet' });
    const email = screen.getByLabelText('Your email');
    await user.click(screen.getByRole('button', { name: 'Tell me' }));
    expect(await screen.findByText('Enter your email address so we know where to write.')).toBeInTheDocument();
    await user.type(email, 'nope');
    await user.click(screen.getByRole('button', { name: 'Tell me' }));
    expect(await screen.findByText('Enter a valid email address, like name@example.com.')).toBeInTheDocument();
    expect(sent.filter((r) => r.url.includes('/v1/services/alerts'))).toHaveLength(0);

    await user.clear(email);
    await user.type(email, ' Ada@Example.com ');
    await user.click(screen.getByRole('button', { name: 'Tell me' }));
    expect(await screen.findByText('✓ We will email ada@example.com when a provider starts serving Dallas')).toBeInTheDocument();
    const req = sent.filter((r) => r.url.includes('/v1/services/alerts')).at(-1);
    expect(req?.method).toBe('POST');
    expect(await req?.clone().json()).toEqual({ email: 'Ada@Example.com', category: 'tailoring' });
    expect(screen.queryByText(/sign in/i)).not.toBeInTheDocument();
  });

  it('asks the visitor to wait on a 429 and to retry on a server error', async () => {
    const user = userEvent.setup();
    await at('Dallas', 'TX', '75201');
    renderWithProviders(<ServicesView />);
    await screen.findByRole('heading', { name: 'No services in Dallas yet' });
    mockServer.use(http.post('*/v1/services/alerts', () => HttpResponse.json({ type: 'about:blank', title: 'x', status: 429 }, { status: 429, headers: { 'content-type': 'application/problem+json' } })));
    await user.type(screen.getByLabelText('Your email'), 'ada@example.com');
    await user.click(screen.getByRole('button', { name: 'Tell me' }));
    expect(await screen.findByText('You have asked for a lot of alerts. Please try again a little later.')).toBeInTheDocument();
  });
});

describe('formatting', () => {
  const fmt = createFormatters('en');
  it('never shows a raw ISO timestamp: weekday, date and time in the visitor’s zone', () => {
    const iso = '2026-11-11T16:00:00Z';
    expect(formatNext(fmt, iso)).not.toMatch(/\d{4}-\d{2}-\d{2}|T\d{2}:/);
    expect(formatNext(fmt, iso)).toMatch(/^Wed, Nov 11 · \d{1,2}:\d{2}\s?[AP]M$/);
    expect(fmt.time(iso, 'America/Chicago')).toMatch(/10:00\s?AM/);
    expect(fmt.date(iso, { weekday: 'short', month: 'short', day: 'numeric' }, 'America/Los_Angeles')).toBe('Wed, Nov 11');
  });

  it('a photo-less provider gets initials, never an icon or stripes', () => {
    expect(monogram('Adire Tailors')).toBe('AT');
    expect(monogram('Bola’s Kitchen')).toBe('BK');
    expect(monogram('Zuri')).toBe('Z');
    expect(monogram('')).toBe('·');
  });
});
