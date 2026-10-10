import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { type CountryCode } from 'libphonenumber-js/mobile';
import { useState } from 'react';
import { describe, expect, it, vi } from 'vitest';

import { AddressAutocomplete, createFixtureAddressSearch, fromNominatim, PasswordField, PhoneInput, ProductCard, type ProductCardData, QuantityStepper, SAMPLE_ADDRESSES, SocialButton, Stars } from '../src';

function Phone() {
  const [country, setCountry] = useState<CountryCode>('US');
  const [value, setValue] = useState('');
  return <PhoneInput label="Mobile phone" country={country} onCountryChange={setCountry} value={value} onChange={setValue} />;
}

describe('PhoneInput', () => {
  it('formats as you type and offers every country with flag and dial code', async () => {
    render(<Phone />);
    const input = screen.getByLabelText('Mobile phone');
    await userEvent.type(input, '7132490142');
    expect(input).toHaveValue('(713) 249-0142');
    const select = screen.getByLabelText('Country code');
    expect(select.querySelectorAll('option').length).toBeGreaterThan(200);
    expect(select.querySelector('option')?.textContent).toMatch(/United States \(\+1\)/);
  });

  it('deletes digits through formatting characters', async () => {
    render(<Phone />);
    const input = screen.getByLabelText('Mobile phone');
    await userEvent.type(input, '713');
    expect(input).toHaveValue('(713)');
    await userEvent.type(input, '{backspace}');
    expect(input).toHaveValue('71');
  });

  it('reformats for a newly chosen country', async () => {
    render(<Phone />);
    await userEvent.selectOptions(screen.getByLabelText('Country code'), 'NG');
    await userEvent.type(screen.getByLabelText('Mobile phone'), '08031234567');
    expect(screen.getByLabelText('Mobile phone')).toHaveValue('0803 123 4567');
  });
});

describe('PasswordField', () => {
  it('toggles visibility and shows the live checklist', async () => {
    render(<PasswordField label="Password" defaultValue="abc" rules={[{ id: 'length', label: '12+ characters', ok: false }, { id: 'number', label: 'At least one number', ok: true }]} />);
    const input = screen.getByLabelText('Password');
    expect(input).toHaveAttribute('type', 'password');
    await userEvent.click(screen.getByRole('button', { name: 'Show password' }));
    expect(input).toHaveAttribute('type', 'text');
    expect(screen.getByRole('button', { name: 'Hide password' })).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByRole('list', { name: 'Password requirements' })).toHaveTextContent('12+ characters — not met');
    expect(screen.getByRole('list', { name: 'Password requirements' })).toHaveTextContent('At least one number — met');
  });
});

describe('QuantityStepper', () => {
  it('stops at the stock limit and turns minus into remove at 1', async () => {
    const onChange = vi.fn();
    const onRemove = vi.fn();
    const { rerender } = render(<QuantityStepper value={3} max={3} onChange={onChange} onRemove={onRemove} itemLabel="Gele" format={(n) => `${n} in cart`} />);
    expect(screen.getByRole('button', { name: 'Increase quantity of Gele' })).toBeDisabled();
    expect(screen.getByText('3 in cart')).toBeInTheDocument();
    rerender(<QuantityStepper value={1} max={3} onChange={onChange} onRemove={onRemove} itemLabel="Gele" />);
    await userEvent.click(screen.getByRole('button', { name: 'Remove from cart: Gele' }));
    expect(onRemove).toHaveBeenCalled();
    await userEvent.click(screen.getByRole('button', { name: 'Increase quantity of Gele' }));
    expect(onChange).toHaveBeenCalledWith(2);
  });
});

describe('Stars', () => {
  it('announces the rating', () => {
    render(<Stars rating={4.8} reviewCount={1284} />);
    expect(screen.getByRole('img', { name: '4.8 out of 5 stars' })).toBeInTheDocument();
    expect(screen.getByText('1,284')).toBeInTheDocument();
  });
});

const product: ProductCardData = {
  atin: 'AT00000P18', title: 'Auto Gele', community: 'Nigerian', unit: 'One size', rating: 4.6, reviewCount: 330, imageUrl: null, priceMinor: 3500, listPriceMinor: null,
  sellerName: 'Owambe Party Co.', sellerKind: 'SELLER', sellerCity: 'Houston, TX', fulfilment: 'AFROTRANSACT', availableQty: 3, inStock: true, lowStock: true,
  delivery: { prefix: 'FREE delivery', label: 'Tomorrow, Oct 11' }, offerCount: 1, minOfferPriceMinor: 3500, sponsor: null
};

describe('ProductCard', () => {
  it('shows Add to cart, then a stepper once in the cart', async () => {
    const onAdd = vi.fn();
    const { rerender } = render(<ProductCard product={product} href="/p/AT00000P18" onAdd={onAdd} />);
    expect(screen.getByText('Fulfilled by AfroTransact')).toBeInTheDocument();
    expect(screen.getByText('Only 3 left — order soon')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Add to cart: Auto Gele' }));
    expect(onAdd).toHaveBeenCalled();
    rerender(<ProductCard product={product} href="/p/AT00000P18" quantityInCart={1} />);
    expect(screen.getByText('1 in cart')).toBeInTheDocument();
  });

  it('shows "Currently unavailable" at zero stock', () => {
    render(<ProductCard product={{ ...product, availableQty: 0, inStock: false, lowStock: false, fulfilment: 'SELLER' }} href="/p" />);
    expect(screen.getByText('Currently unavailable')).toBeInTheDocument();
    expect(screen.getByText('Ships from Houston, TX')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Add to cart/ })).not.toBeInTheDocument();
  });
});

describe('SocialButton', () => {
  it('is a navigation to the OIDC start URL with the provider logo', () => {
    render(<SocialButton provider="google" href="/api/bff/v1/auth/oidc/google/authorize?returnTo=%2F" />);
    const link = screen.getByRole('link', { name: 'Continue with Google' });
    expect(link).toHaveAttribute('href', '/api/bff/v1/auth/oidc/google/authorize?returnTo=%2F');
    expect(link.querySelectorAll('path')).toHaveLength(4);
  });
});

describe('AddressAutocomplete', () => {
  function Demo({ onSelect }: { onSelect: (v: string) => void }) {
    const [value, setValue] = useState('');
    return <AddressAutocomplete label="Street address" value={value} onChange={setValue} onSelect={(s) => { setValue(s.label); onSelect(`${s.label}, ${s.secondary}`); }} searchAddresses={createFixtureAddressSearch(SAMPLE_ADDRESSES)} debounceMs={0} />;
  }

  it('suggests addresses and picks one with the keyboard', async () => {
    const onSelect = vi.fn();
    render(<Demo onSelect={onSelect} />);
    const input = screen.getByRole('combobox', { name: 'Street address' });
    await userEvent.type(input, 'west');
    await waitFor(() => expect(screen.getAllByRole('option').length).toBeGreaterThan(0));
    await userEvent.keyboard('{ArrowDown}{Enter}');
    expect(onSelect).toHaveBeenCalledWith('9800 Westheimer Rd, Houston, TX 77042');
    expect(input).toHaveValue('9800 Westheimer Rd');
  });

  it('maps Nominatim results', () => {
    const s = fromNominatim({ place_id: 1, lat: '29.7', lon: '-95.5', display_name: '9800, Westheimer Road, Houston', address: { house_number: '9800', road: 'Westheimer Road', city: 'Houston', state: 'Texas', postcode: '77042', country_code: 'us' } });
    expect(s).toMatchObject({ label: '9800 Westheimer Road', secondary: 'Houston, TX 77042', source: 'osm', address: { state: 'TX', zip: '77042', country: 'US' } });
  });
});
