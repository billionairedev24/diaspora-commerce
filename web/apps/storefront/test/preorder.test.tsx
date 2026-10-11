import { createApiClient, type ProductSummary, SearchQuerySchema } from '@afrotransact/api-client';
import { createFormatters } from '@afrotransact/i18n';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { CartView } from '@/components/cart/CartView';
import { CartAwareProductCard } from '@/components/common/CartAwareProductCard';
import { PreorderBanners } from '@/components/preorder/PreorderBanners';
import { deliveryIcs } from '@/components/preorder/PreorderConfirmation';
import { PreorderHome } from '@/components/preorder/PreorderHome';
import { PreorderView } from '@/components/preorder/PreorderPage';
import { WaitlistUnsubscribe } from '@/components/preorder/WaitlistUnsubscribe';
import { tEn } from '@/i18n/t';
import { ATTRIBUTION_COOKIE, captureAttribution, CONSENT_COOKIE, hasAttributionConsent, readAttribution, setAttribution } from '@/lib/preorder/attribution';
import { deliveryTerms, familyCities, paymentMethodsText, payNotes, preorderDates, stageCopy } from '@/lib/preorder/copy';

import { installMockBff } from './mockBff';
import { renderWithProviders } from './render';

installMockBff();

const fmt = createFormatters('en');
/** ICU puts a narrow no-break space before AM/PM; the design writes a plain space. */
const plain = (s: string) => s.replace(/\u202f/g, ' ');

const LBK = { opensAt: '2026-10-06T14:00:00Z', closesAt: '2026-10-23T04:59:00Z', deliversOn: '2026-10-25', timeZone: 'America/Chicago' };
const dates = preorderDates(LBK, 'en-US');
const copyFor = (stage: 'OPEN' | 'UPCOMING' | 'CLOSED') =>
  stageCopy(tEn, { stage, city: 'Lubbock', ribbon: 'Lubbock pre-order is open', headline: 'Fresh egusi, garri and palm oil.', phone: '+1 (833) 555-0199', dates, doorFee: '$7.99' });

const api = () => createApiClient({ baseUrl: `${window.location.origin}/api/bff` });
async function preview(body: Record<string, unknown>) {
  const res = await fetch(`${window.location.origin}/api/bff/v1/mock/preorder-preview`, { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
  expect(res.ok).toBe(true);
}
const product = async (atin: string): Promise<ProductSummary> => {
  const page = await api().search(SearchQuerySchema.parse({ size: 60 }));
  const hit = page.hits.find((h) => h.atin === atin);
  if (!hit) throw new Error(`no ${atin}`);
  return hit;
};
const JOLLOF = 'AT000000P2';
const SHEA = 'AT00000P15';

describe('pre-order copy (design v2 strings)', () => {
  it('formats the window dates in the market time zone', () => {
    expect(plain(dates.opensT)).toBe('Tue, Oct 6, 9:00 AM');
    expect(plain(dates.closesT)).toBe('Thu, Oct 22, 11:59 PM');
    expect(dates.deliversT).toBe('Sun, Oct 25');
    expect([dates.opensS, dates.closesS, dates.deliversS]).toEqual(['Oct 6', 'Oct 22', 'Oct 25']);
  });

  it('open stage: ribbon, kicker, countdown label, CTA, PDP note and card tag', () => {
    const c = copyFor('OPEN');
    expect(c.ribbon).toBe('Lubbock pre-order is open');
    expect(plain(c.ribbonSub)).toBe('Order by Thu, Oct 22, 11:59 PM · delivered Sun, Oct 25 · $7.99 flat');
    expect(c.kicker).toBe('Lubbock pre-order · open');
    expect(c.stageLabel).toBe('Open now');
    expect(c.cdLabel).toBe('Closes in');
    expect(c.cta).toBe('Pre-order now');
    expect(plain(c.pdpNote)).toBe('Order by Thu, Oct 22, 11:59 PM. Charged at checkout. Cancel for free until the pre-order closes.');
    expect(c.tag).toBe('Pre-order · delivered Oct 25');
    expect([c.addLabel, c.pdpAddLabel, c.lockedText]).toEqual([null, null, null]);
  });

  it('upcoming stage locks adding with the opening date', () => {
    const c = copyFor('UPCOMING');
    expect(c.ribbon).toBe('Lubbock pre-order opens Oct 6');
    expect(c.ribbonSub).toBe('Order Oct 6 – Oct 22 · delivered Sun, Oct 25');
    expect([c.kicker, c.stageLabel, c.cdLabel, c.cta]).toEqual(['Lubbock pre-order · opens soon', 'Opens soon', 'Opens in', "See what's coming"]);
    expect(c.tag).toBe('Pre-order opens Oct 6');
    expect(c.addLabel).toBe('Opens Oct 6');
    expect(plain(c.pdpAddLabel ?? '')).toBe('Opens Tue, Oct 6, 9:00 AM');
    expect(plain(c.lockedText ?? '')).toBe('Pre-order items can be bought from Tue, Oct 6, 9:00 AM. They stay in your cart until then.');
  });

  it('closed stage: delivering, missed-it phone, late-add lock', () => {
    const c = copyFor('CLOSED');
    expect(c.ribbon).toBe('Lubbock pre-order is closed');
    expect(c.ribbonSub).toBe('Delivering Sun, Oct 25. Missed it? Call +1 (833) 555-0199');
    expect(c.headline).toBe('Pre-order closed. Lubbock deliveries go out Sun, Oct 25.');
    expect([c.cdLabel, c.cta, c.tag, c.addLabel, c.pdpAddLabel]).toEqual(['Delivers in', 'Missed it? Contact us', 'Pre-order closed', 'Closed', 'Pre-order closed']);
    expect(plain(c.lockedText ?? '')).toBe('The pre-order closed Thu, Oct 22, 11:59 PM. Remove those items to check out, or call +1 (833) 555-0199 about a late add.');
  });

  it('fees and payment methods come from the window setup, never hard-coded', () => {
    const terms = { door: true, pickup: true, doorFeeMinor: 799, pickupFeeMinor: 0, currency: 'USD' };
    expect(deliveryTerms(tEn, fmt, terms)).toBe('$7.99 flat or free pickup');
    expect(deliveryTerms(tEn, fmt, { ...terms, pickup: false, doorFeeMinor: 999, currency: 'CAD' })).toBe('CA$9.99 flat');
    expect(deliveryTerms(tEn, fmt, { ...terms, pickupFeeMinor: 200 })).toBe('$7.99 flat or $2.00 pickup');
    expect(paymentMethodsText(tEn, ['CARD', 'WALLET', 'ACH'])).toBe('card, Apple Pay or ACH');
    expect(paymentMethodsText(tEn, ['CARD', 'ACH'])).toBe('card or ACH');
    expect(paymentMethodsText(tEn, ['CARD'])).toBe('card');
    const notes = payNotes(tEn, { total: '$62.95', methods: ['CARD', 'WALLET', 'ACH'], dates, split: true }).map(plain);
    expect(notes).toEqual([
      'One payment today for the whole cart: $62.95, by card, Apple Pay or ACH.',
      'Split into two shipments: pre-order items are delivered Sun, Oct 25, the rest ship now.',
      'Change quantities or cancel pre-order items for a full refund until Thu, Oct 22, 11:59 PM.',
      'If we miss the delivery date, you choose: a full refund, or wait for the new date.'
    ]);
    expect(familyCities(tEn, ['Lubbock', 'El Paso', 'Toronto', 'Amarillo', 'Lubbock'])).toBe('Lubbock, El Paso, Toronto + 1 more');
  });

  it('builds an all-day calendar event for the delivery day', () => {
    const ics = deliveryIcs({ number: 'LBK-2001', windowName: 'Lubbock Pre-order #7', deliversOn: '2026-10-25', closesAt: LBK.closesAt, timeZone: LBK.timeZone, otherItemCount: 0 }, 'Lubbock, delivery', new Date('2026-10-10T12:00:00Z'));
    expect(ics).toContain('DTSTART;VALUE=DATE:20261025');
    expect(ics).toContain('DTEND;VALUE=DATE:20261026');
    expect(ics).toContain('SUMMARY:Lubbock\\, delivery');
  });
});

describe('attribution capture (src / cr / po, consent-aware)', () => {
  const clearCookies = () => {
    for (const c of document.cookie.split(';')) {
      const name = c.split('=')[0]?.trim();
      if (name) document.cookie = `${name}=; Path=/; Max-Age=0`;
    }
  };
  beforeEach(() => {
    sessionStorage.clear();
    clearCookies();
  });
  afterEach(clearCookies);

  it('keeps the touch for the visit only without consent', () => {
    expect(hasAttributionConsent()).toBe(false);
    expect(captureAttribution(new URLSearchParams('src=ig&cr=story&po=LBK-7'))).toEqual({ src: 'ig', cr: 'story', po: 'LBK-7' });
    expect(document.cookie).not.toContain(ATTRIBUTION_COOKIE);
    sessionStorage.clear();
    expect(readAttribution()).toBeNull();
  });

  it('keeps it 30 days in at_attr with analytics or advertising consent, then expires it', () => {
    document.cookie = `${CONSENT_COOKIE}=${encodeURIComponent(JSON.stringify({ necessary: true, analytics: true }))}; Path=/`;
    expect(hasAttributionConsent()).toBe(true);
    const t0 = Date.now();
    captureAttribution(new URLSearchParams('src=wa&po=LBK-7'), t0);
    expect(document.cookie).toContain(`${ATTRIBUTION_COOKIE}=`);
    sessionStorage.clear();
    expect(readAttribution(t0 + 29 * 86_400_000)).toEqual({ src: 'wa', po: 'LBK-7' });
    expect(readAttribution(t0 + 31 * 86_400_000)).toBeNull();
  });

  it('last touch wins; on-site placements record hero / ribbon / pdp / page; unknown sources are ignored', () => {
    captureAttribution(new URLSearchParams('src=email&cr=announce'));
    setAttribution('ribbon', { po: 'LBK-7' });
    expect(readAttribution()).toEqual({ src: 'ribbon', po: 'LBK-7' });
    expect(captureAttribution(new URLSearchParams('src=<script>'))).toBeNull();
    expect(readAttribution()).toEqual({ src: 'ribbon', po: 'LBK-7' });
  });

  it('drops the 30-day cookie once consent is withdrawn', () => {
    document.cookie = `${CONSENT_COOKIE}=${encodeURIComponent(JSON.stringify({ advertising: true }))}; Path=/`;
    setAttribution('hero', { po: 'LBK-7' });
    sessionStorage.clear();
    document.cookie = `${CONSENT_COOKIE}=${encodeURIComponent(JSON.stringify({ analytics: false, advertising: false }))}; Path=/`;
    expect(readAttribution()).toBeNull();
    expect(document.cookie).not.toContain(`${ATTRIBUTION_COOKIE}=`);
  });
});

describe('storefront pre-order states (mock BFF)', () => {
  it('ribbon: open copy, live countdown that does not spam screen readers, link to the pre-order page', async () => {
    await api().setDeliveryLocation({ zip: '79401' });
    renderWithProviders(<PreorderBanners />);
    const ribbon = await screen.findByTestId('preorder-ribbon');
    expect(ribbon).toHaveAttribute('href', '/pre-orders');
    expect(ribbon).toHaveTextContent('Lubbock pre-order is open');
    expect(ribbon).toHaveTextContent(/delivered \w{3}, \w{3} \d+ · \$7\.99 flat/);
    const timer = within(ribbon).getByRole('timer');
    expect(timer).toHaveAttribute('aria-live', 'off');
    await waitFor(() => expect(timer).toHaveAttribute('aria-label', expect.stringMatching(/^Closes in \d+ days, \d+ hours, \d+ minutes$/)));
    expect(timer.firstElementChild).toHaveAttribute('aria-hidden', 'true');
    expect(timer).toHaveTextContent(/Closes in \d+d \d{2}h \d{2}m \d{2}s/);
  });

  it('metro shopper sees the send-to-family banner with the window door fee', async () => {
    renderWithProviders(<PreorderBanners />);
    const fam = await screen.findByTestId('preorder-family');
    expect(fam).toHaveTextContent('Pre-order open in Lubbock, El Paso, Toronto.');
    expect(fam).toHaveTextContent('Their delivery fee is $7.99 flat.');
  });

  it('badge logic on cards: pre-order tag while open, ships now for other items, locked while upcoming', async () => {
    await api().setDeliveryLocation({ zip: '79401' });
    const jollof = await product(JOLLOF);
    const shea = await product(SHEA);
    const { unmount } = renderWithProviders(
      <>
        <CartAwareProductCard product={jollof} />
        <CartAwareProductCard product={shea} />
      </>
    );
    const [pre, now] = await screen.findAllByTestId('card-availability');
    await waitFor(() => expect(pre).toHaveTextContent(/^Pre-order · delivered \w{3} \d+$/));
    expect(screen.getAllByTestId('preorder-badge')).toHaveLength(1);
    expect(now).toHaveTextContent('Ships now · UPS Ground 3–5 days');
    expect(screen.getByRole('button', { name: `Add to cart: ${jollof.title}` })).toBeEnabled();
    unmount();

    await api().setDeliveryLocation({ zip: '79901' });
    renderWithProviders(<CartAwareProductCard product={jollof} />);
    await waitFor(() => expect(screen.getByTestId('card-availability')).toHaveTextContent(/^Pre-order opens \w{3} \d+$/));
    expect(screen.getByRole('button', { name: new RegExp(`^Opens \\w{3} \\d+: ${jollof.title}`) })).toBeDisabled();
  });

  it('cart split: pre-order group with door $7.99 / pickup free, ships-now group, totals and pay notes', async () => {
    const c = api();
    await c.setDeliveryLocation({ zip: '79401' });
    const jollof = await c.getProduct(JOLLOF);
    const shea = await c.getProduct(SHEA);
    await c.addToCart({ offerId: jollof.buyBox?.offerId ?? '', quantity: 2 });
    await c.addToCart({ offerId: shea.buyBox?.offerId ?? '', quantity: 1 });
    await c.selectAllCartLines(true);
    const user = userEvent.setup();
    renderWithProviders(<CartView />);
    const head = await screen.findByTestId('cart-preorder-head');
    expect(head).toHaveTextContent(/Pre-order · delivered \w{3}, \w{3} \d+/);
    expect(screen.getByTestId('cart-ships-now-head')).toHaveTextContent('Ships now · UPS Ground, 3–5 days');
    const door = screen.getByTestId('fulfil-door');
    const pickup = screen.getByTestId('fulfil-pickup');
    expect(door).toHaveAttribute('aria-pressed', 'true');
    expect(door).toHaveTextContent('$7.99');
    expect(pickup).toHaveTextContent('Free');
    expect(pickup).toHaveTextContent(/10am–2pm/);
    const summary = screen.getByTestId('cart-preorder-summary');
    expect(summary).toHaveTextContent('Pre-order · door delivery$7.99');
    const items = jollof.buyBox?.priceMinor ?? 0;
    const sheaMinor = shea.buyBox?.priceMinor ?? 0;
    const cart = await c.getCart();
    const shipNow = cart.packages.filter((p) => !p.preorder).reduce((a, p) => a + (p.deliveryOptions[0]?.feeMinor ?? 0), 0);
    expect(screen.getByTestId('cart-preorder-total')).toHaveTextContent(fmt.money(items * 2 + sheaMinor + 799 + shipNow));
    expect(screen.getByTestId('preorder-pay-notes')).toHaveTextContent('by card, Apple Pay or ACH.');
    expect(screen.getByTestId('preorder-pay-notes')).toHaveTextContent('Split into two shipments');

    await user.click(pickup);
    await waitFor(() => expect(screen.getByTestId('fulfil-pickup')).toHaveAttribute('aria-pressed', 'true'));
    expect(screen.getByTestId('cart-preorder-summary')).toHaveTextContent('Pre-order · pickup$0.00');
    expect(screen.getByTestId('cart-preorder-total')).toHaveTextContent(fmt.money(items * 2 + sheaMinor + shipNow));
    expect(screen.getByRole('link', { name: 'Proceed to checkout' })).toBeVisible();
  });

  it('locked checkout: the window is not open, so the cart explains why and blocks checkout', async () => {
    const c = api();
    await c.setDeliveryLocation({ zip: '79401' });
    const jollof = await c.getProduct(JOLLOF);
    await c.addToCart({ offerId: jollof.buyBox?.offerId ?? '', quantity: 1 });
    await preview({ stage: 'UPCOMING' });
    renderWithProviders(<CartView />);
    const locked = await screen.findByTestId('cart-preorder-locked');
    expect(locked).toHaveTextContent(/^Pre-order items can be bought from \w{3}, \w{3} \d+, .+\. They stay in your cart until then\.$/);
    expect(screen.getByRole('button', { name: 'Pre-order not open' })).toBeDisabled();
    expect(screen.queryByTestId('fulfil-door')).toBeNull();
  });

  it('waitlist fallback: off ribbon and home card collect an email, no checkout of items', async () => {
    await api().setDeliveryLocation({ zip: '79401' });
    await preview({ enabled: false, fallback: 'WAITLIST' });
    const user = userEvent.setup();
    renderWithProviders(
      <>
        <PreorderBanners />
        <PreorderHome />
      </>
    );
    expect(await screen.findByTestId('preorder-off-ribbon')).toHaveTextContent("We don't deliver to Lubbock yet. Join the waitlist to hear when the next pre-order opens.");
    expect(screen.getByRole('heading', { name: 'Pre-orders are coming to Lubbock.' })).toBeVisible();
    await user.type(screen.getByLabelText('Email'), 'kwame@example.com');
    await user.click(screen.getByRole('button', { name: 'Join the waitlist' }));
    expect(await screen.findByText("You're on the list. We'll email kwame@example.com when a pre-order opens in Lubbock.")).toHaveAttribute('role', 'status');
  });

  it('ship fallback: off ribbon and headline', async () => {
    await api().setDeliveryLocation({ zip: '79401' });
    await preview({ enabled: false });
    renderWithProviders(<PreorderHome />);
    expect(await screen.findByRole('heading', { name: 'Shipping by UPS Ground for now.' })).toBeVisible();
    expect(screen.queryByTestId('preorder-waitlist')).toBeNull();
  });

  it('window page: rules, timeline, closed support block', async () => {
    await api().setDeliveryLocation({ zip: '79101' });
    renderWithProviders(<PreorderView placeParam={null} />);
    expect(await screen.findByRole('heading', { level: 1, name: 'Amarillo Pre-order #4' })).toBeVisible();
    expect(screen.getByText('Closed')).toBeVisible();
    expect(screen.getByTestId('preorder-missed')).toHaveTextContent('Missed the closing date?');
    const rules = screen.getByRole('list', { name: 'How this pre-order works' });
    expect(within(rules).getAllByRole('listitem')).toHaveLength(4);
    // Amarillo #4 offers door delivery only: the rule quotes the door fee without pickup.
    expect(rules).toHaveTextContent(/Everything is delivered on \w{3}, \w{3} \d+, \$7\.99 flat\. There's no minimum order count\./);
    const steps = within(screen.getByTestId('preorder-timeline')).getAllByRole('listitem');
    expect(steps[2]).toHaveAttribute('aria-current', 'step');
  });
});

describe('waitlist unsubscribe page', () => {
  it('asks, then takes the email off the waitlist and confirms', async () => {
    renderWithProviders(<WaitlistUnsubscribe token="wl-3f9c2a7d" />);
    expect(screen.getByRole('heading', { name: tEn('preorder.unsubscribe.title') })).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: tEn('preorder.unsubscribe.confirm') }));
    expect(await screen.findByRole('heading', { name: tEn('preorder.unsubscribe.doneTitle') })).toBeInTheDocument();
    expect(screen.getByTestId('waitlist-unsubscribe')).toHaveAttribute('data-state', 'done');
    expect(screen.getByRole('link', { name: tEn('preorder.unsubscribe.browse') })).toHaveAttribute('href', '/pre-orders');
  });

  it('explains a missing or unknown token', async () => {
    const { unmount } = renderWithProviders(<WaitlistUnsubscribe token={null} />);
    expect(screen.getByRole('heading', { name: tEn('preorder.unsubscribe.invalidTitle') })).toBeInTheDocument();
    unmount();
    renderWithProviders(<WaitlistUnsubscribe token="invalid-token-1" />);
    await userEvent.click(screen.getByRole('button', { name: tEn('preorder.unsubscribe.confirm') }));
    expect(await screen.findByRole('heading', { name: tEn('preorder.unsubscribe.invalidTitle') })).toBeInTheDocument();
  });
});
