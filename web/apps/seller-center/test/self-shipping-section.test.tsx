import { getSellerDb, resetSellerDb } from '@afrotransact/api-client/mocks/seller';
import { screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it } from 'vitest';

import { SelfShippingSection } from '@/components/settings/SelfShippingSection';

import { asRole } from './mock-api';
import { renderScreen } from './render';

describe('Your own shipping (staff switch, off by default)', () => {
  beforeEach(() => {
    resetSellerDb();
    asRole(null);
    getSellerDb().selfShipping.available = false;
  });

  it('shows no shipping-charge field at all while the switch is off', async () => {
    await renderScreen(<SelfShippingSection editable />);
    await new Promise((r) => setTimeout(r, 50));
    expect(screen.queryByTestId('self-shipping')).toBeNull();
    expect(screen.queryByLabelText(/Shipping charge/)).toBeNull();
  });

  it('with the switch on, says the charge is the seller’s own responsibility', async () => {
    getSellerDb().selfShipping.available = true;
    getSellerDb().selfShipping.enabled = true;
    await renderScreen(<SelfShippingSection editable />);
    await waitFor(() => expect(screen.getByLabelText(/Shipping charge/)).toBeInTheDocument());
    expect(screen.getByText(/your own charge and your responsibility/)).toBeInTheDocument();
  });
});
