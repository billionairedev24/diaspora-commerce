import { type Metadata } from 'next';

import { ComingSoon } from '@/components/common/ComingSoon';

export const metadata: Metadata = { title: 'Your orders', robots: { index: false } };

export default function Page() {
  return <ComingSoon page="soon.orders" />;
}
