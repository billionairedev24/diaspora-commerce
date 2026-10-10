import Link from 'next/link';

import { getT } from '@/i18n/server';

export default async function NotFound() {
  const t = await getT();
  return (
    <main id="main" tabIndex={-1} className="mx-auto flex w-[min(560px,100%)] flex-col items-center gap-3 px-4 py-20 text-center">
      <h1 className="m-0 text-2xl font-bold">{t('sc.notFound.pageNotFound')}</h1>
      <p className="m-0 text-muted">{t('sc.notFound.thatPageIsnTPart')}</p>
      <Link href="/">{t('sc.notFound.backToYourDashboard')}</Link>
    </main>
  );
}
