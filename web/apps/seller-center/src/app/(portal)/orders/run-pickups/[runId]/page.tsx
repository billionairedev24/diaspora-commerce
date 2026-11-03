import { type Metadata } from 'next';

import { RunPickupDetailView } from '@/components/orders/RunPickups';

export const metadata: Metadata = { title: 'Pick list' };

/** One run's pick list: the total per product and what goes in each order's bag (S264). */
export default async function RunPickupPage({ params }: { params: Promise<{ runId: string }> }) {
  const { runId } = await params;
  return <RunPickupDetailView key={runId} runId={runId} />;
}
