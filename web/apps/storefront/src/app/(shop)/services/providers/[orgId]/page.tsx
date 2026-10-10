import { type Metadata } from 'next';

import { ProviderPageView } from '@/components/services/ProviderPageView';
import { getT } from '@/i18n/server';

type Props = { params: Promise<{ orgId: string }> };

export async function generateMetadata(): Promise<Metadata> {
  const t = await getT();
  return { title: t('title.servicesProviders') };
}

export default async function Page({ params }: Props) {
  const { orgId } = await params;
  return <ProviderPageView orgId={decodeURIComponent(orgId)} />;
}
