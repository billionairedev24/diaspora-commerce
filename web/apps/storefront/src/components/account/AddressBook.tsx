'use client';

import { accountKeys, type Address, isApiError } from '@afrotransact/api-client';
import { Plus, Skeleton, useToast } from '@afrotransact/ui';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';

import { useT } from '@/i18n/t';
import { browserApi } from '@/lib/api/browser';
import { shopperQueries } from '@/lib/api/queries';

import { AccountCrumb } from './AccountCrumb';
import { AddressDialog } from './AddressDialog';
import styles from './Pages.module.css';

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

  return (
    <div className={styles.page}>
      <AccountCrumb>{t('account.addresses')}</AccountCrumb>
      <h1 className={styles.h1}>{t('addr.title')}</h1>
      {list.isError && <p className={styles.error} role="alert">{t('acct.loadError')}</p>}
      <div className={styles.grid}>
        <button type="button" className={styles.addTile} onClick={() => { setEditing(null); setOpen(true); }}>
          <Plus size={32} aria-hidden />
          <strong>{t('addr.addTile')}</strong>
        </button>
        {list.isPending && <Skeleton height={200} />}
        {sorted.map((a) => (
          <section key={a.id} className={`${styles.card} ${styles.addressCard}`} aria-label={a.name} data-testid="address-card">
            {a.isDefault && <div className={styles.defaultTag}>{t('addr.defaultTag')}</div>}
            <p className={styles.muted} style={{ color: 'var(--at-color-text)' }}>
              <strong>{a.name}</strong>
              <br />
              {a.line1}
              {a.line2 && (
                <>
                  <br />
                  {a.line2}
                </>
              )}
              <br />
              {a.city}, {a.state} {a.zip}
              <br />
              {t('addr.phoneLine', { phone: a.phone })}
            </p>
            {!a.verified && <span className={styles.status}>{t('addr.notVerified')}</span>}
            <div className={styles.actions}>
              <button type="button" className="link-button" onClick={() => { setEditing(a); setOpen(true); }}>
                {t('common.edit')}
              </button>
              <span className="sep" aria-hidden>|</span>
              <button type="button" className="link-button" onClick={() => remove.mutate(a)} disabled={remove.isPending}>
                {t('common.remove')}
              </button>
              {!a.isDefault && (
                <>
                  <span className="sep" aria-hidden>|</span>
                  <button type="button" className="link-button" onClick={() => makeDefault.mutate(a)} disabled={makeDefault.isPending}>
                    {t('addr.setDefault')}
                  </button>
                </>
              )}
            </div>
          </section>
        ))}
      </div>
      <AddressDialog open={open} onOpenChange={setOpen} address={editing} onSaved={(a) => toast.show(editing ? t('addr.updated') : t('addr.added', { name: a.name }))} />
    </div>
  );
}
