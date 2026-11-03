import { type Metadata } from 'next';

import { ReturnsView } from '@/components/cases/ReturnsView';

export const metadata: Metadata = { title: 'Returns' };

export default function ReturnsPage() {
  return <ReturnsView />;
}
