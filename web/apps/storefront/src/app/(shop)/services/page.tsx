import { type Metadata } from 'next';

import { ServicesView } from '@/components/services/ServicesView';
import { getT } from '@/i18n/server';

export async function generateMetadata(): Promise<Metadata> {
  const t = await getT();
  return { title: t('title.services'), description: t('title.servicesDescription') };
}

export default function Page() {
  return <ServicesView />;
}
