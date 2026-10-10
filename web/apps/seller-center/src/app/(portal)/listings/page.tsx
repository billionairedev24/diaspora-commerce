import { type Metadata } from 'next';

import { ListingsView } from '@/components/listings/ListingsView';

export const metadata: Metadata = { title: 'Listings' };

export default function ListingsPage() {
  return <ListingsView />;
}
