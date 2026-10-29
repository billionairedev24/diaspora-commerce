'use client';

import { accountKeys, type Address, isApiError } from '@afrotransact/api-client';
import { Button, EmptyState, FlatPage, Message, Plus, Row, RowList, Section, Skeleton, StatusText, TextLinkButton, useToast } from '@afrotransact/ui';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Fragment, useState } from 'react';

import { useT } from '@/i18n/t';
import { browserApi } from '@/lib/api/browser';
import { shopperQueries } from '@/lib/api/queries';

import { AccountHeader } from './AccountHeader';
import { AddressDialog } from './AddressDialog';

/** Your Addresses (S148): add (autocomplete + verification), edit, remove, set default. */
export function AddressBook() {
  const t = useT();
  const api = browserApi();
  const qc = useQueryClient();
  const toast = useToast();
  const list = useQuery(shopperQueries.addresses(api));
  const [editing, setEditing] = useState<Address | null>(null);
  const [open, setOpen] = useState(false);
  const refresh = () => Promise.all([qc.invalidateQueries({ queryKey: accountKeys.addresses() }), qc.invalidateQueries({ queryKey: accountKeys.me() })]);
  const remove = useMutation({
    mutationFn: (a: Address) => api.deleteAddress(a.id),
    onSuccess: async () => { await refresh(); toast.show(t('addr.removed')); },
    onError: (e) => toast.show(isApiError(e) ? e.message : t('auth.genericError'))
  });
  const makeDefault = useMutation({
    mutationFn: (a: Address) => api.setDefaultAddress(a.id),
    onSuccess: async (a) => { await refresh(); toast.show(t('addr.defaultSet', { name: a.name })); },
    onError: (e) => toast.show(isApiError(e) ? e.message : t('auth.genericError'))
  });

  const sorted = [...(list.data ?? [])].sort((a, b) => Number(b.isDefault) - Number(a.isDefault));
  const lines = (a: Address): string[] => [a.line1, a.line2 ?? '', `${a.city}, ${a.state} ${a.zip}`, t('addr.phoneLine', { phone: a.phone })].filter(Boolean);

  return (
    <FlatPage>
      <AccountHeader
        title={t('addr.title')}
        crumb={t('account.addresses')}
        actions={
          <Button
            icon={<Plus aria-hidden />}
            onClick={() => {
              setEditing(null);
              setOpen(true);
            }}
          >
            {t('addr.addTile')}
          </Button>
        }
      />
      <Section title={t('addr.title')} id="addresses">
        {list.isPending && <Skeleton height={120} />}
        {list.isError && (
          <Message tone="error">
            {t('acct.loadError')} <TextLinkButton onClick={() => void list.refetch()}>{t('acct.retry')}</TextLinkButton>
          </Message>
        )}
        {list.isSuccess && sorted.length === 0 && <EmptyState>{t('addr.none')}</EmptyState>}
        {sorted.length > 0 && (
          <RowList>
            {sorted.map((a) => (
              <Row
                key={a.id}
                testId="address-card"
                title={
                  <>
                    {a.name}
                    {a.isDefault ? <StatusText tone="positive">{t('addr.defaultTag')}</StatusText> : null}
                    {!a.verified ? <StatusText tone="warning">{t('addr.notVerified')}</StatusText> : null}
                  </>
                }
                detail={lines(a).map((line, i) => (
                  <Fragment key={i}>
                    {i > 0 && <br />}
                    {line}
                  </Fragment>
                ))}
                actions={
                  <>
                    <TextLinkButton
                      onClick={() => {
                        setEditing(a);
                        setOpen(true);
                      }}
                      aria-label={`${t('common.edit')} ${a.name}`}
                    >
                      {t('common.edit')}
                    </TextLinkButton>
                    <TextLinkButton danger onClick={() => remove.mutate(a)} disabled={remove.isPending} aria-label={`${t('common.remove')} ${a.name}`}>
                      {t('common.remove')}
                    </TextLinkButton>
                    {!a.isDefault && (
                      <TextLinkButton onClick={() => makeDefault.mutate(a)} disabled={makeDefault.isPending} aria-label={`${t('addr.setDefault')}: ${a.name}`}>
                        {t('addr.setDefault')}
                      </TextLinkButton>
                    )}
                  </>
                }
              />
            ))}
          </RowList>
        )}
      </Section>
      <AddressDialog open={open} onOpenChange={setOpen} address={editing} onSaved={(a) => toast.show(editing ? t('addr.updated') : t('addr.added', { name: a.name }))} />
    </FlatPage>
  );
}
