import { type Metadata } from 'next';

import { RunPickupsView } from '@/components/orders/RunPickups';

export const metadata: Metadata = { title: 'Delivery run pick lists' };

/** The delivery runs that collect from this seller: pickup time and what to pack (S264). */
export default function RunPickupsPage() {
  return <RunPickupsView />;
}
