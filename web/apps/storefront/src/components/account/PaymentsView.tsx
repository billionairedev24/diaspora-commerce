'use client';

import { accountKeys, isApiError, type PaymentMethod } from '@afrotransact/api-client';
import { Button, Plus, Skeleton, useToast } from '@afrotransact/ui';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';

import { AddPaymentMethodDialog } from '@/components/payments/AddPaymentMethodDialog';
import { PaymentChip } from '@/components/payments/PaymentChip';
import { t } from '@/i18n/t';
import { browserApi } from '@/lib/api/browser';
import { shopperQueries } from '@/lib/api/queries';

import { AccountCrumb } from './AccountCrumb';
import styles from './Pages.module.css';

/** Your Payments (S148): add card or US bank account (Stripe), remove, set default. */
export function PaymentsView() {
  const api = browserApi();
  const qc = useQueryClient();
  const toast = useToast();
  const list = useQuery(shopperQueries.paymentMethods(api));
  const [adding, setAdding] = useState(false);
  const refresh = () => Promise.all([qc.invalidateQueries({ queryKey: accountKeys.paymentMethods() }), qc.invalidateQueries({ queryKey: accountKeys.me() })]);
  const fail = (e: unknown) => toast.show(isApiError(e) ? e.message : t('auth.genericError'));
  const remove = useMutation({ mutationFn: (pm: PaymentMethod) => api.deletePaymentMethod(pm.id), onSuccess: async () => { await refresh(); toast.show(t('pay.removed')); }, onError: fail });
  const makeDefault = useMutation({ mutationFn: (pm: PaymentMethod) => api.setDefaultPaymentMethod(pm.id), onSuccess: async () => { await refresh(); toast.show(t('pay.defaultSet')); }, onError: fail });

  return (
    <div className={styles.page}>
      <AccountCrumb>{t('account.payments')}</AccountCrumb>
      <div className={styles.head}>
        <h1 className={styles.h1}>{t('pay.title')}</h1>
        <Button icon={<Plus aria-hidden />} onClick={() => setAdding(true)}>
          {t('pay.add')}
        </Button>
      </div>
      <section className={styles.card} aria-label={t('pay.saved')}>
        {list.isPending && <Skeleton height={48} />}
        {list.isError && <p className={styles.error} role="alert">{t('acct.loadError')}</p>}
        {list.data?.length === 0 && <p className={styles.muted}>{t('pay.none')}</p>}
        <ul className={styles.list}>
          {list.data?.map((pm) => (
            <li key={pm.id} className={styles.row} data-testid="payment-method">
              <PaymentChip method={pm} />
              <span>
                <strong>{pm.label}</strong>
                <br />
                <span className={styles.muted}>{pm.expires ? t('pay.expires', { date: pm.expires }) : pm.type === 'us_bank_account' ? t('pay.bankAccount') : t('pay.wallet')}</span>
              </span>
              <span className={styles.actions}>
                {pm.isDefault ? (
                  <span className={styles.status}>{t('pay.default')}</span>
                ) : (
                  <button type="button" className="link-button" onClick={() => makeDefault.mutate(pm)} disabled={makeDefault.isPending}>
                    {t('pay.setDefault')}
                  </button>
                )}
                <button type="button" className="link-button" onClick={() => remove.mutate(pm)} disabled={remove.isPending} aria-label={t('pay.removeAria', { label: pm.label })}>
                  {t('common.remove')}
                </button>
              </span>
            </li>
          ))}
        </ul>
        <p className={styles.muted}>{t('pay.stripeNote')}</p>
      </section>
      <AddPaymentMethodDialog open={adding} onOpenChange={setAdding} onSaved={(pm) => toast.show(pm ? t('pay.added', { label: pm.label }) : t('pay.addedPending'))} />
    </div>
  );
}
