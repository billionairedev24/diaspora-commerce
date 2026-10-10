import { type Metadata } from 'next';

import { ComingSoon } from '@/components/common/ComingSoon';
import { getT } from '@/i18n/server';

export async function generateMetadata(): Promise<Metadata> {
  const t = await getT();
  return { title: t('title.legal'), robots: { index: false } };
}

export default function Page() {
  return <ComingSoon page="soon.legal" />;
}
