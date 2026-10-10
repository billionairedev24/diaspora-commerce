import { buttonClassName, Logo } from '@afrotransact/ui';
import Link from 'next/link';

import { getT } from '@/i18n/server';

export default async function NotFound() {
  const t = await getT();
  return (
    <main id="main" tabIndex={-1} className="page" style={{ paddingTop: 64, display: 'flex', flexDirection: 'column', gap: 16, alignItems: 'flex-start' }}>
      <Logo href="/" />
      <h1 style={{ margin: 0, fontSize: 'var(--at-text-4xl)' }}>{t('notFound.title')}</h1>
      <p style={{ margin: 0, color: 'var(--at-color-text-muted)' }}>{t('notFound.body')}</p>
      <Link href="/" className={buttonClassName()}>
        {t('soon.home')}
      </Link>
    </main>
  );
}
