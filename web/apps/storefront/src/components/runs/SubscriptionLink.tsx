'use client';

import { isApiError } from '@afrotransact/api-client';
import { useMutation } from '@tanstack/react-query';
import Link from 'next/link';

import preorderStyles from '@/components/preorder/Preorder.module.css';
import { useT } from '@/i18n/t';
import { browserApi } from '@/lib/api/browser';

/**
 * /delivery-runs/subscriptions/confirm?token= (the link in the confirmation email: double opt-in) and
 * /delivery-runs/subscriptions/unsubscribe?token= (the link in every run alert). Both ask first with one button instead
 * of acting on load, so mail scanners that open links neither confirm nor unsubscribe anyone. Unsubscribing withdraws
 * every channel of the location (the consent records keep the time).
 */
export function SubscriptionLink({ mode, token }: { mode: 'confirm' | 'unsubscribe'; token: string | null }) {
  const t = useT();
  const api = browserApi();
  const act = useMutation({ mutationFn: (tk: string) => (mode === 'confirm' ? api.confirmRunAlerts(tk) : api.unsubscribeRunAlerts(tk)) });
  const invalid = !token || (act.isError && isApiError(act.error) && [400, 404, 422].includes(act.error.status));
  const links = (
    <div className={preorderStyles.unsubscribeActions}>
      <Link href="/delivery-runs" className={preorderStyles.inkButton}>
        {t('runs.link.browse')}
      </Link>
      <Link href="/account/delivery-alerts" className={preorderStyles.outlineButton}>
        {t('runs.link.manage')}
      </Link>
    </div>
  );
  const prefix = mode === 'confirm' ? 'runs.confirm' : 'runs.unsubscribe';
  if (invalid)
    return (
      <section className={preorderStyles.unsubscribeCard} data-testid={`subscription-${mode}`} data-state="invalid" aria-live="polite">
        <h1 className={preorderStyles.offTitle}>{t(`${prefix}.invalidTitle` as 'runs.confirm.invalidTitle')}</h1>
        <p className={preorderStyles.offBody}>{t(`${prefix}.invalidBody` as 'runs.confirm.invalidBody')}</p>
        {links}
      </section>
    );
  if (act.isSuccess)
    return (
      <section className={preorderStyles.unsubscribeCard} data-testid={`subscription-${mode}`} data-state="done" aria-live="polite">
        <h1 className={preorderStyles.offTitle}>{t(`${prefix}.doneTitle` as 'runs.confirm.doneTitle')}</h1>
        <p className={preorderStyles.offBody}>{t(`${prefix}.doneBody` as 'runs.confirm.doneBody')}</p>
        {links}
      </section>
    );
  return (
    <section className={preorderStyles.unsubscribeCard} data-testid={`subscription-${mode}`} data-state="ask" aria-live="polite">
      <h1 className={preorderStyles.offTitle}>{t(`${prefix}.title` as 'runs.confirm.title')}</h1>
      <p className={preorderStyles.offBody}>{t(`${prefix}.body` as 'runs.confirm.body')}</p>
      {act.isError ? (
        <p className={preorderStyles.unsubscribeError} role="alert">
          {t('runs.link.error')}
        </p>
      ) : null}
      <div className={preorderStyles.unsubscribeActions}>
        <button type="button" className={preorderStyles.inkButton} disabled={act.isPending} onClick={() => token && act.mutate(token)}>
          {act.isPending ? t('runs.link.pending') : t(`${prefix}.button` as 'runs.confirm.button')}
        </button>
        <Link href="/delivery-runs" className={preorderStyles.outlineButton}>
          {t('runs.link.browse')}
        </Link>
      </div>
    </section>
  );
}
