import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';

import { CheckoutChrome } from '@/components/checkout/CheckoutChrome';
import { CheckoutView } from '@/components/checkout/CheckoutView';

import { installMockBff, signInDemo } from './mockBff';
import { renderWithProviders } from './render';

installMockBff();

async function withJollof(quantity = 2) {
  const api = await signInDemo();
  await api.addToCart({ offerId: (await api.getProduct('AT000000P2')).buyBox?.offerId ?? '', quantity });
}

/** The checkout in the Amazon structure: slim bar, summary rows, sections, a closing Place your order, notes (owner brief). */
describe('checkout layout', () => {
  it('has a slim bar: the brand, a centred "Secure checkout" title and the cart link, and nothing else', async () => {
    await withJollof();
    renderWithProviders(<CheckoutChrome><CheckoutView /></CheckoutChrome>);
    const header = await screen.findByTestId('checkout-header');
    expect(within(header).getByRole('heading', { level: 1 })).toHaveTextContent('Secure checkout');
    const cart = await within(header).findByRole('link', { name: /^Cart, 2 items$/ });
    expect(cart).toHaveAttribute('href', '/cart');
    expect(within(header).queryByRole('searchbox')).toBeNull();
  });

  it('puts Place your order first in the summary, then the legal line, then Items / Delivery / Estimated tax / Order total', async () => {
    await withJollof();
    renderWithProviders(<CheckoutView />);
    await screen.findByTestId('checkout-package');
    const rail = screen.getByRole('complementary', { name: 'Order summary' });
    const first = within(rail).getAllByRole('button')[0] as HTMLElement;
    expect(first).toHaveTextContent('Place your order');
    const terms = within(rail).getByTestId('checkout-terms');
    expect(within(terms).getByRole('link', { name: 'Privacy Notice' })).toHaveAttribute('href', '/legal/privacy');
    expect(within(terms).getByRole('link', { name: 'Conditions of Use' })).toHaveAttribute('href', '/legal/conditions-of-use');
    expect(first.compareDocumentPosition(terms) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    await waitFor(() => expect(within(rail).getByTestId('checkout-tax-amount').textContent).toMatch(/^\$\d+\.\d{2}$/));
    expect(Array.from(rail.querySelectorAll('dt')).map((d) => d.textContent)).toEqual(['Items (2)', 'Delivery', 'Estimated tax', 'Order total']);
  });

  it('orders the sections address, payment (Change, promo line under it), items per seller package, then a second Place your order with the total', async () => {
    await withJollof();
    const user = userEvent.setup();
    renderWithProviders(<CheckoutView />);
    await screen.findByTestId('checkout-package');
    expect(screen.getAllByRole('heading', { level: 2 }).map((h) => h.textContent).slice(0, 3)).toEqual(['Delivery address', 'Payment method', 'Review items and delivery']);
    for (const n of [1, 2, 3]) expect(screen.getByTestId(`checkout-change-${n}`)).toBeInTheDocument();
    expect(screen.getByTestId('checkout-change-3')).toHaveAttribute('href', '/cart');
    // The promo line sits under the payment section even while it is collapsed to its summary.
    const pay = screen.getByTestId('checkout-step-2');
    expect(within(pay).getByTestId('checkout-coupon-open')).toBeVisible();
    // Completed sections say so in words, not by colour alone.
    expect(screen.getByTestId('checkout-step-1')).toHaveTextContent('Completed');

    const finish = screen.getByTestId('checkout-finish');
    const package1 = screen.getByTestId('checkout-package');
    expect(package1.compareDocumentPosition(finish) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    await waitFor(() => expect(within(finish).getByTestId('checkout-finish-total')).toHaveTextContent(/^Order total: \$\d+\.\d{2}$/));
    expect(within(finish).getByRole('button', { name: 'Place your order' })).toBeEnabled();
    expect(within(finish).getByRole('link', { name: 'Back to cart' })).toHaveAttribute('href', '/cart');
    expect(within(finish).getByRole('link', { name: 'Returns & Refunds' })).toHaveAttribute('href', '/legal/returns');
    expect(within(finish).getByRole('link', { name: 'Help center' })).toHaveAttribute('href', '/help');
    expect(within(finish).getByRole('button', { name: 'About sales tax' })).toBeInTheDocument();
    await user.click(within(pay).getByTestId('checkout-coupon-open'));
    expect(within(pay).getByLabelText('Promo code')).toBeInTheDocument();
  });
});
