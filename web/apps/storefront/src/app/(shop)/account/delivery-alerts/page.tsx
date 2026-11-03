import { type Metadata } from 'next';

import { DeliveryAlertsView } from '@/components/runs/DeliveryAlerts';
import { getT } from '@/i18n/server';
import { requireCustomer } from '@/lib/auth/session';

export async function generateMetadata(): Promise<Metadata> {
  const t = await getT();
  return { title: t('runs.alerts.title'), robots: { index: false } };
}

export default async function Page() {
  await requireCustomer('/account/delivery-alerts');
  return <DeliveryAlertsView />;
}
