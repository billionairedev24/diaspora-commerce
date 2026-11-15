import { type Metadata } from 'next';

import { StorePageView } from '@/components/store-page/StorePageView';

export const metadata: Metadata = { title: 'Store page' };

export default function Page() {
  return <StorePageView />;
}
