'use client';

import { Sheet, Sparkle } from '@afrotransact/ui';

import { t } from '@/i18n/t';

import styles from './LolaSheet.module.css';

/** Lola drawer shell (the assistant itself ships in a later phase). */
export function LolaSheet({ open, onOpenChange }: { open: boolean; onOpenChange: (o: boolean) => void }) {
  return (
    <Sheet
      open={open}
      onOpenChange={onOpenChange}
      title={t('lola.title')}
      description={t('lola.subtitle')}
      header={
        <>
          <span className={styles.avatar} aria-hidden>
            <Sparkle weight="fill" />
          </span>
          <span className={styles.text}>
            <strong>{t('lola.title')}</strong>
            <span className={styles.sub}>{t('lola.subtitle')}</span>
          </span>
        </>
      }
    >
      <p className={styles.bubble}>{t('lola.soon')}</p>
    </Sheet>
  );
}
