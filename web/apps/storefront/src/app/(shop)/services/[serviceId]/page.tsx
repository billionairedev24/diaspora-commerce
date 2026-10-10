import { type Metadata } from 'next';

import { ServiceDetailView } from '@/components/services/ServiceDetailView';

type Props = { params: Promise<{ serviceId: string }> };

export const metadata: Metadata = { title: 'Book a service' };

export default async function Page({ params }: Props) {
  const { serviceId } = await params;
  return <ServiceDetailView serviceId={decodeURIComponent(serviceId)} />;
}
