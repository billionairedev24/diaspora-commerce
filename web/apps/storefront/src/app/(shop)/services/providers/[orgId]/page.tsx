import { type Metadata } from 'next';

import { ProviderPageView } from '@/components/services/ProviderPageView';

type Props = { params: Promise<{ orgId: string }> };

export const metadata: Metadata = { title: 'Provider' };

export default async function Page({ params }: Props) {
  const { orgId } = await params;
  return <ProviderPageView orgId={decodeURIComponent(orgId)} />;
}
