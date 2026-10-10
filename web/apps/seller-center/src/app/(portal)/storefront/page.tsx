import { type Metadata } from 'next';

import { PublicPageView } from '@/components/provider/PublicPageView';

export const metadata: Metadata = { title: 'Storefront & theme' };

export default function StorefrontPage() {
  return <PublicPageView />;
}
