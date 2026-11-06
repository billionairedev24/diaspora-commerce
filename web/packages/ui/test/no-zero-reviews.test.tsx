import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import { ProductCard, type ProductCardData, Stars } from '../src';

const product: ProductCardData = {
  atin: 'AT00000P18', title: 'Auto Gele', community: 'Nigerian', unit: 'One size', rating: 0, reviewCount: 0, imageUrl: 'https://img.example/a.jpg', priceMinor: 3500, listPriceMinor: null,
  sellerName: 'Owambe Party Co.', sellerKind: 'SELLER', sellerCity: 'Houston, TX', fulfilment: 'AFROTRANSACT', availableQty: 3, inStock: true, lowStock: false,
  delivery: { prefix: 'FREE delivery', label: 'Tomorrow, Oct 11' }, offerCount: 1, minOfferPriceMinor: 3500, sponsor: null
};

describe('no reviews means no rating row', () => {
  it('Stars renders nothing for zero reviews and the row for real ones', () => {
    const { container, rerender } = render(<Stars rating={0} reviewCount={0} />);
    expect(container).toBeEmptyDOMElement();
    rerender(<Stars rating={4.5} reviewCount={12} />);
    expect(screen.getByRole('img', { name: '4.5 out of 5 stars' })).toBeInTheDocument();
    expect(container).toHaveTextContent('12');
    // The facet "4 & up" has no count at all and still shows its stars.
    rerender(<Stars rating={4} />);
    expect(screen.getByRole('img', { name: '4.0 out of 5 stars' })).toBeInTheDocument();
  });

  it.each(['grid', 'rail'] as const)('a %s card without reviews shows neither stars nor a 0', (variant) => {
    const { container } = render(<ProductCard product={product} href="/p/AT00000P18" variant={variant} />);
    expect(screen.queryByRole('img', { name: /out of 5 stars/ })).not.toBeInTheDocument();
    expect(container.textContent).not.toMatch(/(^|[^\d.,$])0(?![\d.,])\s*(reviews?|ratings?)?/i);
    expect(container).toHaveTextContent('$35.00');
  });

  it('a card with reviews keeps its row', () => {
    render(<ProductCard product={{ ...product, rating: 4.6, reviewCount: 330 }} href="/p/AT00000P18" />);
    expect(screen.getByRole('img', { name: '4.6 out of 5 stars' })).toBeInTheDocument();
  });
});
