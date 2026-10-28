import { resetSellerDb } from '@afrotransact/api-client/mocks/seller';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it } from 'vitest';

import { ReviewsView } from '@/components/reviews/ReviewsView';
import { tEn } from '@/i18n/t';

import { asRole } from './mock-api';
import { renderScreen } from './render';

describe('Seller Center reviews (against the seller mocks)', () => {
  beforeEach(() => {
    resetSellerDb(new Date());
    asRole(null);
  });

  it('lists verified-purchase reviews by first name and initial, never the customer', async () => {
    await renderScreen(<ReviewsView />);
    const cards = await screen.findAllByTestId('seller-review');
    expect(cards).toHaveLength(2);
    expect(within(cards[0] as HTMLElement).getByText('Amara O.')).toBeInTheDocument();
    expect(within(cards[0] as HTMLElement).getByText('Verified purchase')).toBeInTheDocument();
    expect(document.body.textContent).not.toMatch(/customerId|@example\.com/);
  });

  it('offers a reply once, then shows it and no longer offers one; there is no way to edit, hide or delete', async () => {
    const user = userEvent.setup();
    await renderScreen(<ReviewsView />);
    const [first] = await screen.findAllByTestId('seller-review');
    expect(screen.queryByRole('button', { name: /edit|hide|delete|remove/i })).not.toBeInTheDocument();
    await user.click(within(first as HTMLElement).getByRole('button', { name: 'Reply' }));
    const dialog = await screen.findByRole('dialog');
    await user.click(within(dialog).getByRole('button', { name: 'Post reply' }));
    expect(await within(dialog).findByText(tEn('sc.reviews.replyRequired'))).toBeInTheDocument();
    await user.type(within(dialog).getByLabelText('Your public reply'), 'Thank you, Amara.');
    await user.click(within(dialog).getByRole('button', { name: 'Post reply' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    const [updated] = await screen.findAllByTestId('seller-review');
    expect(await within(updated as HTMLElement).findByText('Thank you, Amara.')).toBeInTheDocument();
    expect(within(updated as HTMLElement).queryByRole('button', { name: 'Reply' })).not.toBeInTheDocument();
  });

  it('filters the reviews that wait for a reply and reports a review to Trust & Safety', async () => {
    const user = userEvent.setup();
    await renderScreen(<ReviewsView />);
    await screen.findAllByTestId('seller-review');
    await user.selectOptions(screen.getByLabelText('Show'), 'unanswered');
    await waitFor(() => expect(screen.getAllByTestId('seller-review')).toHaveLength(1));
    await user.click(screen.getByRole('button', { name: 'Report' }));
    const dialog = await screen.findByRole('dialog');
    await user.click(within(dialog).getByRole('button', { name: 'Send report' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(await screen.findByText('Reported once')).toBeInTheDocument();
  });

  it('answers a role without the Listings module with a refusal', async () => {
    asRole('seller.finance');
    await renderScreen(<ReviewsView />);
    expect(await screen.findByRole('alert')).toBeInTheDocument();
  });
});
