import { type Metadata } from 'next';

import { TourHubView } from '@/components/tours/TourHubView';

export const metadata: Metadata = { title: 'Tour hub' };

export default function TourHubPage() {
  return <TourHubView />;
}
