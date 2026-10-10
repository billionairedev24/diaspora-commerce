import { type Metadata } from 'next';

import { DashboardView } from '@/components/dashboard/DashboardView';

export const metadata: Metadata = { title: 'Home' };

export default function HomePage() {
  return <DashboardView />;
}
