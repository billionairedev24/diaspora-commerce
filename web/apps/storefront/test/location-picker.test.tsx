import { createApiClient } from '@afrotransact/api-client';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { Header } from '@/components/shell/Header';

import { installMockBff, sent, signInDemo } from './mockBff';
import { renderWithProviders } from './render';

installMockBff();

const geolocation = { getCurrentPosition: vi.fn() };
Object.defineProperty(navigator, 'geolocation', { value: geolocation, configurable: true });
afterEach(() => geolocation.getCurrentPosition.mockReset());

const locationButton = () => screen.findByRole('button', { name: /Deliver to .*Change location/ });
const openPicker = async (user: ReturnType<typeof userEvent.setup>) => {
  await user.click(await locationButton());
  return screen.findByRole('dialog', { name: 'Choose your location' });
};

describe('deliver-to in the header', () => {
  it('shows the city, never the ZIP, and opens nothing on load', async () => {
    renderWithProviders(<Header />);
    const button = await locationButton();
    expect(within(button).getByText('Houston')).toBeInTheDocument();
    expect(button).not.toHaveTextContent(/\d{5}/);
    expect(button).toHaveAccessibleName('Deliver to Houston. Change location');
    expect(screen.queryByRole('dialog', { name: 'Choose your location' })).not.toBeInTheDocument();
    expect(geolocation.getCurrentPosition).not.toHaveBeenCalled();
  });

  it('a detected location is flagged detected and a picked one chosen', async () => {
    const api = createApiClient({ baseUrl: `${window.location.origin}/api/bff` });
    expect((await api.getDeliveryLocation()).source).toBe('detected');
    await api.setDeliveryLocation({ zip: '10456' });
    const chosen = await api.getDeliveryLocation();
    expect(chosen).toMatchObject({ city: 'Bronx', label: 'Bronx', source: 'chosen' });
  });
});

describe('the location popover', () => {
  it('is a combobox: cities list with their state, arrow keys and Enter apply one at once', async () => {
    const user = userEvent.setup();
    renderWithProviders(<Header />);
    const dialog = await openPicker(user);
    const input = within(dialog).getByRole('combobox', { name: 'City, address or ZIP code' });
    expect(input).toHaveAttribute('aria-expanded', 'false');
    await user.type(input, 'Georg');
    const list = await within(dialog).findByRole('listbox');
    expect(input).toHaveAttribute('aria-expanded', 'true');
    expect(input).toHaveAttribute('aria-controls', list.id);
    const options = within(list).getAllByRole('option').map((o) => o.textContent);
    expect(options).toEqual(expect.arrayContaining(['Georgetown, DE', 'Georgetown, KY', 'Georgetown, TX']));
    expect(options.join(' ')).not.toMatch(/\d{5}/);
    // The list renders inside the popover (never clipped by it).
    expect(dialog).toContainElement(list);
    await user.keyboard('{ArrowDown}{ArrowDown}');
    expect(input.getAttribute('aria-activedescendant')).toBe(within(list).getAllByRole('option')[1]?.id);
    expect(within(list).getAllByRole('option')[1]).toHaveAttribute('aria-selected', 'true');
    await user.keyboard('{Enter}');
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Choose your location' })).not.toBeInTheDocument());
    const put = sent.find((r) => r.method === 'PUT' && r.url.includes('/v1/delivery-location'));
    expect(await put?.clone().json()).toMatchObject({ suggestionId: expect.stringMatching(/^pl:\d{5}:KY:Georgetown$/) });
    expect(await locationButton()).toHaveAccessibleName('Deliver to Georgetown. Change location');
  });

  it('a typed ZIP plus Enter applies it and the header shows its city', async () => {
    const user = userEvent.setup();
    renderWithProviders(<Header />);
    const dialog = await openPicker(user);
    await user.type(within(dialog).getByRole('combobox'), '10456{Enter}');
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Choose your location' })).not.toBeInTheDocument());
    const button = await screen.findByRole('button', { name: 'Deliver to Bronx. Change location' });
    expect(button).not.toHaveTextContent(/10456/);
  });

  it('Esc closes it and says plainly when nothing matches', async () => {
    const user = userEvent.setup();
    renderWithProviders(<Header />);
    const dialog = await openPicker(user);
    await user.type(within(dialog).getByRole('combobox'), 'Zzzzzz');
    expect(await within(dialog).findByText(/No matching places/)).toBeInTheDocument();
    await user.keyboard('{Escape}');
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Choose your location' })).not.toBeInTheDocument());
    expect(await locationButton()).toHaveFocus();
  });

  it('lists a signed-in shopper’s saved addresses first and applies one when selected', async () => {
    await signInDemo();
    const user = userEvent.setup();
    renderWithProviders(<Header />);
    const dialog = await openPicker(user);
    const saved = await within(dialog).findAllByTestId('saved-address');
    expect(saved.length).toBeGreaterThan(0);
    const combobox = within(dialog).getByRole('combobox');
    // Saved addresses come before the input.
    expect(saved[0]?.compareDocumentPosition(combobox)).toBe(Node.DOCUMENT_POSITION_FOLLOWING);
    await user.click(saved[0] as HTMLElement);
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Choose your location' })).not.toBeInTheDocument());
    const put = sent.find((r) => r.method === 'PUT' && r.url.includes('/v1/delivery-location'));
    expect(await put?.clone().json()).toMatchObject({ zip: expect.stringMatching(/^\d{5}$/) });
  });

  it('a guest sees no saved addresses', async () => {
    const user = userEvent.setup();
    renderWithProviders(<Header />);
    const dialog = await openPicker(user);
    expect(within(dialog).queryByTestId('saved-address')).not.toBeInTheDocument();
  });
});

describe('Use my current location', () => {
  it('asks the browser only on the click, and applies the coordinates', async () => {
    geolocation.getCurrentPosition.mockImplementation((ok: (p: { coords: { latitude: number; longitude: number } }) => void) => ok({ coords: { latitude: 29.7376, longitude: -95.5134 } }));
    const user = userEvent.setup();
    renderWithProviders(<Header />);
    const dialog = await openPicker(user);
    expect(geolocation.getCurrentPosition).not.toHaveBeenCalled();
    await user.click(within(dialog).getByRole('button', { name: 'Use my current location' }));
    expect(geolocation.getCurrentPosition).toHaveBeenCalledTimes(1);
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Choose your location' })).not.toBeInTheDocument());
    const put = sent.find((r) => r.method === 'PUT' && r.url.includes('/v1/delivery-location'));
    expect(await put?.clone().json()).toEqual({ lat: 29.7376, lon: -95.5134 });
  });

  it('a denial is a plain inline message and the popover stays usable', async () => {
    geolocation.getCurrentPosition.mockImplementation((_ok: unknown, fail: (e: { code: number }) => void) => fail({ code: 1 }));
    const user = userEvent.setup();
    renderWithProviders(<Header />);
    const dialog = await openPicker(user);
    await user.click(within(dialog).getByRole('button', { name: 'Use my current location' }));
    expect(await within(dialog).findByText(/Location permission was denied/)).toBeInTheDocument();
    expect(within(dialog).getByRole('combobox')).toBeEnabled();
    expect(screen.getByRole('dialog', { name: 'Choose your location' })).toBeInTheDocument();
  });
});
