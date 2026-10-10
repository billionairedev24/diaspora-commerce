'use client';

import { type FeesOverview, staffQueries } from '@afrotransact/api-client';
import { Button, Checkbox, Select, TextField } from '@afrotransact/ui';
import { Plus, Trash } from '@phosphor-icons/react/ssr';
import { useQuery } from '@tanstack/react-query';
import { useMemo, useState } from 'react';

import { Card } from '@/components/ui/Card';
import { Notice } from '@/components/ui/Notice';
import { problemMessage } from '@/components/ui/States';
import { useT } from '@/i18n/t';
import { staffApi } from '@/lib/api';
import { money } from '@/lib/format';

import { type DeliveryForm, previewQuote, type QuotePreview, type TariffForm, toDeliveryDraft } from './delivery-model';
import { parseMiles, rowId } from './money-input';
import { OrgSearch } from './OrgSearch';

const th = 'px-3 py-2 text-left text-xs font-semibold text-muted';
const td = 'px-3 py-2 align-top text-sm';

/** Element id of a delivery field, derived from its error key (deliveryPricing.customerCharge.baseMinor → dp-customerCharge-baseMinor). */
export const deliveryFieldId = (key: string): string => key.replace(/^deliveryPricing\./, 'dp-').replace(/\./g, '-');

/** One tariff: FLAT (per package) or DISTANCE (base + per mile, optional bands, minimum and maximum). Amounts in dollars. */
function TariffEditor({ name, prefix, value, onChange, errors }: { name: string; prefix: string; value: TariffForm; onChange: (t: TariffForm) => void; errors: Record<string, string> }) {
  const t = useT();
  const id = (field: string) => deliveryFieldId(`${prefix}.${field}`);
  const err = (field: string) => errors[`${prefix}.${field}`];
  const set = <K extends keyof TariffForm>(k: K, v: TariffForm[K]) => onChange({ ...value, [k]: v });
  return (
    <fieldset className="m-0 flex flex-col gap-3 rounded-md border border-border p-3">
      <legend className="px-1 text-sm font-semibold">{name}</legend>
      <Select
        id={id('mode')}
        className="max-w-xs"
        label={t('st.delivery.mode')}
        value={value.mode}
        error={err('mode')}
        options={[
          { value: 'FLAT', label: t('st.delivery.mode.flat') },
          { value: 'DISTANCE', label: t('st.delivery.mode.distance') }
        ]}
        onChange={(e) => set('mode', e.currentTarget.value === 'DISTANCE' ? 'DISTANCE' : 'FLAT')}
      />
      {value.mode === 'FLAT' ? (
        <TextField
          id={id('flatPerPackageMinor')}
          className="max-w-xs"
          inputMode="decimal"
          label={t('st.delivery.flat')}
          value={value.flat}
          error={err('flatPerPackageMinor')}
          help={t('st.delivery.flatHelp')}
          onChange={(e) => set('flat', e.currentTarget.value)}
        />
      ) : (
        <>
          <div className="grid grid-cols-[repeat(auto-fit,minmax(min(100%,160px),1fr))] gap-3">
            <TextField id={id('baseMinor')} inputMode="decimal" label={t('st.delivery.base')} value={value.base} error={err('baseMinor')} onChange={(e) => set('base', e.currentTarget.value)} />
            <TextField id={id('perMileMinor')} inputMode="decimal" label={t('st.delivery.perMile')} value={value.perMile} error={err('perMileMinor')} onChange={(e) => set('perMile', e.currentTarget.value)} />
            <TextField id={id('minMinor')} inputMode="decimal" label={t('st.delivery.min')} value={value.min} error={err('minMinor')} help={t('st.delivery.optional')} onChange={(e) => set('min', e.currentTarget.value)} />
            <TextField id={id('maxMinor')} inputMode="decimal" label={t('st.delivery.max')} value={value.max} error={err('maxMinor')} help={t('st.delivery.noCap')} onChange={(e) => set('max', e.currentTarget.value)} />
          </div>
          <p className="m-0 text-xs text-muted">{t('st.delivery.distanceHelp')}</p>
          {value.bands.length ? (
            <table className="w-full border-collapse" aria-label={t('st.delivery.bandsOf', { name })}>
              <thead>
                <tr className="border-b border-border">
                  <th className={th}>{t('st.delivery.bandFrom')}</th>
                  <th className={th}>{t('st.delivery.bandTo')}</th>
                  <th className={th}>{t('st.delivery.bandFee')}</th>
                  <th className={th}>
                    <span className="at-visually-hidden">{t('st.delivery.remove')}</span>
                  </th>
                </tr>
              </thead>
              <tbody>
                {value.bands.map((b, i) => {
                  const band = (k: 'from' | 'to' | 'fee', v: string) => set('bands', value.bands.map((x) => (x.rowId === b.rowId ? { ...x, [k]: v } : x)));
                  return (
                    <tr key={b.rowId} className="border-b border-border">
                      <td className={td}>
                        <TextField id={id(`bands.${i}.fromMiles`)} inputMode="decimal" label={<span className="at-visually-hidden">{t('st.delivery.bandFromN', { n: i + 1 })}</span>} value={b.from} error={err(`bands.${i}.fromMiles`)} onChange={(e) => band('from', e.currentTarget.value)} />
                      </td>
                      <td className={td}>
                        <TextField id={id(`bands.${i}.toMiles`)} inputMode="decimal" label={<span className="at-visually-hidden">{t('st.delivery.bandToN', { n: i + 1 })}</span>} value={b.to} error={err(`bands.${i}.toMiles`)} onChange={(e) => band('to', e.currentTarget.value)} />
                      </td>
                      <td className={td}>
                        <TextField id={id(`bands.${i}.feeMinor`)} inputMode="decimal" label={<span className="at-visually-hidden">{t('st.delivery.bandFeeN', { n: i + 1 })}</span>} value={b.fee} error={err(`bands.${i}.feeMinor`)} onChange={(e) => band('fee', e.currentTarget.value)} />
                      </td>
                      <td className={td}>
                        <Button variant="neutral" size="sm" aria-label={t('st.delivery.removeBand', { n: i + 1 })} onClick={() => set('bands', value.bands.filter((x) => x.rowId !== b.rowId))}>
                          <Trash size={16} aria-hidden />
                        </Button>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          ) : null}
          <div>
            <Button
              variant="neutral"
              size="sm"
              onClick={() => {
                const last = value.bands.at(-1);
                set('bands', [...value.bands, { rowId: rowId(), from: last?.to ?? '0', to: '', fee: '' }]);
              }}
            >
              <Plus size={16} aria-hidden /> {t('st.delivery.addBand')}
            </Button>
          </div>
        </>
      )}
    </fieldset>
  );
}

function QuoteLine({ label, q }: { label: string; q: QuotePreview }) {
  const t = useT();
  return (
    <div className="flex flex-col gap-1 rounded-md border border-border p-3">
      <span className="text-xs font-semibold text-muted">{label}</span>
      {q.available ? (
        <span className="text-sm">
          {t('st.delivery.quote.customer')} <strong>{money(q.customerMinor)}</strong> · {t('st.delivery.quote.seller')} <strong>{money(q.sellerMinor)}</strong>{' '}
          <span className="text-xs text-muted">({t(`st.delivery.source.${q.source}`)})</span>
        </span>
      ) : (
        <span className="text-sm">{q.reason === 'BEYOND_MAX_RADIUS' ? t('st.delivery.quote.beyond') : t('st.delivery.quote.notOffered')}</span>
      )}
    </div>
  );
}

/** Enter miles and packages to see what the shopper and the seller pay: under the draft, the version in force and (on demand) payments' live quote. */
function QuotePreviewCard({ overview, form }: { overview: FeesOverview; form: DeliveryForm }) {
  const t = useT();
  const [milesText, setMilesText] = useState('7.4');
  const [packagesText, setPackagesText] = useState('1');
  const [orgId, setOrgId] = useState('');
  const [live, setLive] = useState(false);
  const miles = parseMiles(milesText);
  const packages = /^\d{1,2}$/.test(packagesText.trim()) ? Math.max(1, Number(packagesText)) : null;
  const draft = useMemo(() => {
    const ignore: Record<string, string> = {};
    return form.include ? (toDeliveryDraft(form, ignore, t) ?? null) : (overview.base?.deliveryPricing ?? null);
  }, [form, overview.base, t]);
  const current = overview.current?.deliveryPricing ?? null;
  const orgs = new Map<string, string>([['', t('st.delivery.quote.anySeller')], [overview.retail.orgId, overview.retail.name]]);
  form.overrides.forEach((o) => orgs.set(o.orgId, o.orgName));
  current?.sellerOverrides.forEach((o) => orgs.set(o.orgId, o.orgName ?? o.orgId));
  const liveQuote = useQuery({
    ...staffQueries.deliveryQuote(staffApi(), orgId || 'quote-preview', miles ?? 0, packages ?? 1),
    enabled: live && miles !== null && packages !== null
  });
  return (
    <Card title={t('st.delivery.quote.title')}>
      <div className="grid grid-cols-[repeat(auto-fit,minmax(min(100%,180px),1fr))] gap-3">
        <TextField id="dp-quote-miles" inputMode="decimal" label={t('st.delivery.quote.miles')} value={milesText} error={miles === null ? t('st.delivery.err.miles') : null} onChange={(e) => { setMilesText(e.currentTarget.value); setLive(false); }} />
        <TextField id="dp-quote-packages" inputMode="numeric" label={t('st.delivery.quote.packages')} value={packagesText} error={packages === null ? t('st.delivery.err.packages') : null} onChange={(e) => { setPackagesText(e.currentTarget.value); setLive(false); }} />
        <Select
          id="dp-quote-org"
          label={t('st.delivery.quote.seller.label')}
          value={orgId}
          options={[...orgs.entries()].map(([value, label]) => ({ value, label }))}
          onChange={(e) => { setOrgId(e.currentTarget.value); setLive(false); }}
        />
      </div>
      {miles !== null && packages !== null ? (
        <div className="grid grid-cols-[repeat(auto-fit,minmax(min(100%,260px),1fr))] gap-3" aria-live="polite" data-testid="delivery-quote-preview">
          <QuoteLine label={t('st.delivery.quote.new')} q={previewQuote(draft, orgId, miles, packages, overview.retail.orgId)} />
          <QuoteLine label={t('st.delivery.quote.now', { v: overview.current?.version ?? '—' })} q={previewQuote(current, orgId, miles, packages, overview.retail.orgId)} />
        </div>
      ) : null}
      <div className="flex flex-wrap items-center gap-2">
        <Button size="sm" variant="neutral" disabled={miles === null || packages === null} onClick={() => setLive(true)}>
          {t('st.delivery.quote.live')}
        </Button>
        {live && liveQuote.isFetching ? <span className="text-xs text-muted">{t('st.delivery.quote.loading')}</span> : null}
        {live && liveQuote.isError ? <span className="text-sm text-danger">{problemMessage(liveQuote.error)}</span> : null}
        {live && liveQuote.data ? (
          <span className="text-sm" data-testid="delivery-live-quote">
            {liveQuote.data.available
              ? t('st.delivery.quote.liveResult', { v: liveQuote.data.scheduleVersion ?? '—', customer: money(liveQuote.data.customerChargeMinor), seller: money(liveQuote.data.sellerFeeMinor) })
              : liveQuote.data.unavailableReason === 'BEYOND_MAX_RADIUS'
                ? t('st.delivery.quote.beyond')
                : t('st.delivery.quote.notOffered')}
          </span>
        ) : null}
      </div>
    </Card>
  );
}

/** Platform configuration → Fees → new version: AfroTransact Delivery from the seller's store (S255). */
export function DeliveryPricingEditor({ overview, form, onChange, errors }: { overview: FeesOverview; form: DeliveryForm; onChange: (f: DeliveryForm) => void; errors: Record<string, string> }) {
  const t = useT();
  const set = <K extends keyof DeliveryForm>(k: K, v: DeliveryForm[K]) => onChange({ ...form, [k]: v });
  const baseVersion = overview.base?.version ?? '—';
  const hasBase = !!overview.base?.deliveryPricing;
  return (
    <>
      <Card title={t('st.delivery.title')}>
        <p className="m-0 text-sm text-muted">{t('st.delivery.intro')}</p>
        <Checkbox
          id="dp-include"
          checked={form.include}
          onChange={(e) => set('include', e.currentTarget.checked)}
          label={t('st.delivery.include')}
          description={hasBase ? t('st.delivery.carriedOver', { v: baseVersion }) : t('st.delivery.noneYet')}
        />
        {form.include ? (
          <>
            <div className="grid grid-cols-[repeat(auto-fit,minmax(min(100%,240px),1fr))] gap-3">
              <Checkbox id="dp-enabled" className="self-center" checked={form.enabled} onChange={(e) => set('enabled', e.currentTarget.checked)} label={t('st.delivery.enabled')} description={t('st.delivery.enabledHelp')} />
              <TextField
                id={deliveryFieldId('deliveryPricing.maxRadiusMiles')}
                inputMode="decimal"
                label={t('st.delivery.radius')}
                value={form.radius}
                error={errors['deliveryPricing.maxRadiusMiles']}
                help={t('st.delivery.radiusHelp', { max: overview.limits.deliveryMaxRadiusMiles })}
                onChange={(e) => set('radius', e.currentTarget.value)}
              />
            </div>
            <div className="grid grid-cols-[repeat(auto-fit,minmax(min(100%,360px),1fr))] gap-3">
              <TariffEditor name={t('st.delivery.customer')} prefix="deliveryPricing.customerCharge" value={form.customer} onChange={(v) => set('customer', v)} errors={errors} />
              <TariffEditor name={t('st.delivery.seller')} prefix="deliveryPricing.sellerFee" value={form.seller} onChange={(v) => set('seller', v)} errors={errors} />
            </div>
            <h3 className="m-0 text-md font-semibold">{t('st.delivery.overrides')}</h3>
            <p className="m-0 text-sm text-muted">{t('st.delivery.overridesHelp', { retail: overview.retail.name })}</p>
            {form.overrides.length ? (
              <ul aria-label={t('st.delivery.overrides')} className="m-0 flex list-none flex-col gap-3 p-0">
                {form.overrides.map((o, i) => (
                  <li key={o.rowId} className="flex flex-col gap-2">
                    <div className="flex flex-wrap items-center gap-2" id={deliveryFieldId(`deliveryPricing.sellerOverrides.${i}.orgId`)} tabIndex={-1}>
                      <strong>{o.orgName}</strong>
                      {errors[`deliveryPricing.sellerOverrides.${i}.orgId`] ? <span className="text-xs text-danger">{errors[`deliveryPricing.sellerOverrides.${i}.orgId`]}</span> : null}
                      <Button variant="neutral" size="sm" aria-label={t('st.delivery.removeOverride', { seller: o.orgName })} onClick={() => set('overrides', form.overrides.filter((x) => x.rowId !== o.rowId))}>
                        <Trash size={16} aria-hidden />
                      </Button>
                    </div>
                    <TariffEditor
                      name={t('st.delivery.overrideFee', { seller: o.orgName })}
                      prefix={`deliveryPricing.sellerOverrides.${i}.sellerFee`}
                      value={o.tariff}
                      errors={errors}
                      onChange={(v) => set('overrides', form.overrides.map((x) => (x.rowId === o.rowId ? { ...x, tariff: v } : x)))}
                    />
                  </li>
                ))}
              </ul>
            ) : (
              <p className="m-0 text-sm text-muted">{t('st.delivery.noOverrides')}</p>
            )}
            <OrgSearch
              overview={overview}
              label={t('st.delivery.findSeller')}
              takenNote={t('st.delivery.hasOverride')}
              taken={(id) => form.overrides.some((o) => o.orgId === id)}
              onPick={(h) => {
                if (form.overrides.some((o) => o.orgId === h.orgId)) return;
                set('overrides', [...form.overrides, { rowId: rowId(), orgId: h.orgId, orgName: h.name, tariff: { ...form.seller, bands: form.seller.bands.map((b) => ({ ...b, rowId: rowId() })) } }]);
              }}
            />
          </>
        ) : (
          <Notice tone="info" title={t('st.delivery.notChangedTitle')}>
            {hasBase ? t('st.delivery.notChanged', { v: baseVersion }) : t('st.delivery.notConfigured')}
          </Notice>
        )}
      </Card>
      <QuotePreviewCard overview={overview} form={form} />
    </>
  );
}
