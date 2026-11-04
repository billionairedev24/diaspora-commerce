import { type Metadata } from 'next';

import { BusinessPageView } from '@/components/business/BusinessPageView';

export const metadata: Metadata = { title: 'Business page' };

export default function BusinessPagePage() {
  return <BusinessPageView />;
}
