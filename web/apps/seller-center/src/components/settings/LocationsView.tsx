'use client';

import {
  isApiError,
  sellerKeys,
  sellerQueries,
  type ShipFromAddress,
  type ShipFromForm,
  ShipFromFormSchema,
  shipFromToForm
} from '@afrotransact/api-client';
import { AddressFields, Button, PhoneNumberField, Sheet, TextField, useToast } from '@afrotransact/ui';
import { MapPin, Plus } from '@phosphor-icons/react/ssr';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import Link from 'next/link';
import { type FormEvent, useState } from 'react';

import { sellerAddressSearch, STATE_OPTIONS } from '@/components/onboarding/WizardKit';
import { Notice } from '@/components/ui/Notice';
import { ErrorState, problemMessage, Spinner } from '@/components/ui/States';
import { StatusPill } from '@/components/ui/StatusPill';
import { useT } from '@/i18n/t';
import { sellerApi } from '@/lib/api';
import { useSeller } from '@/lib/session';

import { BusinessAddressStart } from './BusinessAddressStart';
import { SettingsPage, useSectionAccess } from './SettingsShell';

type AddressForm = ShipFromForm & { label: string; company: string };
const EMPTY: AddressForm = { label: '', name: '', company: '', phone: '', line1: '', line2: '', city: '', state: '', postalCode: '' };

/** Where geo-service placed the address: coordinates with a map link (no map key needed), or why it isn't located. */
function Position({ a }: { a: ShipFromAddress }) {
  const t = useT();
  const p = a.position;
  if (!p) {
    return (
      <Notice tone="warning" title={t('sc.shipFrom.notLocated')}>
        {t('sc.shipFrom.notLocatedHelp')}
      </Notice>
    );
  }
  const lat = p.lat.toFixed(4);
  const lng = p.lng.toFixed(4);
  return (
    <div className="flex flex-col gap-1">
      <p className="m-0 flex flex-wrap items-center gap-2 text-sm" data-testid="ship-from-position">
        <MapPin size={16} weight="fill" aria-hidden className="text-success" />
        <span>{t('sc.shipFrom.position', { lat, lng })}</span>
        <span className="text-xs text-muted">({p.precision === 'ZIP' ? t('sc.shipFrom.positionZip') : t('sc.shipFrom.positionStreet')})</span>
        <a
          className="text-link underline"
          href={`https://www.openstreetmap.org/?mlat=${lat}&mlon=${lng}#map=16/${lat}/${lng}`}
          target="_blank"
          rel="noreferrer noopener"
          aria-label={t('sc.shipFrom.viewMapAria', { label: a.label })}
        >
          {t('sc.shipFrom.viewMap')}
        </a>
      </p>
      {p.precision === 'ZIP' ? (
        <Notice tone="warning" title={t('sc.shipFrom.zipOnly')}>
          {t('sc.shipFrom.zipOnlyHelp')}
        </Notice>
      ) : null}
    </div>
  );
}

function AddressDialog({ open, address, onClose }: { open: boolean; address: ShipFromAddress | null; onClose: () => void }) {
  const t = useT();
  const qc = useQueryClient();
  const toast = useToast();
  const { orgId } = useSeller();
  const [form, setForm] = useState<AddressForm>(address ? { ...shipFromToForm(address), label: address.label, company: address.company } : EMPTY);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const save = useMutation({
    mutationFn: (f: AddressForm) =>
      address ? sellerApi().updateShipFrom(address.id, { ...f, version: address.version }) : sellerApi().createShipFrom({ ...f, makeDefault: false }),
    onSuccess: () => {
      toast.show(t('sc.shipFrom.saved'));
      void qc.invalidateQueries({ queryKey: sellerKeys.shipFrom(orgId) });
      onClose();
    },
    onError: (e) => setErrors(isApiError(e) && Object.keys(e.fieldErrors).length ? e.fieldErrors : { form: problemMessage(e) })
  });
  const submit = (e: FormEvent) => {
    e.preventDefault();
    const parsed = ShipFromFormSchema.safeParse(form);
    if (!parsed.success) {
      setErrors(Object.fromEntries(parsed.error.issues.map((i) => [String(i.path[0]), i.message])));
      return;
    }
    setErrors({});
    save.mutate({ ...form, ...parsed.data });
  };
  const field = (k: keyof AddressForm, label: string, extra: { autoComplete?: string; inputMode?: 'numeric' } = {}) => (
    <TextField id={`sfa-${k}`} label={label} value={form[k]} error={errors[k]} onChange={(e) => {
        const value = e.currentTarget.value; // read now: React clears currentTarget before a state updater runs
        setForm((f) => ({ ...f, [k]: value }));
      }} {...extra} />
  );
  return (
    <Sheet width={520} open={open} onOpenChange={(o) => (o ? undefined : onClose())} title={address ? t('sc.shipFrom.editTitle', { label: address.label }) : t('sc.shipFrom.addTitle')}>
      <form noValidate onSubmit={submit} className="flex flex-col gap-3">
        {field('label', t('sc.shipFrom.label'))}
        {field('name', t('sc.shipFrom.name'), { autoComplete: 'name' })}
        {field('company', t('sc.shipFrom.company'), { autoComplete: 'organization' })}
        <PhoneNumberField id="sfa-phone" label={t('sc.shipFrom.phone')} value={form.phone} error={errors.phone} onChange={(phone) => setForm((f) => ({ ...f, phone }))} />
        <AddressFields
          idPrefix="sfa"
          value={{ line1: form.line1, line2: form.line2, city: form.city, state: form.state, zip: form.postalCode, country: 'US' }}
          onChange={(a) => setForm((f) => ({ ...f, line1: a.line1, line2: a.line2, city: a.city, state: a.state, postalCode: a.zip }))}
          searchAddresses={sellerAddressSearch}
          regions={STATE_OPTIONS}
          errors={{ line1: errors.line1, line2: errors.line2, city: errors.city, state: errors.state, zip: errors.postalCode }}
          labels={{ line1: t('sc.shipFrom.line1'), line2: t('sc.shipFrom.line2'), city: t('sc.shipFrom.city'), state: t('sc.shipFrom.state'), zip: t('sc.shipFrom.zip') }}
        />
        {errors.form ? (
          <Notice tone="danger" title={errors.form} role="alert" />
        ) : null}
        <div className="flex justify-end gap-2">
          <Button variant="neutral" onClick={onClose}>
            {t('sc.shipFrom.cancel')}
          </Button>
          <Button type="submit" loading={save.isPending}>
            {t('sc.shipFrom.save')}
          </Button>
        </div>
      </form>
    </Sheet>
  );
}


/**
 * Settings > Locations: the addresses orders leave from (the business address is offered to start from), where geo placed
 * them and which one is the default. Pickup and driver pickup have their own pages and link back here to change an address.
 */
export function LocationsView() {
  const t = useT();
  const qc = useQueryClient();
  const toast = useToast();
  const { orgId } = useSeller();
  const { canEdit } = useSectionAccess('locations');
  const q = useQuery(sellerQueries.shipFrom(sellerApi(), orgId));
  const [dialog, setDialog] = useState<{ open: boolean; address: ShipFromAddress | null }>({ open: false, address: null });
  const makeDefault = useMutation({
    mutationFn: (id: string) => sellerApi().defaultShipFrom(id),
    onSuccess: (list) => qc.setQueryData(sellerKeys.shipFrom(orgId), list),
    onError: (e) => toast.show(problemMessage(e), 'error')
  });
  return (
    <SettingsPage
      section="locations"
      actions={
        <Button onClick={() => setDialog({ open: true, address: null })}>
          <Plus size={18} aria-hidden /> {t('sc.shipFrom.add')}
        </Button>
      }
    >
      {q.isPending ? <Spinner /> : null}
      {q.isError ? <ErrorState error={q.error} onRetry={() => void q.refetch()} /> : null}
      {q.data && q.data.length === 0 ? (
        <div className="flex flex-col items-start gap-3" data-testid="locations-empty">
          <p className="m-0 text-sm text-muted">{t('sc.shipFrom.none')}</p>
          {canEdit ? <BusinessAddressStart /> : null}
        </div>
      ) : null}
      <ul className="m-0 flex list-none flex-col p-0" data-testid="locations-list">
        {q.data?.map((a) => (
          <li key={a.id} className="flex flex-col gap-2 border-b border-border py-4 first:border-t" data-testid="ship-from-address" data-tour="ship-from-address">
            <div className="flex flex-wrap items-center gap-2">
              <h2 className="m-0 flex-1 text-md font-semibold">{a.label || a.city}</h2>
              {a.isDefault ? <StatusPill tone="info">{t('sc.shipFrom.default')}</StatusPill> : null}
              {canEdit && !a.isDefault ? (
                <Button size="sm" variant="neutral" loading={makeDefault.isPending} onClick={() => makeDefault.mutate(a.id)}>
                  {t('sc.shipFrom.makeDefault')}
                </Button>
              ) : null}
              {canEdit ? (
                <Button size="sm" variant="neutral" onClick={() => setDialog({ open: true, address: a })}>
                  {t('sc.shipFrom.edit')}
                </Button>
              ) : null}
            </div>
            <address className="text-sm not-italic">
              {a.name}
              {a.company ? ` · ${a.company}` : ''}
              <br />
              {a.line1}
              {a.line2 ? `, ${a.line2}` : ''}
              <br />
              {a.city}, {a.state} {a.postalCode}
            </address>
            <Position a={a} />
            <p className="m-0 text-sm">
              <Link href="/settings/pickup" className="text-link underline">
                {t('sc.settings.locations.pickupLink')}
              </Link>
              {' · '}
              <Link href="/settings/shipping" className="text-link underline">
                {t('sc.settings.locations.shippingLink')}
              </Link>
            </p>
          </li>
        ))}
      </ul>
      {dialog.open ? <AddressDialog open address={dialog.address} onClose={() => setDialog({ open: false, address: null })} /> : null}
    </SettingsPage>
  );
}
