'use client';

import { useReportError } from '@afrotransact/telemetry/react';
import { Button } from '@afrotransact/ui';
import Link from 'next/link';

import styles from '@/components/legal/Legal.module.css';
import { useT } from '@/i18n/t';

/** A legal page whose document could not be loaded (content unavailable): retry, or go to the other documents. */
export default function LegalError({ error, reset }: { error: Error & { digest?: string }; reset: () => void }) {
  const t = useT();
  useReportError(error);
  return (
    <div role="alert" className={styles.problem}>
      <h1 className={styles.title}>{t('legal.errorTitle')}</h1>
      <p className={styles.lede}>{t('legal.errorBody')}</p>
      <div className={styles.buttons}>
        <Button onClick={reset}>{t('error.retry')}</Button>
        <Link href="/legal" className={styles.btn}>
          {t('legal.allDocs')}
        </Link>
      </div>
    </div>
  );
}
