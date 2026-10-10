import { type Meta, type StoryObj } from '@storybook/react-vite';
import { useState } from 'react';

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

export const Default: Story = { render: () => <Interactive product={jollof} /> };
export const SellerFulfilled: Story = { args: { product: teff } };
export const InCartAtStock: Story = { args: { product: gele, quantityInCart: 3 } };
export const LowStock: Story = { render: () => <Interactive product={gele} /> };
export const Unavailable: Story = { args: { product: kente } };
export const Sponsored: Story = { args: { product: { ...jollof, sponsor: { advertiser: 'Kingston Kitchen' } } } };
export const Rail: Story = { args: { variant: 'rail' } };
export const Loading: Story = { render: () => <div style={{ display: 'grid', gap: 8 }}><Skeleton height={220} /><Skeleton width="90%" /><Skeleton width="60%" /><Skeleton height={32} radius="999px" /></div> };
export const RTL: Story = { ...rtl };
export const Mobile: Story = { ...mobile };
