import { resetSellerDb } from '@afrotransact/api-client/mocks/seller';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it } from 'vitest';

import { BusinessPageView, validateBusinessPage } from '@/components/business/BusinessPageView';

import { asRole } from './mock-api';
import { renderScreen } from './render';

describe('Business page settings', () => {
  beforeEach(() => {
    resetSellerDb(new Date());
    asRole(null);
  });

  it('checks the colour and description before saving', () => {
    expect(validateBusinessPage({ description: '', brandColor: 'green', logoUrl: '' })).toEqual({ brandColor: 'Choose a colour from the list, or enter a hex code' });
    expect(validateBusinessPage({ description: 'x'.repeat(501), brandColor: '', logoUrl: '' }).description).toBe('At most 500 characters');
    expect(validateBusinessPage({ description: 'Jollof', brandColor: '#aabbcc', logoUrl: '' })).toEqual({});
  });

  it('saves a new colour and description and shows them in the preview', async () => {
    const user = userEvent.setup();
    await renderScreen(<BusinessPageView />);
    await user.click(await screen.findByRole('radio', { name: 'Custom' }));
    await user.type(screen.getByLabelText('Hex code'), 'ABC');
    await user.type(screen.getByLabelText(/What you sell or do/), 'Jollof and more');
    await user.click(screen.getByRole('button', { name: 'Save changes' }));
    expect(await screen.findByText('Your business page is updated.')).toBeInTheDocument();
    await waitFor(() => expect(screen.getByRole('button', { name: 'Save changes' })).toBeDisabled());
    expect(screen.getByRole('region', { name: 'Preview' })).toHaveTextContent('Jollof and more');
  });

  it('refuses a colour that is not a hex code without calling the server', async () => {
    const user = userEvent.setup();
    await renderScreen(<BusinessPageView />);
    await user.click(await screen.findByRole('radio', { name: 'Custom' }));
    await user.type(screen.getByLabelText('Hex code'), 'green');
    await user.click(screen.getByRole('button', { name: 'Save changes' }));
    expect(await screen.findAllByText(/Choose a colour from the list/)).not.toHaveLength(0);
  });
});
