import { MOCK_GIFT_CODE } from '@afrotransact/api-client/mocks';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';

import { CheckoutView } from '@/components/checkout/CheckoutView';
import { CreditView } from '@/components/credit/CreditView';
import { GiftCardView } from '@/components/credit/GiftCardView';
import { OrderDetailView } from '@/components/orders/OrderDetailView';

import { installMockBff, signInDemo } from './mockBff';
import { renderWithProviders } from './render';

installMockBff();

describe('AfroTransact credit (S320)', () => {
  it('shows the balance and history, and offers no way to take credit out', async () => {
    await signInDemo();
    renderWithProviders(<CreditView />);
    expect(await screen.findByTestId('credit-balance')).toHaveTextContent('$25.00');
    const rows = await screen.findAllByTestId('credit-entry');
    expect(rows).toHaveLength(3);
    expect(rows[0]).toHaveTextContent(/Order/);
    expect(rows[0]).toHaveTextContent(/−\$50\.00/);
    expect(rows[1]).toHaveTextContent(/Gift card/);
    expect(rows[1]).toHaveTextContent(/\+\$50\.00/);
    expect(screen.queryByRole('button', { name: /withdraw|cash ?out|payout|transfer|refund to card/i })).not.toBeInTheDocument();
    expect(screen.getByText(/can’t be withdrawn/)).toBeInTheDocument();
  });

  it('redeems a gift card once: wrong codes are refused, the right one adds to the balance', async () => {
    await signInDemo();
    const user = userEvent.setup();
    renderWithProviders(<CreditView />);
    const box = await screen.findByTestId('credit-redeem-code');
    await user.type(box, 'AAAA-BBBB-CCCC-DDDD');
    await user.click(screen.getByTestId('credit-redeem-button'));
    expect(await screen.findByText(/That code isn’t valid/)).toBeInTheDocument();
    await user.clear(box);
    await user.type(box, MOCK_GIFT_CODE);
    await user.click(screen.getByTestId('credit-redeem-button'));
    await waitFor(() => expect(screen.getByTestId('credit-balance')).toHaveTextContent('$75.00'));
    expect(screen.queryByText(/That code isn’t valid/)).not.toBeInTheDocument();
    await user.type(box, MOCK_GIFT_CODE);
    await user.click(screen.getByTestId('credit-redeem-button'));
    expect(await screen.findByText(/already redeemed/)).toBeInTheDocument();
    expect(screen.getByTestId('credit-balance')).toHaveTextContent('$75.00');
  });
});

describe('gift cards (S321)', () => {
  it('checks the amount and email, then buys with a card and lists the card', async () => {
    await signInDemo();
    const user = userEvent.setup();
    renderWithProviders(<GiftCardView />);
    const form = await screen.findByTestId('gift-card-form');
    await within(form).findByRole('button', { name: '$50.00' });
    await user.click(screen.getByTestId('gift-card-buy'));
    expect(await within(form).findByText('Enter the recipient’s email address.')).toBeInTheDocument();

    await user.type(within(form).getByLabelText('Other amount'), '5');
    await user.type(within(form).getByLabelText('Recipient’s email'), 'ama@example.com');
    await user.click(screen.getByTestId('gift-card-buy'));
    expect(await within(form).findByText(/Choose an amount between \$10\.00 and \$500\.00/)).toBeInTheDocument();

    await user.clear(within(form).getByLabelText('Other amount'));
    await user.click(within(form).getByRole('button', { name: '$25.00' }));
    const card = await screen.findByTestId('mock-payment-element');
    await user.type(within(card).getByLabelText('Card number'), '4242 4242 4242 4242');
    await user.type(within(card).getByLabelText('Expiration'), '12/34');
    await user.type(within(card).getByLabelText('Security code'), '123');
    await user.type(within(card).getByLabelText('ZIP'), '77042');
    await user.click(screen.getByTestId('gift-card-buy'));
    expect(await screen.findByTestId('gift-card-done')).toHaveTextContent(/ama@example\.com/);
    const rows = await screen.findAllByTestId('gift-card-row');
    expect(rows).toHaveLength(1);
    expect(rows[0]).toHaveTextContent('$25.00');
  });
});

describe('credit at checkout (S320)', () => {
  it('applies credit first, shows what the card pays and places the order with useCredit', async () => {
    const api = await signInDemo();
    const jollof = await api.getProduct('AT000000P2');
    await api.addToCart({ offerId: jollof.buyBox?.offerId ?? '', quantity: 12 });
    const user = userEvent.setup();
    renderWithProviders(<CheckoutView />);
    const box = await screen.findByTestId('checkout-credit');
    // Credit is never spent by surprise: the shopper chooses it.
    expect(within(box).getByTestId('checkout-use-credit')).not.toBeChecked();
    expect(screen.queryByTestId('checkout-credit-row')).not.toBeInTheDocument();
    await user.click(within(box).getByTestId('checkout-use-credit'));
    expect(await screen.findByTestId('checkout-credit-split')).toHaveTextContent(/Credit pays \$25\.00; your card pays/);
    expect(screen.getByTestId('checkout-credit-row')).toHaveTextContent('−$25.00');

    await user.click(within(box).getByTestId('checkout-use-credit'));
    expect(screen.queryByTestId('checkout-credit-split')).not.toBeInTheDocument();
    expect(screen.queryByTestId('checkout-credit-row')).not.toBeInTheDocument();
    await user.click(within(box).getByTestId('checkout-use-credit'));

    await user.click(screen.getByRole('button', { name: 'Place your order' }));
    await waitFor(async () => expect((await api.getAccountCredit()).balanceMinor).toBe(0), { timeout: 5000 });
    const orders = await api.getOrderPage();
    expect(orders.items.length).toBeGreaterThan(0);
  });

  it('needs no payment method when credit pays the whole order', async () => {
    const api = await signInDemo();
    const jollof = await api.getProduct('AT000000P2');
    await api.addToCart({ offerId: jollof.buyBox?.offerId ?? '', quantity: 1 });
    const user = userEvent.setup();
    renderWithProviders(<CheckoutView />);
    const box = await screen.findByTestId('checkout-credit');
    await user.click(within(box).getByTestId('checkout-use-credit'));
    expect(await screen.findByTestId('checkout-credit-split')).toHaveTextContent(/No card is needed/);
    expect(screen.queryAllByTestId('checkout-payment')).toHaveLength(0);
    await user.click(screen.getByRole('button', { name: 'Place your order' }));
    const push = (await import('next/navigation')).useRouter().push;
    await waitFor(() => expect(push).toHaveBeenCalledWith(expect.stringMatching(/^\/orders\?placed=AT-2026-/)), { timeout: 5000 });
    const credit = await api.getAccountCredit();
    expect(credit.balanceMinor).toBeLessThan(2500);
    expect(credit.entries[0]).toMatchObject({ kind: 'SPEND', source: 'ORDER' });
    const order = (await api.getOrderPage()).items[0];
    expect(order?.status).toBe('placed');
  });
});

describe('refunds as credit (S320)', () => {
  it('shows what credit paid and takes the card part of refunds as credit, after a second click', async () => {
    const api = await signInDemo();
    const jollof = await api.getProduct('AT000000P2');
    await api.addToCart({ offerId: jollof.buyBox?.offerId ?? '', quantity: 12 });
    const sum = await api.getCheckoutSummary();
    const conf = await api.placeOrder(
      { addressId: 'ad1', useCredit: true, paymentMethodId: 'pm_visa4242', deliverySelections: [{ packageId: sum.packages[0]?.packageId ?? '', option: 'carrier' }] },
      'checkout-credit-ui-0001'
    );
    await api.confirmCheckoutPayment(conf.orderNumber);
    const user = userEvent.setup();
    renderWithProviders(<OrderDetailView orderNumber={conf.orderNumber} />);
    expect(await screen.findByTestId('order-credit')).toHaveTextContent('$25.00');
    await user.click(await screen.findByTestId('order-card-as-credit-button'));
    expect(await screen.findByText(/can’t be turned back into money/)).toBeInTheDocument();
    await user.click(screen.getByTestId('order-card-as-credit-confirm'));
    await waitFor(async () => expect((await api.getOrder(conf.orderNumber)).payment?.cardRefundsAsCredit).toBe(true));
    await waitFor(() => expect(screen.queryByTestId('order-card-as-credit-button')).not.toBeInTheDocument());
  });
});

