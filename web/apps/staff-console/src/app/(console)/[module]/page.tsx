import { type Metadata } from 'next';
import { notFound } from 'next/navigation';
import { Suspense } from 'react';

import { ModuleScreen } from '@/components/module/ModuleScreen';
import { Spinner } from '@/components/ui/States';
import { moduleOf } from '@/lib/modules';

const TITLES: Record<string, string> = {
  exports: 'Exports', orders: 'Orders', moderation: 'Listing moderation', inventory: 'Inventory', retail: 'Retail purchase orders', dispatch: 'Delivery dispatch',
  applications: 'Applications', support: 'Customer support', users: 'Users & roles', payouts: 'Payouts', fees: 'Fees, commissions & tax', marketing: 'Ads & announcements',
  disputes: 'Disputes & returns', audit: 'Audit log', markets: 'Markets', 'site-content': 'Site content & legal', developers: 'Developers'
};

export async function generateMetadata({ params }: { params: Promise<{ module: string }> }): Promise<Metadata> {
  const { module } = await params;
  return { title: TITLES[module] ?? 'Staff console' };
}

export default async function ModuleRoute({ params }: { params: Promise<{ module: string }> }) {
  const { module } = await params;
  const key = moduleOf(`/${module}`);
  if (!key || key === 'dash') notFound();
  return (
    <Suspense fallback={<Spinner />}>
      <ModuleScreen module={key} />
    </Suspense>
  );
}
