'use client';

import { accountKeys, accountQueries, type Address, AddressInputSchema, checkoutKeys, isApiError, shopperQueries, US_STATES } from '@afrotransact/api-client';
import { AddressFields, Button, Dialog, PhoneNumberField, TextField, WarningCircle } from '@afrotransact/ui';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { type FormEvent, useEffect, useState } from 'react';

import { FormErrors } from '@/components/common/FormErrors';
import { bffAddressSearch } from '@/components/shell/DeliverToDialog';
import { useT } from '@/i18n/t';
import { browserApi } from '@/lib/api/browser';

import styles from './Pages.module.css';

interface Draft {
  name: string;
  phone: string;
  line1: string;
  line2: string;
  city: string;
  state: string;
  zip: string;
  isDefault: boolean;
}

const EMPTY: Draft = { name: '', phone: '', line1: '', line2: '', city: '', state: '', zip: '', isDefault: false };
const STATES = US_STATES.map(([value, label]) => ({ value, label: `${label} (${value})` }));

const fromAddress = (a: Address): Draft => ({ name: a.name, phone: a.phone, line1: a.line1, line2: a.line2, city: a.city, state: a.state, zip: a.zip, isDefault: a.isDefault });

/**
 * A new address starts with the person's name (account) and phone (the default saved address's, else any saved one:
 * the account only exposes a masked phone); both stay editable.
 */
export function prefilledDraft(name: string, addresses: Address[]): Draft {
  const withPhone = addresses.find((a) => a.isDefault && a.phone) ?? addresses.find((a) => a.phone);
  return { ...EMPTY, name, phone: withPhone?.phone ?? '', isDefault: addresses.length === 0 };
}

/**
 * Add or edit a delivery address (S148, S047): street autocomplete through the BFF (geo: Google Places / OSM), USPS-style
 * verification by geo — an address it can't verify can be kept as entered. No "use my current location" here.
 */
export function AddressDialog({
  open,
  onOpenChange,
  address,
  onSaved
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  address?: Address | null;
  onSaved?: (a: Address) => void;
}) {
  const t = useT();
  const qc = useQueryClient();
  const [draft, setDraft] = useState<Draft>(EMPTY);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [unverified, setUnverified] = useState<string | null>(null);
  const me = useQuery({ ...accountQueries.me(browserApi()), enabled: open && !address });
  const saved = useQuery({ ...shopperQueries.addresses(browserApi()), enabled: open && !address });
  const user = me.data?.user;
  const fullName = user ? `${user.firstName} ${user.lastName}`.trim() : '';

  useEffect(() => {
    if (open) {
      setDraft(address ? fromAddress(address) : prefilledDraft(fullName, saved.data ?? []));
      setErrors({});
      setUnverified(null);
    }
  }, [open, address, fullName, saved.data]);

  const save = useMutation({
    mutationFn: (input: { acceptUnverified: boolean }) => {
      const body = { ...draft, line2: draft.line2 || undefined, acceptUnverified: input.acceptUnverified };
      return address ? browserApi().updateAddress(address.id, body) : browserApi().createAddress(body);
    },
    onSuccess: async (saved) => {
      await Promise.all([qc.invalidateQueries({ queryKey: accountKeys.addresses() }), qc.invalidateQueries({ queryKey: checkoutKeys.all }), qc.invalidateQueries({ queryKey: accountKeys.me() })]);
      onSaved?.(saved);
      onOpenChange(false);
    },
    onError: (e) => {
      if (!isApiError(e)) return setErrors({ _: t('auth.genericError') });
      const f = e.fieldErrors;
      const street = f.line1 ?? f.line2;
      if (street && Object.keys(f).every((k) => k === 'line1' || k === 'line2')) setUnverified(street);
      else setErrors(Object.keys(f).length ? f : { _: e.message });
    }
  });

  const set = (k: keyof Draft) => (v: string | boolean) => setDraft((d) => ({ ...d, [k]: v }));


  const submit = (e: FormEvent) => {
    e.preventDefault();
    const parsed = AddressInputSchema.safeParse({ ...draft, line2: draft.line2 || undefined });
    if (!parsed.success) {
      const next: Record<string, string> = {};
      parsed.error.issues.forEach((i) => { next[String(i.path[0])] ??= i.message; });
      return setErrors(next);
    }
    setErrors({});
    setUnverified(null);
    save.mutate({ acceptUnverified: false });
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange} title={address ? t('addr.editTitle') : t('addr.addTitle')} width={520}>
      <form className={styles.form} onSubmit={submit} noValidate>
        <FormErrors errors={errors} ids={{ name: 'addr-name', phone: 'addr-phone', line1: 'addr-line1', line2: 'addr-line2', city: 'addr-city', state: 'addr-state', zip: 'addr-zip' }} />
        <TextField id="addr-name" label={t('addr.name')} value={draft.name} onChange={(e) => set('name')(e.target.value)} error={errors.name} autoComplete="name" required />
        <PhoneNumberField id="addr-phone" label={t('addr.phone')} value={draft.phone} onChange={(v) => set('phone')(v)} error={errors.phone} required help={t('addr.phoneHelp')} />
        <AddressFields
          idPrefix="addr"
          value={{ line1: draft.line1, line2: draft.line2, city: draft.city, state: draft.state, zip: draft.zip, country: 'US' }}
          onChange={(a) => setDraft((d) => ({ ...d, line1: a.line1, line2: a.line2, city: a.city, state: a.state, zip: a.zip }))}
          onPick={() => {
            setErrors({});
            setUnverified(null);
          }}
          onStreetEdit={() => setUnverified(null)}
          searchAddresses={bffAddressSearch}
          regions={STATES}
          errors={errors}
          labels={{
            line1: t('addr.line1'),
            line2: t('addr.line2'),
            city: t('addr.city'),
            state: t('addr.state'),
            zip: t('addr.zip'),
            line1Placeholder: t('addr.line1Placeholder'),
            statePlaceholder: t('addr.statePlaceholder')
          }}
        />
        {unverified && (
          <div className={styles.callout} role="alert">
            <p className={styles.muted}>
              <WarningCircle aria-hidden /> <strong>{t('addr.unverifiedTitle')}</strong> {unverified}
            </p>
            <div className={styles.actions}>
              <Button size="sm" variant="neutral" onClick={() => setUnverified(null)}>
                {t('addr.fixIt')}
              </Button>
              <Button size="sm" onClick={() => save.mutate({ acceptUnverified: true })} loading={save.isPending}>
                {t('addr.keepMine')}
              </Button>
            </div>
          </div>
        )}
        <label className={styles.check}>
          <input type="checkbox" checked={draft.isDefault} onChange={(e) => set('isDefault')(e.target.checked)} />
          {t('addr.makeDefault')}
        </label>
        {errors._ && <p className={styles.error} role="alert">{errors._}</p>}
        <div className={styles.actions}>
          <Button variant="neutral" onClick={() => onOpenChange(false)}>
            {t('common.cancel')}
          </Button>
          <Button type="submit" loading={save.isPending && !unverified}>
            {address ? t('addr.save') : t('addr.add')}
          </Button>
        </div>
      </form>
    </Dialog>
  );
}
