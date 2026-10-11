'use client';

import {
  isApiError,
  PICKUP_DAYS,
  type PickupDay,
  type PickupWindow,
  sellerKeys,
  sellerQueries,
  type ShipFromAddress,
  type ShipFromForm,
  ShipFromFormSchema,
  shipFromToForm,
  validatePickupHours
} from '@afrotransact/api-client';
import { Button, buttonClassName, Checkbox, Dialog, Select, TextField, useToast } from '@afrotransact/ui';
import { MapPin, Plus, Trash } from '@phosphor-icons/react/ssr';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import Link from 'next/link';
import { type FormEvent, useState } from 'react';

import { Card } from '@/components/ui/Card';
import { Notice } from '@/components/ui/Notice';
import { PageHeader } from '@/components/ui/PageHeader';
import { ErrorState, problemMessage, Spinner } from '@/components/ui/States';
import { StatusPill } from '@/components/ui/StatusPill';
import { type MessageKey, type Translator, useT } from '@/i18n/t';
import { sellerApi } from '@/lib/api';
import { shortDate } from '@/lib/format';
import { useSeller } from '@/lib/session';

import { CustomerPickupSettings } from './CustomerPickupSettings';

type AddressForm = ShipFromForm & { label: string; company: string };
const EMPTY: AddressForm = { label: '', name: '', company: '', phone: '', line1: '', line2: '', city: '', state: '', postalCode: '' };

/** The pickup refusals of seller-service, in the seller's words (S255). */
function pickupProblem(t: Translator<MessageKey>, e: unknown): { message: string; fields: Record<string, string> } {
  if (!isApiError(e)) return { message: problemMessage(e), fields: {} };
  switch (e.code) {
    case 'PICKUP_ORG_NOT_APPROVED':
    case 'NOT_APPROVED':
      return { message: t('sc.pickup.err.notApproved'), fields: {} };
    case 'PICKUP_OUTSIDE_DELIVERY_AREA':
      return { message: `${t('sc.pickup.err.outside')} ${e.message}`, fields: {} };
    case 'PICKUP_NOT_GEOCODED':
      return { message: `${t('sc.pickup.err.geocoding')} ${e.message}`, fields: {} };
    case 'PICKUP_INVALID_HOURS':
      return { message: t('sc.pickup.err.hours'), fields: e.fieldErrors };
    default:
      return { message: problemMessage(e), fields: e.fieldErrors };
  }
}

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

/** "AfroTransact picks up here" with the pickup hours (1–14 windows, 24-hour local times). */
function PickupSettings({ a, editable }: { a: ShipFromAddress; editable: boolean }) {
  const t = useT();
  const qc = useQueryClient();
  const toast = useToast();
  const { orgId } = useSeller();
  const [enabled, setEnabled] = useState(a.afrotransactPickup.enabled);
  const [hours, setHours] = useState<PickupWindow[]>(a.afrotransactPickup.hours);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [problem, setProblem] = useState<string | null>(null);
  const save = useMutation({
    mutationFn: () => sellerApi().setAfrotransactPickup(a.id, { enabled, hours, version: a.version }),
    onSuccess: (saved) => {
      setProblem(null);
      setErrors({});
      setEnabled(saved.afrotransactPickup.enabled);
      setHours(saved.afrotransactPickup.hours);
      toast.show(t('sc.pickup.saved'));
      void qc.invalidateQueries({ queryKey: sellerKeys.shipFrom(orgId) });
    },
    onError: (e) => {
      const p = pickupProblem(t, e);
      setProblem(p.message);
      setErrors(p.fields);
    }
  });
  const submit = (e: FormEvent) => {
    e.preventDefault();
    const errs = validatePickupHours(enabled, hours);
    setErrors(errs);
    setProblem(Object.keys(errs).length ? t('sc.pickup.err.hours') : null);
    if (!Object.keys(errs).length) save.mutate();
  };
  const dayOptions = PICKUP_DAYS.map((d) => ({ value: d, label: t(`sc.day.${d}`) }));
  const setWindow = (i: number, w: Partial<PickupWindow>) => setHours((hs) => hs.map((h, j) => (j === i ? { ...h, ...w } : h)));
  const id = (s: string) => `${a.id}-${s}`;
  return (
    <form noValidate onSubmit={submit} className="flex flex-col gap-3 rounded-md border border-border p-3" aria-label={t('sc.pickup.title')}>
      <div className="flex flex-wrap items-center gap-2">
        <h3 className="m-0 flex-1 text-md font-semibold">{t('sc.pickup.title')}</h3>
        {a.afrotransactPickup.enabled ? <StatusPill tone="good">{t('sc.ff.AFROTRANSACT_DELIVERY')}</StatusPill> : null}
      </div>
      <Checkbox
        id={id('pickup-enabled')}
        checked={enabled}
        disabled={!editable}
        onChange={(e) => {
          const on = e.currentTarget.checked;
          setEnabled(on);
          if (on && hours.length === 0) setHours([{ day: 'MONDAY', opens: '09:00', closes: '17:00' }]);
        }}
        label={t('sc.pickup.toggle')}
        description={t('sc.pickup.toggleHelp')}
      />
      {a.afrotransactPickup.zoneName ? (
        <p className="m-0 text-xs text-muted">
          {t('sc.pickup.zone', { zone: a.afrotransactPickup.zoneName })}
          {a.afrotransactPickup.enabledAt ? ` · ${t('sc.pickup.since', { date: shortDate(a.afrotransactPickup.enabledAt) })}` : ''}
        </p>
      ) : null}
      <fieldset className="m-0 flex flex-col gap-2 border-0 p-0" disabled={!editable}>
        <legend className="mb-1 text-sm font-semibold">{t('sc.pickup.hours')}</legend>
        {errors.hours ? <p className="m-0 text-sm text-danger">{errors.hours}</p> : null}
        {hours.length === 0 ? <p className="m-0 text-sm text-muted">{t('sc.pickup.noWindows')}</p> : null}
        {hours.map((h, i) => (
          <div key={`${a.id}-${i}`} className="grid grid-cols-[minmax(0,1.4fr)_minmax(0,1fr)_minmax(0,1fr)_auto] items-start gap-2" data-testid="pickup-window">
            <Select id={id(`day-${i}`)} label={<span className="at-visually-hidden">{t('sc.pickup.dayN', { n: i + 1 })}</span>} value={h.day} options={dayOptions} error={errors[`hours.${i}.day`]} onChange={(e) => setWindow(i, { day: e.currentTarget.value as PickupDay })} />
            <TextField id={id(`opens-${i}`)} type="time" label={<span className="at-visually-hidden">{t('sc.pickup.opensN', { n: i + 1 })}</span>} value={h.opens} error={errors[`hours.${i}.opens`]} onChange={(e) => setWindow(i, { opens: e.currentTarget.value })} />
            <TextField id={id(`closes-${i}`)} type="time" label={<span className="at-visually-hidden">{t('sc.pickup.closesN', { n: i + 1 })}</span>} value={h.closes} error={errors[`hours.${i}.closes`]} onChange={(e) => setWindow(i, { closes: e.currentTarget.value })} />
            <Button size="sm" variant="neutral" aria-label={t('sc.pickup.removeN', { n: i + 1 })} onClick={() => setHours((hs) => hs.filter((_, j) => j !== i))}>
              <Trash size={16} aria-hidden />
            </Button>
          </div>
        ))}
        {hours.length < 14 ? (
          <div>
            <Button size="sm" variant="neutral" onClick={() => setHours((hs) => [...hs, { day: 'SATURDAY', opens: '10:00', closes: '14:00' }])}>
              <Plus size={16} aria-hidden /> {t('sc.pickup.addWindow')}
            </Button>
          </div>
        ) : null}
      </fieldset>
      {problem ? (
        <Notice tone="danger" title={t('sc.pickup.err.title')} role="alert">
          {problem}
        </Notice>
      ) : null}
      {editable ? (
        <div>
          <Button type="submit" size="sm" loading={save.isPending}>
            {t('sc.pickup.save')}
          </Button>
        </div>
      ) : null}
    </form>
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
    <TextField id={`sfa-${k}`} label={label} value={form[k]} error={errors[k]} onChange={(e) => setForm((f) => ({ ...f, [k]: e.currentTarget.value }))} {...extra} />
  );
  return (
    <Dialog open={open} onOpenChange={(o) => (o ? undefined : onClose())} title={address ? t('sc.shipFrom.editTitle', { label: address.label }) : t('sc.shipFrom.addTitle')}>
      <form noValidate onSubmit={submit} className="flex flex-col gap-3">
        {field('label', t('sc.shipFrom.label'))}
        {field('name', t('sc.shipFrom.name'), { autoComplete: 'name' })}
        {field('company', t('sc.shipFrom.company'), { autoComplete: 'organization' })}
        {field('phone', t('sc.shipFrom.phone'), { autoComplete: 'tel' })}
        {field('line1', t('sc.shipFrom.line1'), { autoComplete: 'address-line1' })}
        {field('line2', t('sc.shipFrom.line2'), { autoComplete: 'address-line2' })}
        <div className="grid grid-cols-[minmax(0,2fr)_minmax(0,1fr)_minmax(0,1fr)] gap-2">
          {field('city', t('sc.shipFrom.city'), { autoComplete: 'address-level2' })}
          {field('state', t('sc.shipFrom.state'), { autoComplete: 'address-level1' })}
          {field('postalCode', t('sc.shipFrom.zip'), { autoComplete: 'postal-code', inputMode: 'numeric' })}
        </div>
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
    </Dialog>
  );
}

/** Orders → Ship-from & pickup (S231, S255): addresses, where geo placed them, AfroTransact driver pickup with its hours,
    and customer pickup (checkout offers the address as a free pickup point for the seller's items). */
export function ShipFromView() {
  const t = useT();
  const qc = useQueryClient();
  const toast = useToast();
  const { orgId, can } = useSeller();
  const editable = can('orders');
  const q = useQuery(sellerQueries.shipFrom(sellerApi(), orgId));
  const [dialog, setDialog] = useState<{ open: boolean; address: ShipFromAddress | null }>({ open: false, address: null });
  const makeDefault = useMutation({
    mutationFn: (id: string) => sellerApi().defaultShipFrom(id),
    onSuccess: (list) => qc.setQueryData(sellerKeys.shipFrom(orgId), list),
    onError: (e) => toast.show(problemMessage(e), 'error')
  });
  return (
    <>
      <PageHeader
        title={t('sc.shipFrom.title')}
        description={t('sc.shipFrom.description')}
        actions={
          <div className="flex flex-wrap gap-2">
            <Link href="/orders" className={buttonClassName({ variant: 'neutral' })}>
              {t('sc.shipFrom.back')}
            </Link>
            {editable ? (
              <Button onClick={() => setDialog({ open: true, address: null })}>
                <Plus size={18} aria-hidden /> {t('sc.shipFrom.add')}
              </Button>
            ) : null}
          </div>
        }
      />
      {q.isPending ? <Spinner /> : null}
      {q.isError ? <ErrorState error={q.error} onRetry={() => void q.refetch()} /> : null}
      {q.data && q.data.length === 0 ? <p className="m-0 text-sm text-muted">{t('sc.shipFrom.none')}</p> : null}
      <div className="flex flex-col gap-4">
        {q.data?.map((a) => (
          <Card
            key={a.id}
            title={a.label || a.city}
            actions={
              <div className="flex flex-wrap items-center gap-2">
                {a.isDefault ? <StatusPill tone="info">{t('sc.shipFrom.default')}</StatusPill> : null}
                {editable && !a.isDefault ? (
                  <Button size="sm" variant="neutral" loading={makeDefault.isPending} onClick={() => makeDefault.mutate(a.id)}>
                    {t('sc.shipFrom.makeDefault')}
                  </Button>
                ) : null}
                {editable ? (
                  <Button size="sm" variant="neutral" onClick={() => setDialog({ open: true, address: a })}>
                    {t('sc.shipFrom.edit')}
                  </Button>
                ) : null}
              </div>
            }
          >
            <div className="grid grid-cols-[repeat(auto-fit,minmax(min(100%,320px),1fr))] gap-4" data-testid="ship-from-address">
              <div className="flex flex-col gap-2">
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
              </div>
              <div className="flex flex-col gap-4">
                <PickupSettings key={`${a.id}-${a.version}`} a={a} editable={editable} />
                <CustomerPickupSettings key={`${a.id}-cp-${a.customerPickup?.enabled ? 'on' : 'off'}`} a={a} editable={editable} />
              </div>
            </div>
          </Card>
        ))}
      </div>
      {dialog.open ? <AddressDialog open address={dialog.address} onClose={() => setDialog({ open: false, address: null })} /> : null}
    </>
  );
}
