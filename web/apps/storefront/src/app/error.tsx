'use client';

import { Button } from '@afrotransact/ui';

import { t } from '@/i18n/t';

export default function ErrorPage({ reset }: { error: Error & { digest?: string }; reset: () => void }) {
  return (
    <main id="main" className="page" style={{ paddingTop: 64, display: 'flex', flexDirection: 'column', gap: 16, alignItems: 'flex-start' }} role="alert">
      <h1 style={{ margin: 0, fontSize: 'var(--at-text-3xl)' }}>{t('error.title')}</h1>
      <p style={{ margin: 0, color: 'var(--at-color-text-muted)' }}>{t('error.body')}</p>
      <Button onClick={reset}>{t('error.retry')}</Button>
    </main>
  );
}
