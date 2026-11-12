/* The cart's tax estimate before an address is chosen: a signed-in shopper's saved default address is used; a guest or a new
   shopper types a ZIP code. No ZIP code and no address means an input, never an amount and never "$0.00". */
import { createApiClient } from '@afrotransact/api-client';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';

import { CartView } from '@/components/cart/CartView';

import { installMockBff, sent, signInDemo } from './mockBff';
import { renderWithProviders } from './render';

installMockBff();

describe('cart tax estimate', () => {
  it('a signed-in shopper with a saved default address sees the estimate for it without typing anything', async () => {
    const api = await signInDemo();
    const seller = await api.getProduct('AT000000P6');
    await api.addToCart({ offerId: seller.buyBox?.offerId ?? '', quantity: 1 });
    renderWithProviders(<CartView />);
    const box = await screen.findByTestId('cart-tax-estimate');
    const result = await within(box).findByTestId('tax-zip-result');
    expect(result).toHaveTextContent('Estimated for Houston, TX');
    expect(within(box).getByTestId('tax-zip-amount').textContent).toMatch(/^\$\d+\.\d{2}$/);
    expect(box.textContent).not.toMatch(/when you place your order|at checkout/i);
  });

  it('a guest is asked for a ZIP code, shown no amount until one is typed, and gets the estimate for that place', async () => {
    const api = createApiClient({ baseUrl: `${window.location.origin}/api/bff` });
    const seller = await api.getProduct('AT000000P6');
    await api.addToCart({ offerId: seller.buyBox?.offerId ?? '', quantity: 1 });
    renderWithProviders(<CartView />);
    const box = await screen.findByTestId('cart-tax-estimate');
    const form = await within(box).findByTestId('tax-zip-form');
    expect(within(form).getByText('Enter your ZIP code to see tax')).toBeInTheDocument();
    expect(box.textContent).not.toMatch(/\$0\.00/);
    const user = userEvent.setup();
    await user.type(within(form).getByLabelText('Enter your ZIP code to see tax'), '77002');
    await user.click(within(form).getByRole('button', { name: 'See tax' }));
    await waitFor(() => expect(within(box).getByTestId('tax-zip-result')).toHaveTextContent('Estimated for Houston, TX'));
    expect(sent.some((r) => r.url.includes('/v1/checkout/tax-estimate') && r.url.includes('zip=77002'))).toBe(true);
    // changing the ZIP code goes back to the input
    await user.click(within(box).getByRole('button', { name: 'Change ZIP code' }));
    expect(within(box).getByTestId('tax-zip-form')).toBeInTheDocument();
  });
});
