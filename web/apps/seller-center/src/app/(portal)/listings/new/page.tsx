import { type Metadata } from 'next';

import { ProductForm } from '@/components/listings/ProductForm';

export const metadata: Metadata = { title: 'Add a product' };

/** One-step product form (S307); `?draft=<id>` continues an autosaved draft. */
export default function NewListingPage() {
  return <ProductForm />;
}
