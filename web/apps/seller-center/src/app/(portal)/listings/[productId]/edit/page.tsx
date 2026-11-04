import { type Metadata } from 'next';

import { ProductForm } from '@/components/listings/ProductForm';

export const metadata: Metadata = { title: 'Edit a product' };

/** The one-step product form on an existing listing (S307). */
export default async function EditListingPage({ params }: { params: Promise<{ productId: string }> }) {
  const { productId } = await params;
  return <ProductForm key={productId} productId={productId} />;
}
