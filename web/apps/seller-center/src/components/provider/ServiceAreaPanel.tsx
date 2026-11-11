'use client';

import { isApiError, sellerKeys, sellerQueries, type ServiceAreaNamed, type ServiceAreaView } from '@afrotransact/api-client';
import { AddressFields, Button, Select, Sheet, TextField, useToast } from '@afrotransact/ui';
import { MapPin, X } from '@phosphor-icons/react/ssr';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useState } from 'react';

import { sellerAddressSearch, STATE_OPTIONS } from '@/components/onboarding/WizardKit';
import { Card } from '@/components/ui/Card';
import { Notice } from '@/components/ui/Notice';
import { problemMessage } from '@/components/ui/States';
import { useT } from '@/i18n/t';
import { sellerApi } from '@/lib/api';
import { useSeller } from '@/lib/session';

const SHOWN = 6;

/** "Georgetown, Hutto, Pflugerville +4" from the cities a radius reaches (the first few, then the count). */
export function citiesLine(cities: { city: string }[], shown = SHOWN): { text: string; more: number } {
  const names = [...new Set(cities.map((c) => c.city))];
  return { text: names.slice(0, shown).join(', '), more: Math.max(0, names.length - shown) };
}

/**
 * Service area: where the provider is based. A base address, how far they travel to customers (a radius within the
 * staff-set bounds for their service categories; customers who go to a shop choose their own distance), optional named extra areas (cities, ZIPs, whole metro zones) and a live preview of the cities and
 * the zone the radius covers. Whether a service comes to the customer or the customer goes to the provider is set on each
 * service. A service that comes to the customer is shown only to customers inside the radius.
 */
export function ServiceAreaPanel() {
  const t = useT();
  const api = sellerApi();
  const { orgId, can } = useSeller();
  const editable = can('services');
  const view = useQuery(sellerQueries.serviceArea(api, orgId));
  const [open, setOpen] = useState(false);
  const area = view.data?.area ?? null;
  const served = area ? citiesLine(area.servedCities, 4) : null;

  if (view.isPending || view.isError) return null;
  return (
    <Card>
      <div className="flex flex-wrap items-center justify-between gap-3" data-testid="service-area-summary">
        <div className="flex min-w-0 flex-col gap-1">
          <h2 className="m-0 flex items-center gap-2 text-base font-semibold">
            <MapPin size={18} aria-hidden /> {t('sc.svcArea.title')}
          </h2>
          {area ? (
            <p className="m-0 text-sm text-text-muted">
              {t('sc.svcArea.summary', { city: `${area.baseCity}, ${area.baseState}`, miles: area.radiusMiles })}
              {area.zone ? ` · ${area.zone}` : ''}
              {served && served.text ? ` · ${t('sc.svcArea.serves', { cities: served.text })}${served.more ? ` +${served.more}` : ''}` : ''}
            </p>
          ) : (
            <Notice tone="warning" title={t('sc.svcArea.missing')}>
              {t('sc.svcArea.missingBody')}
            </Notice>
          )}
        </div>
        {editable ? (
          <Button variant={area ? 'neutral' : 'primary'} onClick={() => setOpen(true)}>
            {area ? t('sc.svcArea.edit') : t('sc.svcArea.set')}
          </Button>
        ) : null}
      </div>
      {view.data && open ? <AreaSheet view={view.data} onClose={() => setOpen(false)} /> : null}
    </Card>
  );
}

const EXTRA_KINDS = ['CITY', 'ZIP', 'ZONE'] as const;

function extraLabel(a: ServiceAreaNamed, zones: ServiceAreaView['zones']): string {
  if (a.kind === 'ZONE') return zones.find((z) => z.key === a.value)?.name ?? a.value;
  return a.kind === 'CITY' ? `${a.value}, ${a.state ?? ''}` : a.value;
}

function AreaSheet({ view, onClose }: { view: ServiceAreaView; onClose: () => void }) {
  const t = useT();
  const api = sellerApi();
  const qc = useQueryClient();
  const toast = useToast();
  const { orgId } = useSeller();
  const { bounds, area } = view;
  const [address, setAddress] = useState({ line1: '', line2: '', city: area?.baseCity ?? '', state: area?.baseState ?? '', zip: area?.baseZip ?? '', country: 'US' });
  const [radius, setRadius] = useState(area?.radiusMiles ?? bounds.defaultMiles);
  const [extras, setExtras] = useState<ServiceAreaNamed[]>(area?.extraAreas ?? []);
  const [kind, setKind] = useState<(typeof EXTRA_KINDS)[number]>('ZONE');
  const [extraValue, setExtraValue] = useState('');
  const [extraState, setExtraState] = useState('TX');
  const [errors, setErrors] = useState<Record<string, string>>({});
  const zip = address.zip.trim();

  // The preview follows the form after a short pause, so dragging the slider does not call the API for every mile.
  const [settled, setSettled] = useState({ zip, radius });
  useEffect(() => {
    const id = setTimeout(() => setSettled({ zip, radius }), 250);
    return () => clearTimeout(id);
  }, [zip, radius]);
  const preview = useQuery(sellerQueries.serviceAreaPreview(api, orgId, settled.zip, settled.radius));

  const save = useMutation({
    mutationFn: () => api.saveServiceArea({ baseZip: zip, radiusMiles: radius, extraAreas: extras }),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: sellerKeys.serviceArea(orgId) });
      toast.show(t('sc.svcArea.saved'));
      onClose();
    },
    onError: (e) => setErrors(isApiError(e) && Object.keys(e.fieldErrors).length ? { ...e.fieldErrors } : { form: problemMessage(e) })
  });

  const submit = () => {
    if (!/^\d{5}$/.test(zip)) return setErrors({ zip: t('sc.svcArea.zipInvalid') });
    if (radius < bounds.minMiles || radius > bounds.maxMiles) return setErrors({ radiusMiles: t('sc.svcArea.radiusBounds', { min: bounds.minMiles, max: bounds.maxMiles }) });
    setErrors({});
    save.mutate();
  };

  const addExtra = () => {
    const value = extraValue.trim();
    if (kind === 'ZONE') {
      if (!value) return setErrors({ extra: t('sc.svcArea.zoneChoose') });
    } else if (kind === 'ZIP') {
      if (!/^\d{5}$/.test(value)) return setErrors({ extra: t('sc.svcArea.zipInvalid') });
    } else if (!value) return setErrors({ extra: t('sc.svcArea.cityRequired') });
    const next: ServiceAreaNamed = kind === 'CITY' ? { kind, value, state: extraState } : { kind, value };
    if (extras.some((e) => e.kind === next.kind && e.value.toLowerCase() === next.value.toLowerCase() && e.state === next.state)) return;
    setExtras([...extras, next]);
    setExtraValue('');
    setErrors({});
  };

  const p = preview.data;
  const line = p ? citiesLine(p.cities) : null;
  return (
    <Sheet width={560} open onOpenChange={(o) => (o ? undefined : onClose())} title={t('sc.svcArea.sheetTitle')} description={t('sc.svcArea.sheetDescription')}>
      <form noValidate className="flex flex-col gap-4" onSubmit={(e) => { e.preventDefault(); submit(); }}>
        <fieldset className="m-0 flex flex-col gap-2 border-0 p-0">
          <legend className="mb-1 text-sm font-semibold">{t('sc.svcArea.base')}</legend>
          <AddressFields
            idPrefix="svc-area"
            value={address}
            onChange={(a) => setAddress({ ...address, ...a, country: 'US' })}
            searchAddresses={sellerAddressSearch}
            regions={STATE_OPTIONS}
            errors={{ zip: errors.zip ?? errors.baseZip, city: errors.baseCity }}
            labels={{ line1: t('sc.shipFrom.line1'), line2: t('sc.shipFrom.line2'), city: t('sc.shipFrom.city'), state: t('sc.shipFrom.state'), zip: t('sc.shipFrom.zip') }}
          />
          <p className="m-0 text-xs text-text-muted">{t('sc.svcArea.baseHelp')}</p>
        </fieldset>

        <div className="flex flex-col gap-1">
          <label htmlFor="svc-area-radius" className="text-sm font-semibold">
            {t('sc.svcArea.radius', { miles: radius })}
          </label>
          <input id="svc-area-radius" type="range" min={bounds.minMiles} max={bounds.maxMiles} step={1} value={radius} onChange={(e) => setRadius(Number(e.currentTarget.value))}
            aria-valuetext={t('sc.svcArea.radiusValue', { miles: radius })} aria-describedby="svc-area-radius-help" />
          <p id="svc-area-radius-help" className="m-0 text-xs text-text-muted">
            {t('sc.svcArea.radiusHelp', { min: bounds.minMiles, max: bounds.maxMiles })}
          </p>
          {errors.radiusMiles ? <span role="alert" className="text-sm text-danger">{errors.radiusMiles}</span> : null}
        </div>

        <section aria-label={t('sc.svcArea.preview')} className="rounded-md bg-surface-muted p-3 text-sm" data-testid="service-area-preview">
          <strong className="block">{t('sc.svcArea.preview')}</strong>
          {p && line ? (
            <p className="m-0 mt-1">
              {p.zone ? <span className="font-semibold">{p.zone}: </span> : null}
              {line.text}
              {line.more ? ` +${line.more}` : ''}
            </p>
          ) : (
            <p className="m-0 mt-1 text-text-muted">{t('sc.svcArea.previewEmpty')}</p>
          )}
        </section>

        <fieldset className="m-0 flex flex-col gap-2 border-0 p-0">
          <legend className="mb-1 text-sm font-semibold">{t('sc.svcArea.extra')}</legend>
          <p className="m-0 text-xs text-text-muted">{t('sc.svcArea.extraHelp')}</p>
          {extras.length > 0 ? (
            <ul className="m-0 flex list-none flex-wrap gap-2 p-0">
              {extras.map((a) => (
                <li key={`${a.kind}:${a.value}:${a.state ?? ''}`} className="inline-flex items-center gap-1 rounded-pill border border-border-strong px-3 py-1 text-sm">
                  {extraLabel(a, view.zones)}
                  <button type="button" aria-label={t('sc.svcArea.removeExtra', { name: extraLabel(a, view.zones) })} onClick={() => setExtras(extras.filter((x) => x !== a))} className="cursor-pointer border-0 bg-transparent p-0">
                    <X size={12} aria-hidden />
                  </button>
                </li>
              ))}
            </ul>
          ) : null}
          <div className="grid items-end gap-2 sm:grid-cols-[140px_1fr_auto]">
            <Select label={t('sc.svcArea.extraKind')} value={kind} onChange={(e) => { setKind(e.currentTarget.value as (typeof EXTRA_KINDS)[number]); setExtraValue(''); }}
              options={EXTRA_KINDS.map((k) => ({ value: k, label: t(`sc.svcArea.kind.${k}`) }))} />
            {kind === 'ZONE' ? (
              <Select label={t('sc.svcArea.kind.ZONE')} value={extraValue} placeholder={t('sc.svcArea.zoneChoose')} onChange={(e) => setExtraValue(e.currentTarget.value)} options={view.zones.map((z) => ({ value: z.key, label: z.name }))} />
            ) : (
              <TextField label={t(`sc.svcArea.kind.${kind}`)} value={extraValue} inputMode={kind === 'ZIP' ? 'numeric' : undefined} maxLength={kind === 'ZIP' ? 5 : 60} onChange={(e) => setExtraValue(e.currentTarget.value)} />
            )}
            <Button variant="neutral" onClick={addExtra}>{t('sc.svcArea.addExtra')}</Button>
          </div>
          {kind === 'CITY' ? <Select label={t('sc.shipFrom.state')} value={extraState} onChange={(e) => setExtraState(e.currentTarget.value)} options={STATE_OPTIONS} /> : null}
          {errors.extra ?? errors.extraAreas ? <span role="alert" className="text-sm text-danger">{errors.extra ?? errors.extraAreas}</span> : null}
        </fieldset>

        {errors.form ? <Notice tone="danger" title={errors.form} role="alert" /> : null}
        <div className="flex justify-end gap-2">
          <Button variant="neutral" onClick={onClose}>{t('sc.svcArea.cancel')}</Button>
          <Button type="submit" loading={save.isPending}>{t('sc.svcArea.save')}</Button>
        </div>
      </form>
    </Sheet>
  );
}
