import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import { CardBrandMark, CardBrandRow, PoweredByStripe, StripeWordmark } from '../src';

describe('payment brand marks', () => {
  it('names every mark for assistive technology', () => {
    render(
      <>
        <PoweredByStripe />
        <StripeWordmark />
        <CardBrandMark brand="mastercard" />
        <CardBrandRow />
      </>
    );
    expect(screen.getByRole('img', { name: 'Powered by Stripe' })).toBeInTheDocument();
    expect(screen.getByRole('img', { name: 'Stripe' })).toBeInTheDocument();
    expect(screen.getAllByRole('img', { name: 'Mastercard' }).length).toBeGreaterThan(0);
    expect(screen.getByRole('img', { name: 'Accepted cards: Visa, Mastercard, American Express, Discover' })).toBeInTheDocument();
  });

  it('keeps the badge proportions (150 × 34) at any height', () => {
    const { container } = render(<PoweredByStripe height={34} />);
    const svg = container.querySelector('svg');
    expect(svg?.getAttribute('viewBox')).toBe('0 0 150 34');
    expect(Number(svg?.getAttribute('width'))).toBe(150);
  });
});
