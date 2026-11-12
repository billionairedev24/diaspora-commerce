import { type Metadata } from 'next';

import { AddProductFlow } from '@/components/listings/add/AddProductFlow';

export const metadata: Metadata = { title: 'Add a product' };

/** Add a product starts with the barcode (S382); the one-step form (S307) follows when no page exists. `?draft=<id>` continues an autosaved draft. */
export default function NewListingPage() {
  return <AddProductFlow />;
}
