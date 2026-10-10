import { type Metadata } from 'next';

import { ServiceDetailView } from '@/components/services/ServiceDetailView';
import { getT } from '@/i18n/server';

type Props = { params: Promise<{ serviceId: string }> };

export async function generateMetadata(): Promise<Metadata> {
  const t = await getT();
  return { title: t('title.serviceDetail') };
}

export default async function Page({ params }: Props) {
  const { serviceId } = await params;
  return <ServiceDetailView serviceId={decodeURIComponent(serviceId)} />;
}
