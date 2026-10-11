'use client';

import { isApiError } from '@afrotransact/api-client';
import { useMutation } from '@tanstack/react-query';
import Link from 'next/link';

import { useT } from '@/i18n/t';
import { browserApi } from '@/lib/api/browser';

import styles from './Preorder.module.css';
import { PREORDER_PATH } from './PreorderBanners';

/**
 * /pre-orders/waitlist/unsubscribe?token= (link in pre-order waitlist emails): one press takes the email off the
 * waitlist (BFF → delivery-runs POST /v1/public/preorders/waitlist/unsubscribe). It asks first instead of leaving on
 * load, so mail scanners that open links do not unsubscribe anyone.
 */
export function WaitlistUnsubscribe({ token }: { token: string | null }) {
  const t = useT();
  const api = browserApi();
  const leave = useMutation({ mutationFn: (tk: string) => api.leavePreorderWaitlist(tk) });
  const invalid = !token || (leave.isError && isApiError(leave.error) && (leave.error.status === 422 || leave.error.status === 400 || leave.error.status === 404));
  const links = (
    <div className={styles.unsubscribeActions}>
      <Link href={PREORDER_PATH} className={styles.inkButton}>
        {t('preorder.unsubscribe.browse')}
      </Link>
      <Link href="/" className={styles.outlineButton}>
        {t('preorder.unsubscribe.home')}
      </Link>
    </div>
  );
  if (invalid)
    return (
      <section className={styles.unsubscribeCard} data-testid="waitlist-unsubscribe" data-state="invalid" aria-live="polite">
        <h1 className={styles.offTitle}>{t('preorder.unsubscribe.invalidTitle')}</h1>
        <p className={styles.offBody}>{t('preorder.unsubscribe.invalidBody')}</p>
        {links}
      </section>
    );
  if (leave.isSuccess)
    return (
      <section className={styles.unsubscribeCard} data-testid="waitlist-unsubscribe" data-state="done" aria-live="polite">
        <h1 className={styles.offTitle}>{t('preorder.unsubscribe.doneTitle')}</h1>
        <p className={styles.offBody}>{t('preorder.unsubscribe.doneBody')}</p>
        {links}
      </section>
    );
  return (
    <section className={styles.unsubscribeCard} data-testid="waitlist-unsubscribe" data-state="ask" aria-live="polite">
      <h1 className={styles.offTitle}>{t('preorder.unsubscribe.title')}</h1>
      <p className={styles.offBody}>{t('preorder.unsubscribe.body')}</p>
      {leave.isError ? (
        <p className={styles.unsubscribeError} role="alert">
          {t('preorder.unsubscribe.error')}
        </p>
      ) : null}
      <div className={styles.unsubscribeActions}>
        <button type="button" className={styles.inkButton} disabled={leave.isPending} onClick={() => token && leave.mutate(token)}>
          {leave.isPending ? t('preorder.unsubscribe.pending') : t('preorder.unsubscribe.confirm')}
        </button>
        <Link href={PREORDER_PATH} className={styles.outlineButton}>
          {t('preorder.unsubscribe.browse')}
        </Link>
      </div>
    </section>
  );
}
