import { type Metadata } from 'next';

import { PublicPageView } from '@/components/provider/PublicPageView';

export const metadata: Metadata = { title: 'Public page' };

export default function PublicPagePage() {
  return <PublicPageView />;
}
