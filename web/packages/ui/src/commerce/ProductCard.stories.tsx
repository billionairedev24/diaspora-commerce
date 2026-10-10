import { type Meta, type StoryObj } from '@storybook/react-vite';
import { useState } from 'react';
import { expect } from 'storybook/test';

import { mobile, rtl } from '../stories.helpers';
import { Skeleton } from './ImagePlaceholder';
import { ProductCard, type ProductCardData } from './ProductCard';

const jollof: ProductCardData = {
  atin: 'AT000000P2', title: 'AfroTransact Brand Jollof Seasoning, 6 oz', community: 'West African', unit: '6 oz', rating: 4.7, reviewCount: 3410, imageUrl: null,
  priceMinor: 649, listPriceMinor: 799, sellerName: 'AfroTransact', sellerKind: 'PLATFORM', sellerCity: 'Houston, TX', fulfilment: 'AFROTRANSACT', availableQty: 900, inStock: true,
  lowStock: false, delivery: { prefix: 'FREE delivery', label: 'Tomorrow, Oct 11' }, offerCount: 1, minOfferPriceMinor: 649, sponsor: null
};
const teff: ProductCardData = { ...jollof, atin: 'AT000000P5', title: 'Ivory Teff Flour, Stone-Milled, 4 lb', community: 'Ethiopian', unit: '4 lb', priceMinor: 1400, listPriceMinor: null, sellerName: 'Addis Spice House', sellerKind: 'SELLER', sellerCity: 'Silver Spring, MD', fulfilment: 'SELLER', delivery: { prefix: 'Delivery', label: 'Wednesday, Oct 14' } };
const gele: ProductCardData = { ...jollof, atin: 'AT00000P18', title: 'Ready-to-Wear Auto Gele Headwrap', sellerKind: 'SELLER', sellerName: 'Owambe Party Co.', availableQty: 3, lowStock: true, priceMinor: 3500, listPriceMinor: null };
const kente: ProductCardData = { ...teff, atin: 'AT00000P14', title: 'Hand-Woven Kente Stole, 72 in', availableQty: 0, inStock: false, priceMinor: 6400 };

const meta: Meta<typeof ProductCard> = { title: 'Commerce/ProductCard', component: ProductCard, args: { product: jollof, href: '#product' }, decorators: [(S) => <div style={{ width: 260 }}><S /></div>] };
export default meta;
type Story = StoryObj<typeof ProductCard>;

function Interactive({ product }: { product: ProductCardData }) {
  const [q, setQ] = useState(0);
  return <ProductCard product={product} href="#p" quantityInCart={q} onAdd={() => setQ(1)} onQuantityChange={setQ} onRemove={() => setQ(0)} />;
}

export const Default: Story = {
  render: () => <Interactive product={jollof} />,
  play: async ({ canvas, userEvent, step }) => {
    await step('Add to cart turns into a quantity stepper', async () => {
      await userEvent.click(canvas.getByRole('button', { name: `Add to cart: ${jollof.title}` }));
      await expect(canvas.getByRole('group', { name: `Quantity of ${jollof.title}` })).toHaveTextContent('1 in cart');
    });
    await step('+ increases, bin at 1 removes it again', async () => {
      await userEvent.click(canvas.getByRole('button', { name: `Increase quantity of ${jollof.title}` }));
      await expect(canvas.getByRole('group', { name: `Quantity of ${jollof.title}` })).toHaveTextContent('2 in cart');
      await userEvent.click(canvas.getByRole('button', { name: `Decrease quantity of ${jollof.title}` }));
      await userEvent.click(canvas.getByRole('button', { name: `Remove from cart: ${jollof.title}` }));
      await expect(canvas.getByRole('button', { name: `Add to cart: ${jollof.title}` })).toBeVisible();
    });
  }
};
export const SellerFulfilled: Story = { args: { product: teff } };
export const InCartAtStock: Story = { args: { product: gele, quantityInCart: 3 } };
export const LowStock: Story = { render: () => <Interactive product={gele} /> };
export const Unavailable: Story = {
  args: { product: kente },
  play: async ({ canvas }) => {
    await expect(canvas.getByText('Currently unavailable')).toBeVisible();
    await expect(canvas.queryByRole('button', { name: /Add to cart/ })).toBeNull();
  }
};
export const Sponsored: Story = { args: { product: { ...jollof, sponsor: { advertiser: 'Kingston Kitchen' } } } };
export const Rail: Story = { args: { variant: 'rail' } };
/** Pre-order open in the shopper's city (S273): badge on the image, delivery day instead of the promise. */
export const PreOrder: Story = {
  args: { product: jollof, availability: { badge: 'Pre-order', tag: 'Pre-order · delivered Oct 25', tone: 'gold' } },
  play: async ({ canvas }) => {
    await expect(canvas.getByTestId('preorder-badge')).toHaveTextContent('Pre-order');
    await expect(canvas.getByText('Pre-order · delivered Oct 25')).toBeVisible();
    await expect(canvas.getByRole('button', { name: `Add to cart: ${jollof.title}` })).toBeEnabled();
  }
};
/** Window not open yet: adding is blocked with the opening date. */
export const PreOrderLocked: Story = {
  args: { product: jollof, availability: { badge: 'Pre-order', tag: 'Pre-order opens Oct 15', tone: 'muted', lockedLabel: 'Opens Oct 15' } },
  play: async ({ canvas }) => {
    await expect(canvas.getByRole('button', { name: `Opens Oct 15: ${jollof.title}` })).toBeDisabled();
    await expect(canvas.queryByRole('button', { name: /Add to cart/ })).toBeNull();
  }
};
export const Loading: Story = { render: () => <div style={{ display: 'grid', gap: 8 }}><Skeleton height={220} /><Skeleton width="90%" /><Skeleton width="60%" /><Skeleton height={32} radius="999px" /></div> };
export const RTL: Story = { ...rtl };
export const Mobile: Story = { ...mobile };
