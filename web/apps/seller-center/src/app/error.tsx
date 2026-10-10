'use client';

import { useReportError } from '@afrotransact/telemetry/react';

import { useT } from '@/i18n/t';

export default function RouteError({ error, reset }: { error: Error & { digest?: string }; reset: () => void }) {
  const t = useT();
  useReportError(error);
  return (
    <main id="main" tabIndex={-1} className="mx-auto flex w-[min(560px,100%)] flex-col items-center gap-3 px-4 py-20 text-center">
      <h1 className="m-0 text-2xl font-bold">{t('sc.error.somethingWentWrong')}</h1>
      <p className="m-0 text-muted">{t('sc.error.pleaseTryAgain')}</p>
      <button type="button" onClick={reset} className="rounded-pill bg-primary px-5 py-2 font-bold text-on-primary">
        {t('sc.error.tryAgain')}
      </button>
    </main>
  );
}
