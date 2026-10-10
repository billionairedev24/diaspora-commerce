import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useRouter } from 'next/navigation';
import { describe, expect, it } from 'vitest';

import { AddressBook } from '@/components/account/AddressBook';
import { PaymentsView } from '@/components/account/PaymentsView';
import { CheckoutView } from '@/components/checkout/CheckoutView';
import { ServiceDetailView } from '@/components/services/ServiceDetailView';
import { LolaSheet } from '@/components/shell/LolaSheet';
import { tEn } from '@/i18n/t';
import { applyLolaEvent as apply, GREETING } from '@/lib/lola/useLola';

const applyLolaEvent: (m: Parameters<typeof apply>[0], e: Parameters<typeof apply>[1]) => ReturnType<typeof apply> = (m, e) => apply(m, e, tEn);

import { installMockBff,signInDemo } from './mockBff';
import { renderWithProviders } from './render';

installMockBff();

describe('Lola stream reducer', () => {
  it('builds the answer token by token, keeps proposals and finishes on done', () => {
    let m = { ...GREETING, id: 'a', text: '', streaming: true };
    m = applyLolaEvent(m, { type: 'tool_call', data: { id: 't1', tool: 'search_products', label: 'Searching for egusi' } });
    m = applyLolaEvent(m, { type: 'tool_result', data: { id: 't1', tool: 'search_products', kind: 'products', data: { products: [{ atin: 'AT000000P2', title: 'Jollof', priceMinor: 649 }] } } });
    m = applyLolaEvent(m, { type: 'action_proposal', data: { proposal: { id: 'p1', type: 'ADD_TO_CART', label: 'Add all', payload: {} } } });
    m = applyLolaEvent(m, { type: 'token', data: { text: 'Here ' } });
    m = applyLolaEvent(m, { type: 'token', data: { text: 'you go' } });
    expect(m.text).toBe('Here you go');
    expect(m.tools[0]?.done).toBe(true);
    m = applyLolaEvent(m, { type: 'done', data: { sessionId: 'lola_12345678', message: 'Here you go.', products: [], proposals: [], suggestions: ['More'] } });
    expect(m.streaming).toBe(false);
    expect(m.text).toBe('Here you go.');
    expect(m.products).toHaveLength(1);
    expect(m.proposals.map((p) => p.state)).toEqual(['open']);
  });
});

describe('Lola sheet (mock SSE)', () => {
  it('keeps the greeting, streams the answer and adds the proposal to the cart on confirm', async () => {
    await signInDemo();
    const user = userEvent.setup();
    renderWithProviders(<LolaSheet open onOpenChange={() => undefined} />);
    expect(screen.getByText(/I’m Lola/)).toBeInTheDocument();
    await user.type(screen.getByRole('textbox', { name: 'Message Lola' }), 'jollof seasoning{Enter}');
    const proposal = await screen.findByTestId('lola-proposal', {}, { timeout: 5000 });
    await waitFor(() => expect(screen.getByRole('log').textContent).toContain('in stock'), { timeout: 8000 });
    expect(screen.getByText(/I’m Lola/)).toBeInTheDocument();
    await user.click(within(proposal).getByRole('button', { name: 'Add to cart' }));
    expect(await within(proposal).findByText('Done')).toBeInTheDocument();
  }, 20_000);
});

describe('account pages (mock BFF)', () => {
  it('adds an address with autocomplete fields and validation', async () => {
    await signInDemo();
    const user = userEvent.setup();
    renderWithProviders(<AddressBook />);
    expect(await screen.findAllByTestId('address-card')).toHaveLength(2);
    await user.click(screen.getByRole('button', { name: /Add address/ }));
    const dialog = await screen.findByRole('dialog');
    await user.click(within(dialog).getByRole('button', { name: 'Add address' }));
    // Under the field and in the error summary, which links to the field (S194).
    expect(within(dialog).getByRole('link', { name: 'Enter the full name' })).toHaveAttribute('href', '#addr-name');
    expect(within(dialog).getAllByText('Enter the full name')).toHaveLength(2);
    await user.type(within(dialog).getByLabelText(/Full name/), 'Kofi Mensah');
    await user.type(within(dialog).getByLabelText(/Phone number/), '+17135550100');
    await user.type(within(dialog).getByRole('combobox', { name: /Street address/ }), '9800 Westheimer Rd');
    await user.type(within(dialog).getByLabelText('City'), 'Houston');
    await user.selectOptions(within(dialog).getByLabelText('State'), 'TX');
    await user.type(within(dialog).getByLabelText('ZIP code'), '77042');
    await user.click(within(dialog).getByRole('button', { name: 'Add address' }));
    await waitFor(() => expect(screen.getAllByTestId('address-card')).toHaveLength(3));
  });

  it('saves a card through the mock Payment Element; a declined test card is refused', async () => {
    await signInDemo();
    const user = userEvent.setup();
    renderWithProviders(<PaymentsView />);
    expect(await screen.findAllByTestId('payment-method')).toHaveLength(3);
    await user.click(screen.getByRole('button', { name: 'Add a payment method' }));
    const form = await screen.findByTestId('mock-payment-element');
    await user.type(within(form).getByLabelText('Card number'), '4000 0000 0000 9995');
    await user.type(within(form).getByLabelText('Expiration'), '12/34');
    await user.type(within(form).getByLabelText('Security code'), '123');
    await user.type(within(form).getByLabelText('ZIP'), '77042');
    await user.click(within(form).getByRole('button', { name: 'Save payment method' }));
    expect(within(form).getByRole('link', { name: 'Your card was declined.' })).toHaveAttribute('href', '#pm-number');
    await user.clear(within(form).getByLabelText('Card number'));
    await user.type(within(form).getByLabelText('Card number'), '5555 5555 5555 4444');
    await user.click(within(form).getByRole('button', { name: 'Save payment method' }));
    await waitFor(() => expect(screen.getAllByTestId('payment-method')).toHaveLength(4));
    expect(screen.getAllByText('Mastercard ending in 4444', { selector: 'strong' })).toHaveLength(2);
  });
});

describe('checkout (mock BFF + mock Stripe)', () => {
  it('walks the three steps, chooses AfroTransact Delivery and places the order', async () => {
    const api = await signInDemo();
    const jollof = await api.getProduct('AT000000P2');
    await api.addToCart({ offerId: jollof.buyBox?.offerId ?? '', quantity: 2 });
    const user = userEvent.setup();
    renderWithProviders(<CheckoutView />);
    expect(await screen.findAllByTestId('checkout-address')).toHaveLength(2);
    await user.click(screen.getByRole('button', { name: 'Deliver to this address' }));
    expect(await screen.findAllByTestId('checkout-payment')).toHaveLength(3);
    expect(screen.getByRole('group', { name: 'Express checkout' })).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Use this payment method' }));
    const pkg = await screen.findByTestId('checkout-package');
    await user.click(within(pkg).getByRole('radio', { name: /AfroTransact Delivery/ }));
    expect(within(pkg).getByText(/Delivery preferences/)).toBeInTheDocument();
    await user.click(within(pkg).getByRole('radio', { name: /Pick up/ }));
    expect(within(pkg).getByText('Houston FC — Will Call')).toBeInTheDocument();
    expect(within(pkg).getAllByText(/Mon–Sat 8 am–8 pm/).length).toBeGreaterThan(0);
    await user.click(within(pkg).getByRole('radio', { name: /AfroTransact Delivery/ }));
    await user.click(screen.getByRole('button', { name: 'Place your order' }));
    const push = useRouter().push;
    await waitFor(() => expect(push).toHaveBeenCalledWith(expect.stringMatching(/^\/orders\?placed=AT-2026-/)), { timeout: 5000 });
    const orders = await api.getOrderPage();
    expect(orders.items[0]?.shipments[0]?.deliveryCode).toMatch(/^\d{4}$/);
  });
});

describe('services booking (mock BFF)', () => {
  it('books with a deposit from live slots', async () => {
    await signInDemo();
    const user = userEvent.setup();
    renderWithProviders(<ServiceDetailView serviceId="svc-braids-cornrows" />);
    expect(await screen.findByRole('heading', { name: /Cornrows/ })).toBeInTheDocument();
    const times = await screen.findByRole('group', { name: 'Times' }, { timeout: 5000 });
    const first = (await within(times).findAllByRole('button'))[0];
    if (!first) throw new Error('no slot');
    await user.click(first);
    await waitFor(() => expect(screen.getAllByRole('radio').some((r) => (r as HTMLInputElement).checked)).toBe(true));
    await user.click(screen.getByRole('button', { name: /Book · \$15\.00 deposit/ }));
    expect(await screen.findByTestId('booking-done')).toHaveTextContent('You’re booked!');
  });
});
