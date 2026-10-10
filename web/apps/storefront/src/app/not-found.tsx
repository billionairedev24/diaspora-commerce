import { buttonClassName, Logo } from '@afrotransact/ui';
import Link from 'next/link';

import { t } from '@/i18n/t';

export default function NotFound() {
  return (
    <main id="main" className="page" style={{ paddingTop: 64, display: 'flex', flexDirection: 'column', gap: 16, alignItems: 'flex-start' }}>
      <Logo href="/" />
      <h1 style={{ margin: 0, fontSize: 'var(--at-text-4xl)' }}>{t('notFound.title')}</h1>
      <p style={{ margin: 0, color: 'var(--at-color-text-muted)' }}>{t('notFound.body')}</p>
      <Link href="/" className={buttonClassName()}>
        {t('soon.home')}
      </Link>
    </main>
  );
}
