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
    expect(validateBusinessPage({ description: '', brandColor: 'green', logoUrl: '' })).toEqual({ brandColor: 'Use a colour like #rrggbb' });
    expect(validateBusinessPage({ description: 'x'.repeat(501), brandColor: '', logoUrl: '' }).description).toBe('At most 500 characters');
    expect(validateBusinessPage({ description: 'Jollof', brandColor: '#aabbcc', logoUrl: '' })).toEqual({});
  });

  it('saves a new colour and description and shows them in the preview', async () => {
    const user = userEvent.setup();
    await renderScreen(<BusinessPageView />);
    const colour = await screen.findByLabelText('Brand colour');
    await user.type(colour, '#aabbcc');
    await user.type(screen.getByLabelText(/What you offer/), 'Jollof and more');
    await user.click(screen.getByRole('button', { name: 'Save changes' }));
    expect(await screen.findByText('Your business page is updated.')).toBeInTheDocument();
    await waitFor(() => expect(screen.getByRole('button', { name: 'Save changes' })).toBeDisabled());
    expect(screen.getByRole('region', { name: 'Preview' })).toHaveTextContent('Jollof and more');
  });

  it('refuses a colour that is not #rrggbb without calling the server', async () => {
    const user = userEvent.setup();
    await renderScreen(<BusinessPageView />);
    await user.type(await screen.findByLabelText('Brand colour'), 'green');
    await user.click(screen.getByRole('button', { name: 'Save changes' }));
    expect(await screen.findByText('Use a colour like #rrggbb')).toBeInTheDocument();
  });
});
