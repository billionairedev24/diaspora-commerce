'use client';

import { accountKeys, isApiError, type PaymentMethod } from '@afrotransact/api-client';
import { Button, cardExpired, EmptyState, FlatPage, Message, PaymentMethodMark, Plus, Row, RowList, Section, Skeleton, StatusText, TextLinkButton, useToast } from '@afrotransact/ui';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';

import { AddPaymentMethodDialog } from '@/components/payments/AddPaymentMethodDialog';
import { useT } from '@/i18n/t';
import { browserApi } from '@/lib/api/browser';
import { shopperQueries } from '@/lib/api/queries';

import { AccountHeader } from './AccountHeader';

/** Your Payments (S148): add card or US bank account (Stripe), remove, set default. */
export function PaymentsView() {
  const t = useT();
  const api = browserApi();
  const qc = useQueryClient();
  const toast = useToast();
  const list = useQuery(shopperQueries.paymentMethods(api));
  const [adding, setAdding] = useState(false);
  const refresh = () => Promise.all([qc.invalidateQueries({ queryKey: accountKeys.paymentMethods() }), qc.invalidateQueries({ queryKey: accountKeys.me() })]);
  const fail = (e: unknown) => toast.show(isApiError(e) ? e.message : t('auth.genericError'));
  const remove = useMutation({ mutationFn: (pm: PaymentMethod) => api.deletePaymentMethod(pm.id), onSuccess: async () => { await refresh(); toast.show(t('pay.removed')); }, onError: fail });
  const makeDefault = useMutation({ mutationFn: (pm: PaymentMethod) => api.setDefaultPaymentMethod(pm.id), onSuccess: async () => { await refresh(); toast.show(t('pay.defaultSet')); }, onError: fail });

  const detailOf = (pm: PaymentMethod) => {
    const bits: string[] = [];
    if (pm.type === 'us_bank_account' && /verification pending/i.test(pm.label)) bits.push(t('pay.verificationPending'));
    if (pm.expires) bits.push(cardExpired(pm.expires) ? t('pay.expired') : t('pay.expires', { date: pm.expires }));
    return bits.join(' · ');
  };

  return (
    <FlatPage>
      <AccountHeader
        title={t('pay.title')}
        crumb={t('account.payments')}
        actions={
          <Button icon={<Plus aria-hidden />} onClick={() => setAdding(true)}>
            {t('pay.add')}
          </Button>
        }
      />
      <Section title={t('pay.saved')} id="pay-saved">
        {list.isPending && <Skeleton height={48} />}
        {list.isError && (
          <Message tone="error">
            {t('acct.loadError')} <TextLinkButton onClick={() => void list.refetch()}>{t('acct.retry')}</TextLinkButton>
          </Message>
        )}
        {list.data?.length === 0 && <EmptyState>{t('pay.none')}</EmptyState>}
        {(list.data?.length ?? 0) > 0 && (
          <RowList>
            {list.data?.map((pm) => (
              <Row
                key={pm.id}
                testId="payment-method"
                title={
                  <>
                    <PaymentMethodMark type={pm.type} brand={pm.brand} height={22} />
                    <strong>{pm.label}</strong>
                    {pm.isDefault ? <StatusText tone="positive">{t('pay.default')}</StatusText> : null}
                  </>
                }
                detail={detailOf(pm) || undefined}
                actions={
                  <>
                    {pm.isDefault ? null : (
                      <TextLinkButton onClick={() => makeDefault.mutate(pm)} disabled={makeDefault.isPending}>
                        {t('pay.setDefault')}
                      </TextLinkButton>
                    )}
                    <TextLinkButton danger onClick={() => remove.mutate(pm)} disabled={remove.isPending} aria-label={t('pay.removeAria', { label: pm.label })}>
                      {t('common.remove')}
                    </TextLinkButton>
                  </>
                }
              />
            ))}
          </RowList>
        )}
        <Message tone="muted">{t('pay.stripeNote')}</Message>
      </Section>
      <AddPaymentMethodDialog open={adding} onOpenChange={setAdding} onSaved={(pm) => toast.show(pm ? t('pay.added', { label: pm.label }) : t('pay.addedPending'))} />
    </FlatPage>
  );
}
