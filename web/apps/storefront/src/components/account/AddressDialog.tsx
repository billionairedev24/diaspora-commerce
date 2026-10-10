'use client';

import { accountKeys, type Address, AddressInputSchema, checkoutKeys, isApiError, US_STATES } from '@afrotransact/api-client';
import { AddressAutocomplete, type AddressSuggestionItem, Button, Dialog, Select, TextField, WarningCircle } from '@afrotransact/ui';
import { useMutation, useQueryClient } from '@tanstack/react-query';
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

  useEffect(() => {
    if (open) {
      setDraft(address ? fromAddress(address) : EMPTY);
      setErrors({});
      setUnverified(null);
    }
  }, [open, address]);

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

  const pick = (s: AddressSuggestionItem) => {
    setDraft((d) => ({ ...d, line1: s.address.line1 || s.label, line2: s.address.line2 || d.line2, city: s.address.city, state: s.address.state, zip: s.address.zip }));
    setErrors({});
    setUnverified(null);
  };

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
        <Select label={t('addr.country')} options={[{ value: 'US', label: 'United States' }]} value="US" disabled onChange={() => undefined} />
        <TextField id="addr-name" label={t('addr.name')} value={draft.name} onChange={(e) => set('name')(e.target.value)} error={errors.name} autoComplete="name" required />
        <TextField id="addr-phone" label={t('addr.phone')} value={draft.phone} onChange={(e) => set('phone')(e.target.value)} error={errors.phone} autoComplete="tel" inputMode="tel" required
          help={t('addr.phoneHelp')} />
        <AddressAutocomplete
          id="addr-line1"
          label={t('addr.line1')}
          value={draft.line1}
          onChange={(v) => { set('line1')(v); setUnverified(null); }}
          onSelect={pick}
          searchAddresses={bffAddressSearch}
          placeholder={t('addr.line1Placeholder')}
          error={errors.line1}
          name="line1"
        />
        <TextField id="addr-line2" label={t('addr.line2')} value={draft.line2} onChange={(e) => set('line2')(e.target.value)} error={errors.line2} autoComplete="address-line2" />
        <div className={styles.three}>
          <TextField id="addr-city" label={t('addr.city')} value={draft.city} onChange={(e) => set('city')(e.target.value)} error={errors.city} autoComplete="address-level2" required />
          <Select id="addr-state" label={t('addr.state')} options={STATES} placeholder={t('addr.statePlaceholder')} value={draft.state} onChange={(e) => set('state')(e.target.value)} error={errors.state} required />
          <TextField id="addr-zip" label={t('addr.zip')} value={draft.zip} onChange={(e) => set('zip')(e.target.value)} error={errors.zip} inputMode="numeric" autoComplete="postal-code" maxLength={10} required />
        </div>
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
