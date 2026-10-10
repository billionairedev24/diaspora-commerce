import { type Metadata } from 'next';

import { ProductBuilder } from '@/components/listings/ProductBuilder';

export const metadata: Metadata = { title: 'Add a product' };

export default function NewListingPage() {
  return <ProductBuilder />;
}
