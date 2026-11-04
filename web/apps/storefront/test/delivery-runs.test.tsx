import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useSearchParams } from 'next/navigation';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { DeliveryAlertsView } from '@/components/runs/DeliveryAlerts';
import { LocationPageView, LocationsIndex } from '@/components/runs/LocationPage';
import { RunAdvertorials } from '@/components/runs/RunAdvertorials';
import { SubscriptionLink } from '@/components/runs/SubscriptionLink';
import { tEn } from '@/i18n/t';
import { shareEntries, subscriptionSource, utmTags } from '@/lib/runs/source';
import { firstView, firstVisit, resetVisit, visitorId } from '@/lib/runs/visitor';

import { installMockBff, sent, signInDemo } from './mockBff';
import { renderWithProviders } from './render';

installMockBff();

beforeEach(() => resetVisit());
afterEach(() => vi.mocked(useSearchParams).mockImplementation(() => new URLSearchParams() as ReturnType<typeof useSearchParams>));
const landOn = (query: string) => vi.mocked(useSearchParams).mockImplementation(() => new URLSearchParams(query) as ReturnType<typeof useSearchParams>);

const bodyOf = async (req: Request) => JSON.parse(await req.clone().text()) as Record<string, unknown>;
const requests = (method: string, pathPart: string) => sent.filter((r) => r.method === method && new URL(r.url).pathname.includes(pathPart));

describe('delivery run helpers', () => {
  it('adds the UTM source to the sign-up source, safely', () => {
    expect(subscriptionSource('location-page', new URLSearchParams('utm_source=wa&utm_medium=share'))).toBe('location-page.wa');
    expect(subscriptionSource('location-page', new URLSearchParams('utm_source=Face Book!'))).toBe('location-page.facebook');
    expect(subscriptionSource('location-page', new URLSearchParams())).toBe('location-page');
    expect(subscriptionSource('location-page', null)).toBe('location-page');
    expect(subscriptionSource('account', new URLSearchParams(`utm_source=${'x'.repeat(100)}`)).length).toBeLessThanOrEqual(80);
  });

  it('keeps only the three UTM tags, cleaned to short lower-case tokens', () => {
    expect(utmTags(new URLSearchParams('utm_source=WhatsApp&utm_medium=Social Share&utm_campaign=delivery-run-bastrop-tx-20261024&utm_content=x&fbclid=abc'))).toEqual({
      utmSource: 'whatsapp',
      utmMedium: 'socialshare',
      utmCampaign: 'delivery-run-bastrop-tx-20261024'
    });
    expect(utmTags(new URLSearchParams('utm_source=%3Cscript%3E'))).toEqual({ utmSource: 'script' });
    expect(utmTags(new URLSearchParams('ref=friend'))).toBeNull();
    expect(utmTags(null)).toBeNull();
    expect(utmTags(new URLSearchParams(`utm_source=${'x'.repeat(200)}`))?.utmSource).toHaveLength(80);
  });

  it('counts a visit once per session', () => {
    expect(firstVisit('a')).toBe(true);
    expect(firstVisit('a')).toBe(false);
    resetVisit();
    expect(firstVisit('a')).toBe(true);
  });

  it('keeps only secure share links', () => {
    expect(shareEntries({ whatsapp: 'https://wa.me/?text=x', evil: 'javascript:alert(1)', copy: 'http://insecure.example' })).toEqual([{ channel: 'whatsapp', url: 'https://wa.me/?text=x' }]);
  });

  it('keeps one visitor id and counts a view once per visit', () => {
    const id = visitorId();
    expect(id).toMatch(/^[0-9a-f]{24}$/);
    expect(visitorId()).toBe(id);
    expect(firstView('adv-1')).toBe(true);
    expect(firstView('adv-1')).toBe(false);
    resetVisit();
    expect(firstView('adv-1')).toBe(true);
  });
});

describe('location pages', () => {
  it('lists the delivery locations', async () => {
    renderWithProviders(<LocationsIndex />);
    const list = await screen.findByRole('list');
    await waitFor(() => expect(within(list).getAllByRole('link')).toHaveLength(3));
    expect(within(list).getByRole('link', { name: /Lubbock/ })).toHaveAttribute('href', '/delivery-runs/lubbock-tx');
  });

  it('shows upcoming runs, the community link, share links with UTM attribution and the guest sign-up', async () => {
    const user = userEvent.setup();
    renderWithProviders(<LocationPageView slug="lubbock-tx" />);
    expect(await screen.findByRole('heading', { name: 'Delivery to Lubbock' })).toBeInTheDocument();
    const run = await screen.findByTestId('location-run');
    expect(run).toHaveTextContent('Taking orders');
    expect(run).toHaveTextContent('$7.99');
    expect(run).toHaveTextContent('To your door');
    expect(run).toHaveTextContent('21');
    expect(screen.getByTestId('location-community')).toHaveAttribute('href', 'https://chat.example/join/lubbock-tx');
    const share = screen.getByTestId('location-share');
    const whatsapp = within(share).getByRole('link', { name: 'Share on WhatsApp' });
    expect(decodeURIComponent(whatsapp.getAttribute('href') ?? '')).toContain('utm_source=whatsapp&utm_medium=share&utm_campaign=location-page');

    const form = await screen.findByTestId('location-subscribe');
    expect(form).toHaveAttribute('data-state', 'ask');
    await user.click(within(form).getByRole('button', { name: 'Get alerts' }));
    expect(await within(form).findByText('Enter a valid email address')).toBeInTheDocument();
    await user.type(within(form).getByRole('textbox', { name: 'Email address' }), 'ada@example.com');
    await user.click(within(form).getByRole('button', { name: 'Get alerts' }));
    await waitFor(() => expect(screen.getByTestId('location-subscribe')).toHaveAttribute('data-state', 'sent'));
    expect(screen.getByTestId('location-subscribe')).toHaveTextContent('ada@example.com');
    const post = requests('POST', '/v1/delivery-runs/subscriptions')[0];
    expect(await bodyOf(post as Request)).toEqual({ locationId: '4b2f9d3a-0000-4000-8000-00000000000a', email: 'ada@example.com', source: 'location-page' });
  });

  it('counts a visit that arrived with UTM tags once, with the tags only, and sends them with the sign-up', async () => {
    const user = userEvent.setup();
    landOn('utm_source=whatsapp&utm_medium=social&utm_campaign=delivery-run-lubbock-tx-20261024&run=9d4a1c7e-0000-4000-8000-0000000000d1');
    const { unmount } = renderWithProviders(<LocationPageView slug="lubbock-tx" />);
    await screen.findByTestId('location-run');
    await waitFor(() => expect(requests('POST', '/visits')).toHaveLength(1));
    expect(new URL((requests('POST', '/visits')[0] as Request).url).pathname).toBe('/api/bff/v1/delivery-runs/locations/lubbock-tx/visits');
    expect(await bodyOf(requests('POST', '/visits')[0] as Request)).toEqual({
      utmSource: 'whatsapp',
      utmMedium: 'social',
      utmCampaign: 'delivery-run-lubbock-tx-20261024',
      runId: '9d4a1c7e-0000-4000-8000-0000000000d1'
    });
    // Coming back to the page in the same session does not count it again.
    unmount();
    renderWithProviders(<LocationPageView slug="lubbock-tx" />);
    await screen.findByTestId('location-run');
    expect(requests('POST', '/visits')).toHaveLength(1);
    const form = await screen.findByTestId('location-subscribe');
    await user.type(within(form).getByRole('textbox', { name: 'Email address' }), 'ada@example.com');
    await user.click(within(form).getByRole('button', { name: 'Get alerts' }));
    await waitFor(() => expect(screen.getByTestId('location-subscribe')).toHaveAttribute('data-state', 'sent'));
    expect(await bodyOf(requests('POST', '/v1/delivery-runs/subscriptions')[0] as Request)).toMatchObject({
      source: 'location-page.whatsapp',
      utmSource: 'whatsapp',
      utmMedium: 'social',
      utmCampaign: 'delivery-run-lubbock-tx-20261024'
    });
  });

  it('does not count a visit without UTM tags', async () => {
    renderWithProviders(<LocationPageView slug="lubbock-tx" />);
    await screen.findByTestId('location-run');
    expect(requests('POST', '/visits')).toHaveLength(0);
  });

  it('explains an unknown place', async () => {
    renderWithProviders(<LocationPageView slug="nowhere" />);
    expect(await screen.findByRole('heading', { name: tEn('runs.location.missingTitle') })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: tEn('runs.location.all') })).toHaveAttribute('href', '/delivery-runs');
  });

  it('lets a signed-in shopper choose channels right on the page', async () => {
    const user = userEvent.setup();
    await signInDemo();
    renderWithProviders(<LocationPageView slug="lubbock-tx" />);
    const box = await screen.findByTestId('location-subscribe');
    await waitFor(() => expect(within(box).getByRole('checkbox', { name: 'Email' })).toBeInTheDocument());
    expect(box).not.toHaveAttribute('data-state');
    await user.click(within(box).getByRole('checkbox', { name: 'Email' }));
    await waitFor(() => expect(within(box).getByRole('checkbox', { name: 'Email' })).toBeChecked());
    expect(await bodyOf(requests('POST', '/v1/account/run-subscriptions')[0] as Request)).toEqual({ locationId: '4b2f9d3a-0000-4000-8000-00000000000a', channels: ['EMAIL'], source: 'location-page' });
  });
});

describe('Delivery alerts in the account (consent per location and channel)', () => {
  it('turns channels on and off, keeping the consent record', async () => {
    const user = userEvent.setup();
    await signInDemo();
    renderWithProviders(<DeliveryAlertsView />);
    const lubbock = await screen.findByTestId('alerts-lubbock-tx');
    const sms = within(lubbock).getByRole('checkbox', { name: 'Text message' });
    const whatsapp = within(lubbock).getByRole('checkbox', { name: 'WhatsApp' });
    expect(sms).not.toBeChecked();
    await user.click(sms);
    await waitFor(() => expect(within(screen.getByTestId('alerts-lubbock-tx')).getByRole('checkbox', { name: 'Text message' })).toBeChecked());
    expect(within(screen.getByTestId('alerts-lubbock-tx')).getByText(/You turned this on/)).toBeInTheDocument();
    await user.click(whatsapp);
    await waitFor(() => expect(within(screen.getByTestId('alerts-lubbock-tx')).getByRole('checkbox', { name: 'WhatsApp' })).toBeChecked());
    expect(requests('POST', '/v1/account/run-subscriptions').map((r) => r.url).length).toBe(2);
    expect(await bodyOf(requests('POST', '/v1/account/run-subscriptions')[1] as Request)).toMatchObject({ channels: ['WHATSAPP'], source: 'account' });

    await user.click(within(screen.getByTestId('alerts-lubbock-tx')).getByRole('checkbox', { name: 'Text message' }));
    await waitFor(() => expect(within(screen.getByTestId('alerts-lubbock-tx')).getByRole('checkbox', { name: 'Text message' })).not.toBeChecked());
    expect(within(screen.getByTestId('alerts-lubbock-tx')).getByText(/You turned this off/)).toBeInTheDocument();
    expect(within(screen.getByTestId('alerts-lubbock-tx')).getByRole('checkbox', { name: 'WhatsApp' })).toBeChecked();
    const del = requests('DELETE', '/v1/account/run-subscriptions/')[0] as Request;
    expect(new URL(del.url).searchParams.getAll('channel')).toEqual(['SMS']);
    // Other places are untouched.
    expect(within(screen.getByTestId('alerts-amarillo-tx')).getByRole('checkbox', { name: 'Email' })).not.toBeChecked();
    expect(screen.getByText(tEn('runs.alerts.phone'))).toBeInTheDocument();
  });
});

describe('confirm and unsubscribe links', () => {
  it('asks first, then confirms the email subscription', async () => {
    renderWithProviders(<SubscriptionLink mode="confirm" token="confirm-token-1" />);
    expect(requests('POST', 'subscriptions/confirm')).toHaveLength(0);
    await userEvent.click(screen.getByRole('button', { name: tEn('runs.confirm.button') }));
    expect(await screen.findByRole('heading', { name: tEn('runs.confirm.doneTitle') })).toBeInTheDocument();
    expect(screen.getByTestId('subscription-confirm')).toHaveAttribute('data-state', 'done');
  });

  it('asks first, then withdraws every channel', async () => {
    renderWithProviders(<SubscriptionLink mode="unsubscribe" token="message-token-1" />);
    expect(requests('POST', 'subscriptions/unsubscribe')).toHaveLength(0);
    await userEvent.click(screen.getByRole('button', { name: tEn('runs.unsubscribe.button') }));
    expect(await screen.findByRole('heading', { name: tEn('runs.unsubscribe.doneTitle') })).toBeInTheDocument();
    expect(await bodyOf(requests('POST', 'subscriptions/unsubscribe')[0] as Request)).toEqual({ token: 'message-token-1' });
    expect(screen.getByRole('link', { name: tEn('runs.link.manage') })).toHaveAttribute('href', '/account/delivery-alerts');
  });

  it('explains a missing or unknown token', async () => {
    const { unmount } = renderWithProviders(<SubscriptionLink mode="confirm" token={null} />);
    expect(screen.getByRole('heading', { name: tEn('runs.confirm.invalidTitle') })).toBeInTheDocument();
    unmount();
    renderWithProviders(<SubscriptionLink mode="unsubscribe" token="invalid-token-1" />);
    await userEvent.click(screen.getByRole('button', { name: tEn('runs.unsubscribe.button') }));
    expect(await screen.findByRole('heading', { name: tEn('runs.unsubscribe.invalidTitle') })).toBeInTheDocument();
  });
});

describe('location advertorials', () => {
  it('shows the advertorial of the place with a live countdown, counts one view, and a click on the button', async () => {
    const user = userEvent.setup();
    renderWithProviders(<RunAdvertorials place={{ zip: '79401' }} />);
    const card = await screen.findByTestId('run-advertorial');
    expect(card).toHaveTextContent(/Lubbock pre-order is open|Last days: Lubbock jollof and spice box/);
    expect(card).toHaveTextContent('Lubbock, TX');
    expect(card).toHaveTextContent('Delivers Sunday');
    expect(within(card).getByRole('timer')).toHaveAttribute('aria-label', expect.stringContaining('Ordering closes in'));
    const cta = within(card).getByRole('link', { name: 'Order now' });
    expect(cta.getAttribute('href')).toMatch(/src=page&cr=adv-[0-9a-f]{32}&po=LBK-7/);
    await waitFor(() => expect(requests('POST', '/events')).toHaveLength(1));
    expect(await bodyOf(requests('POST', '/events')[0] as Request)).toEqual({ type: 'VIEW', visitor: expect.stringMatching(/^[0-9a-f]{24}$/) });
    const query = new URL((sent.find((r) => new URL(r.url).pathname.endsWith('/v1/delivery-runs/advertorials')) as Request).url).searchParams;
    expect(query.get('zip')).toBe('79401');
    expect(query.get('visitor')).toMatch(/^[0-9a-f]{24}$/);
    cta.addEventListener('click', (e) => e.preventDefault());
    await user.click(cta);
    await waitFor(() => expect(requests('POST', '/events')).toHaveLength(2));
    expect(await bodyOf(requests('POST', '/events')[1] as Request)).toEqual({ type: 'CLICK', visitor: expect.stringMatching(/^[0-9a-f]{24}$/) });
  });

  it('features up to four products on the card, each linking to its product page, and counts a click on one', async () => {
    const user = userEvent.setup();
    renderWithProviders(<RunAdvertorials place={{ zip: '79401' }} />);
    const products = await screen.findByTestId('run-advertorial-products');
    const links = within(products).getAllByRole('link');
    expect(links.map((l) => l.textContent)).toEqual(['Shea butter', 'Garri']);
    expect(links.map((l) => l.getAttribute('href'))).toEqual(['/p/ATSHEA0001', '/p/ATGARI0001']);
    await waitFor(() => expect(requests('POST', '/events')).toHaveLength(1));
    links[0]?.addEventListener('click', (e) => e.preventDefault());
    await user.click(links[0] as HTMLElement);
    await waitFor(() => expect(requests('POST', '/events')).toHaveLength(2));
    expect(await bodyOf(requests('POST', '/events')[1] as Request)).toEqual({ type: 'CLICK', visitor: expect.stringMatching(/^[0-9a-f]{24}$/) });
  });

  it('keeps the same variant for the same visit and renders nothing where there is none', async () => {
    const first = renderWithProviders(<RunAdvertorials place={{ zip: '79401' }} />);
    const headline = (await screen.findByRole('heading', { level: 2 })).textContent;
    first.unmount();
    renderWithProviders(<RunAdvertorials place={{ zip: '79401' }} />);
    expect((await screen.findByRole('heading', { level: 2 })).textContent).toBe(headline);
    expect(requests('POST', '/events')).toHaveLength(1);
    const { container, unmount } = renderWithProviders(<RunAdvertorials place={{ city: 'Houston' }} />);
    await waitFor(() => expect(sent.some((r) => new URL(r.url).searchParams.get('city') === 'Houston')).toBe(true));
    expect(container.querySelector('[data-testid="run-advertorials"]')).toBeNull();
    unmount();
  });
});
