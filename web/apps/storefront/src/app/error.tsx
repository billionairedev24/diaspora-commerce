'use client';

import { useReportError } from '@afrotransact/telemetry/react';
import { Button } from '@afrotransact/ui';

import { useT } from '@/i18n/t';

export default function ErrorPage({ error, reset }: { error: Error & { digest?: string }; reset: () => void }) {
  const t = useT();
  useReportError(error);
  return (
    <main id="main" tabIndex={-1} className="page" style={{ paddingTop: 64, display: 'flex', flexDirection: 'column', gap: 16, alignItems: 'flex-start' }} role="alert">
      <h1 style={{ margin: 0, fontSize: 'var(--at-text-3xl)' }}>{t('error.title')}</h1>
      <p style={{ margin: 0, color: 'var(--at-color-text-muted)' }}>{t('error.body')}</p>
      <Button onClick={reset}>{t('error.retry')}</Button>
    </main>
  );
}
