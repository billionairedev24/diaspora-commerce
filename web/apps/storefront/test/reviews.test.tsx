import { atinOf } from '@afrotransact/api-client/mocks';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';

import { OrderDetailView } from '@/components/orders/OrderDetailView';
import { OrdersView } from '@/components/orders/OrdersView';
import { ProductReviews } from '@/components/reviews/ProductReviews';
import { tEn } from '@/i18n/t';

import { installMockBff, signInDemo } from './mockBff';
import { renderWithProviders } from './render';

installMockBff();

const SHEA = atinOf('p15');
const RICE = atinOf('p2');

describe('ratings section of the product page (mock BFF)', () => {
  it('shows the average, count, star breakdown and a sorted, paged list to a guest', async () => {
    const user = userEvent.setup();
    renderWithProviders(<ProductReviews atin={SHEA} title="Shea butter" />);
    expect(await screen.findByText('4.4')).toBeInTheDocument();
    expect(screen.getByText('5 reviews')).toBeInTheDocument();
    const rows = within(screen.getByRole('list', { name: 'Reviews by star rating' })).getAllByRole('button');
    expect(rows).toHaveLength(5);
    expect(rows[0]).toHaveAccessibleName('5 stars: 3 reviews. Show only these');
    // Verified purchase, first name and last initial only, five per page.
    const reviews = await screen.findAllByTestId('review');
    expect(reviews).toHaveLength(5);
    expect(within(reviews[0] as HTMLElement).getByText('Verified purchase')).toBeInTheDocument();
    expect(screen.getByText('Amara O.')).toBeInTheDocument();
    // A guest cannot write; the reason is shown.
    expect(screen.queryByRole('button', { name: 'Write a review' })).not.toBeInTheDocument();
    expect(await screen.findByText(tEn('rev.onlyBuyers'))).toBeInTheDocument();
    // Filter by stars.
    await user.click(rows[0] as HTMLElement);
    await waitFor(() => expect(screen.getAllByTestId('review')).toHaveLength(3));
    expect(screen.getByText('Showing 5-star reviews')).toBeInTheDocument();
  });

  it('offers the shopper who received the item to write a review, and shows it as theirs', async () => {
    await signInDemo();
    const user = userEvent.setup();
    renderWithProviders(<ProductReviews atin={SHEA} title="Shea butter" />);
    await user.click(await screen.findByRole('button', { name: 'Write a review' }));
    const dialog = await screen.findByRole('dialog');
    await user.click(await within(dialog).findByRole('radio', { name: '4 stars' }));
    await user.type(within(dialog).getByLabelText('Title (optional)'), 'Lovely');
    await user.type(within(dialog).getByLabelText('Your review (optional)'), 'Soft and fragrant.');
    await user.click(within(dialog).getByRole('button', { name: 'Submit review' }));
    const own = await screen.findByTestId('own-review');
    expect(within(own).getByText('Your review')).toBeInTheDocument();
    expect(within(own).getByText('Soft and fragrant.')).toBeInTheDocument();
    await waitFor(() => expect(screen.getByText('6 reviews')).toBeInTheDocument());
    expect(screen.queryByRole('button', { name: 'Write a review' })).not.toBeInTheDocument();
  });

  it('tells a shopper who did not receive the item that only buyers can review', async () => {
    await signInDemo();
    renderWithProviders(<ProductReviews atin={RICE} title="Rice" />);
    expect(await screen.findByText(tEn('rev.onlyBuyers'))).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Write a review' })).not.toBeInTheDocument();
  });
});

describe('Rate this item (mock BFF)', () => {
  it('is offered on delivered items in Your orders and opens the review sheet', async () => {
    await signInDemo();
    const user = userEvent.setup();
    renderWithProviders(<OrdersView />);
    const buttons = await screen.findAllByTestId('rate-item');
    expect(buttons.length).toBeGreaterThan(0);
    await user.click(buttons[0] as HTMLElement);
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByRole('heading', { name: 'Rate this item' })).toBeInTheDocument();
    expect(await within(dialog).findByRole('radio', { name: '5 stars' })).toBeInTheDocument();
    expect(within(dialog).getByText('Verified purchase')).toBeInTheDocument();
  });

  it('is offered on the order page for delivered shipments', async () => {
    await signInDemo();
    renderWithProviders(<OrderDetailView orderNumber="AT-2026-104221" />);
    const shipment = await screen.findByTestId('shipment');
    expect(within(shipment).getAllByTestId('rate-item')).toHaveLength(2);
  });

  it('requires stars before submitting', async () => {
    await signInDemo();
    const user = userEvent.setup();
    renderWithProviders(<OrderDetailView orderNumber="AT-2026-104221" />);
    const shipment = await screen.findByTestId('shipment');
    await user.click(within(shipment).getAllByTestId('rate-item')[0] as HTMLElement);
    const dialog = await screen.findByRole('dialog');
    await user.click(await within(dialog).findByRole('button', { name: 'Submit review' }));
    expect(await within(dialog).findByText(tEn('rev.starsRequired'))).toBeInTheDocument();
  });
});
