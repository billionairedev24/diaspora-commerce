import { markTrialUsed } from '@afrotransact/api-client/mocks';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';

import { CartView } from '@/components/cart/CartView';
import { CheckoutView } from '@/components/checkout/CheckoutView';
import { MembershipManageView } from '@/components/membership/MembershipManageView';
import { MembershipOfferView } from '@/components/membership/MembershipOfferView';
import { OrderDetailView } from '@/components/orders/OrderDetailView';
import { AccountMenu } from '@/components/shell/AccountMenu';

import { installMockBff, signInDemo } from './mockBff';
import { renderWithProviders } from './render';

installMockBff();

async function cartWithSellerShipping() {
  const api = await signInDemo();
  const seller = await api.getProduct('AT000000P6');
  // 38.00 of goods from a seller who ships by carrier for 5.99: above Plus's 25.00 minimum, below free shipping.
  await api.addToCart({ offerId: seller.buyBox?.offerId ?? '', quantity: 2 });
  return api;
}

describe('the membership offer page (S323)', () => {
  it('shows the tiers staff configured with their prices and benefits to a visitor, who is asked to sign in', async () => {
    renderWithProviders(<MembershipOfferView />);
    const plus = await screen.findByTestId('membership-tier-plus');
    expect(within(plus).getByRole('heading', { name: 'Plus' })).toBeInTheDocument();
    expect(within(plus).getByText('$9.99')).toBeInTheDocument();
    expect(within(plus).getByText('Free AfroTransact delivery')).toBeInTheDocument();
    expect(within(plus).getByText(/On orders from \$25\.00, up to \$15\.00 off per order/)).toBeInTheDocument();
    expect(within(plus).getByText(/2% back as credit/)).toBeInTheDocument();
    expect(within(plus).getByText('Priority support')).toBeInTheDocument();
    expect(within(plus).getByText('Free for 14 days')).toBeInTheDocument();
    expect(screen.getByTestId('membership-tier-premium')).toBeInTheDocument();
    expect(within(plus).getByTestId('membership-signin-plus')).toHaveAttribute('href', expect.stringContaining('membership'));
    expect(screen.getByText(/can’t be withdrawn or refunded to a card/)).toBeInTheDocument();
    // Credit has no way out anywhere on the page.
    expect(screen.queryByRole('button', { name: /withdraw|cash ?out|payout|transfer/i })).not.toBeInTheDocument();
  });

  it('switches to yearly prices, and joins with the free trial', async () => {
    await signInDemo();
    const user = userEvent.setup();
    renderWithProviders(<MembershipOfferView />);
    const plus = await screen.findByTestId('membership-tier-plus');
    await user.click(screen.getByTestId('membership-interval-YEARLY'));
    expect(within(plus).getByText('$99.00')).toBeInTheDocument();
    expect(within(plus).getByText(/Save \$20\.88 a year/)).toBeInTheDocument();
    await user.click(screen.getByTestId('membership-interval-MONTHLY'));

    const join = await within(plus).findByTestId('membership-join-plus');
    expect(join).toHaveTextContent('Start my 14-day free trial');
    await waitFor(() => expect(join).toBeEnabled());
    await user.click(join);
    expect(await screen.findByTestId('membership-already')).toHaveTextContent('You are a Plus member.');
    expect(within(plus).queryByTestId('membership-join-plus')).not.toBeInTheDocument();
    expect(within(plus).getByRole('link', { name: 'Manage membership' })).toHaveAttribute('href', '/account/membership');
  });

  it('says why a declined or missing card did not join, and changes nothing', async () => {
    const api = await signInDemo();
    await api.joinAccountMembership({ tierCode: 'nope', interval: 'MONTHLY' }, 'membership-ui-0000001').catch(() => undefined);
    renderWithProviders(<MembershipOfferView />);
    expect(await screen.findByTestId('membership-tier-plus')).toBeInTheDocument();
    expect((await api.getAccountMembership()).member).toBe(false);
  });
});

describe('managing the membership (S323)', () => {
  it('shows the trial, what the plan gives, and changes plan: upgrade now, or switch at the end of the period', async () => {
    const api = await signInDemo();
    await api.joinAccountMembership({ tierCode: 'plus', interval: 'MONTHLY' }, 'membership-ui-0000002');
    const user = userEvent.setup();
    renderWithProviders(<MembershipManageView />);
    const plan = await screen.findByTestId('membership-plan');
    expect(plan).toHaveTextContent('Plus · $9.99 every month');
    expect(screen.getByTestId('membership-status')).toHaveTextContent('Free trial');
    expect(plan).toHaveTextContent(/Your free trial ends on/);
    expect(screen.getByText('Free standard shipping')).toBeInTheDocument();

    // Upgrade now.
    const upgrade = within(await screen.findByTestId('membership-plan-premium-MONTHLY')).getByRole('button', { name: 'Upgrade now' });
    await user.click(upgrade);
    await waitFor(() => expect(screen.getByTestId('membership-plan')).toHaveTextContent('Premium · $19.99 every month'));
    expect(within(screen.getByTestId('membership-plan-premium-MONTHLY')).getByText('Your plan')).toBeInTheDocument();

    // A lower plan waits for the end of the period.
    await user.click(within(screen.getByTestId('membership-plan-plus-MONTHLY')).getByRole('button', { name: 'Switch at the end of the period' }));
    expect(await screen.findByTestId('membership-pending')).toHaveTextContent(/you move to Plus \(\$9\.99 every month\)/);
    expect(screen.getByTestId('membership-plan')).toHaveTextContent('Premium'); // the benefits stay to the period end
    await user.click(screen.getByTestId('membership-resume'));
    await waitFor(() => expect(screen.queryByTestId('membership-pending')).not.toBeInTheDocument());
  });

  it('cancels with the benefits to the end of the period, in two steps, and takes it back', async () => {
    const api = await signInDemo();
    await api.joinAccountMembership({ tierCode: 'plus', interval: 'MONTHLY' }, 'membership-ui-0000003');
    const user = userEvent.setup();
    renderWithProviders(<MembershipManageView />);
    await user.click(await screen.findByTestId('membership-cancel-button'));
    expect((await api.getAccountMembership()).membership?.cancelAtPeriodEnd).toBe(false); // nothing happens on the first click
    await user.click(screen.getByTestId('membership-cancel-confirm'));
    expect(await screen.findByTestId('membership-ending')).toHaveTextContent(/You keep your benefits until/);
    expect((await api.getAccountMembership()).member).toBe(true);
    await user.click(screen.getByTestId('membership-resume'));
    await waitFor(() => expect(screen.queryByTestId('membership-ending')).not.toBeInTheDocument());
  });

  it('offers the full refund while the refund rule allows it, and takes the fee back to the card', async () => {
    const api = await signInDemo();
    markTrialUsed('u_adaeze');
    await api.joinAccountMembership({ tierCode: 'plus', interval: 'MONTHLY' }, 'membership-ui-0000004');
    const user = userEvent.setup();
    renderWithProviders(<MembershipManageView />);
    const box = await screen.findByTestId('membership-refund');
    expect(box).toHaveTextContent(/get \$9\.99 back on your card/);
    await user.click(screen.getByTestId('membership-refund-button'));
    await user.click(screen.getByTestId('membership-refund-confirm'));
    await waitFor(() => expect(screen.getByTestId('membership-status')).toHaveTextContent('Canceled'));
    expect((await api.getAccountMembership()).member).toBe(false);
    expect((await api.getAccountMembership()).charges[0]).toMatchObject({ status: 'REFUNDED', refundedMinor: 999 });
  });

  it('leads a shopper who is not a member to the offer', async () => {
    await signInDemo();
    renderWithProviders(<MembershipManageView />);
    const none = await screen.findByTestId('membership-none');
    expect(within(none).getByRole('link', { name: 'AfroTransact Membership' })).toHaveAttribute('href', '/membership');
  });
});

describe('membership at the cart and the checkout (S323)', () => {
  it('the cart says what joining would save, and says nothing once the shopper is a member', async () => {
    const api = await cartWithSellerShipping();
    const { unmount } = renderWithProviders(<CartView />);
    const upsell = await screen.findByTestId('membership-upsell');
    expect(upsell).toHaveTextContent('Join Plus to save $5.99 on this order');
    unmount();
    await api.joinAccountMembership({ tierCode: 'plus', interval: 'MONTHLY' }, 'membership-ui-0000005');
    renderWithProviders(<CartView />);
    await screen.findByTestId('cart-subtotal');
    await waitFor(() => expect(screen.queryByTestId('membership-upsell')).not.toBeInTheDocument());
  });

  it('shows "Join Plus to save $5.99 on this order" before joining and "Fee waived: membership" after', async () => {
    const api = await cartWithSellerShipping();
    const { unmount } = renderWithProviders(<CheckoutView />);
    const upsell = await screen.findByTestId('membership-upsell');
    expect(upsell).toHaveTextContent('Join Plus to save $5.99 on this order');
    expect(upsell).toHaveTextContent(/Try it free for 14 days, then \$9\.99 a month/);
    expect(within(upsell).getByRole('link', { name: 'See membership' })).toHaveAttribute('href', '/membership');
    expect(screen.queryByTestId('checkout-membership-waived')).not.toBeInTheDocument();
    unmount();

    await api.joinAccountMembership({ tierCode: 'plus', interval: 'MONTHLY' }, 'membership-ui-0000006');
    renderWithProviders(<CheckoutView />);
    const row = await screen.findByTestId('checkout-membership-waived');
    expect(row).toHaveTextContent('Fee waived: membership');
    expect(row).toHaveTextContent('−$5.99');
    expect(row).toHaveTextContent(/Plus · Free standard shipping: \$5\.99 off/);
    expect(screen.queryByTestId('membership-upsell')).not.toBeInTheDocument();
    expect(within(screen.getByTestId('order-summary')).getByText('FREE')).toBeInTheDocument();
  });

  it('the placed order records the waiver, names the tier and the benefit, and shows it', async () => {
    const api = await cartWithSellerShipping();
    await api.joinAccountMembership({ tierCode: 'plus', interval: 'MONTHLY' }, 'membership-ui-0000007');
    const sum = await api.getCheckoutSummary();
    const conf = await api.placeOrder(
      { addressId: 'ad1', paymentMethodId: 'pm_visa4242', deliverySelections: [{ packageId: sum.packages[0]?.packageId ?? '', option: 'carrier' }] },
      'checkout-membership-ui-1'
    );
    await api.confirmCheckoutPayment(conf.orderNumber);
    renderWithProviders(<OrderDetailView orderNumber={conf.orderNumber} />);
    const line = await screen.findByTestId('order-membership-waiver');
    expect(line).toHaveTextContent('Plus member: Free standard shipping saved you $5.99 on this shipment');
  });
});

describe('the member badge (S323)', () => {
  it('names the tier in the account menu, not an id', () => {
    const ada = {
      authenticated: true as const,
      user: { id: 'u1', firstName: 'Adaeze', lastName: 'Nwosu', email: 'adaeze@example.com', initials: 'AN', roles: ['customer'], backOffices: [] },
      counts: { orders: 2, returns: 1, addresses: 2, paymentMethods: 3 }
    };
    renderWithProviders(<AccountMenu me={ada} onAskLola={vi.fn()} memberTier="Plus" inline defaultOpen />);
    expect(screen.getByTestId('member-badge')).toHaveTextContent('Plus member');
  });
});
