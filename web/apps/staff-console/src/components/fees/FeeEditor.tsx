'use client';

import { type FeeScheduleDraft, type FeesOverview, formatBps, isApiError, staffKeys } from '@afrotransact/api-client';
import { Button, Checkbox, Field, fieldStyles, Select, TextField, useToast } from '@afrotransact/ui';
import { Plus, Trash } from '@phosphor-icons/react/ssr';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { type FormEvent, useMemo, useRef, useState } from 'react';

import { Card } from '@/components/ui/Card';
import { FormErrors } from '@/components/ui/FormErrors';
import { Notice } from '@/components/ui/Notice';
import { problemMessage } from '@/components/ui/States';
import { useT } from '@/i18n/t';
import { staffApi } from '@/lib/api';
import { dateTime, idempotencyKey, money } from '@/lib/format';

import { previewQuote } from './delivery-model';
import { deliveryFieldId, DeliveryPricingEditor } from './DeliveryPricingEditor';
import { affectedSellers, categoryLabel, diffDraft, type FeeForm, formFrom, impactRows, localInput, planFbaError, rowId, toDraft } from './model';
import { OrgSearch } from './OrgSearch';

const th = 'px-3 py-2 text-left text-xs font-semibold text-muted';
const td = 'px-3 py-2 align-top text-sm';

/**
 * New fee-schedule version: starts as a copy of the latest version, edits everything as percentages / dollars, then
 * shows the diff, a $100 impact preview and the sellers affected before publishing.
 */
export function FeeEditor({ overview, onClose }: { overview: FeesOverview; onClose: () => void }) {
  const qc = useQueryClient();
  const toast = useToast();
  const t = useT();
  const now = useMemo(() => new Date(), []);
  const [form, setForm] = useState<FeeForm>(() => formFrom(overview.base, overview, now));
  const [step, setStep] = useState<'edit' | 'review'>('edit');
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [submits, setSubmits] = useState(0);
  const [draft, setDraft] = useState<FeeScheduleDraft | null>(null);
  const [confirmImmediate, setConfirmImmediate] = useState(false);
  const key = useRef(idempotencyKey());
  const names = useRef(new Map(overview.base?.overrides.map((o) => [o.orgId, o.orgName ?? o.orgId]) ?? []));
  const orgName = (id: string) => names.current.get(id) ?? id;
  const cats = overview.categories;
  const set = <K extends keyof FeeForm>(k: K, v: FeeForm[K]) => setForm((f) => ({ ...f, [k]: v }));

  const publish = useMutation({
    mutationFn: (d: FeeScheduleDraft) => staffApi().createFeeSchedule(d, key.current),
    onSuccess: (v) => {
      key.current = idempotencyKey();
      toast.show(`Fee schedule v${v.version} published — effective ${dateTime(v.effectiveFrom)}`);
      void qc.invalidateQueries({ queryKey: staffKeys.fees() });
      void qc.invalidateQueries({ queryKey: staffKeys.resource('fee-schedules') });
      onClose();
    },
    onError: (e) => {
      if (isApiError(e) && Object.keys(e.fieldErrors).length) {
        setErrors(e.fieldErrors);
        setStep('edit');
      }
    }
  });

  const review = (e: FormEvent) => {
    e.preventDefault();
    setSubmits((n) => n + 1);
    const r = toDraft(form, overview, new Date(), t);
    setErrors(r.errors);
    if (r.draft) {
      setDraft(r.draft);
      setConfirmImmediate(false);
      setStep('review');
    }
  };

  const ids: Record<string, string> = { effectiveFrom: 'fee-effective', immediate: 'fee-immediate', defaultCommissionBps: 'fee-default', cardPctBps: 'fee-card-pct', cardFixedMinor: 'fee-card-fixed', fbaPerUnitMinor: 'fee-fba', note: 'fee-note' };
  form.categories.forEach((c, i) => {
    ids[`categoryRates.${i}.category`] = `fee-cat-${c.rowId}`;
    ids[`categoryRates.${i}.commissionBps`] = `fee-cat-rate-${c.rowId}`;
  });
  form.overrides.forEach((o, i) => {
    ids[`overrides.${i}.orgId`] = `fee-ovr-${o.rowId}`;
    ids[`overrides.${i}.category`] = `fee-ovr-cat-${o.rowId}`;
    ids[`overrides.${i}.commissionBps`] = `fee-ovr-rate-${o.rowId}`;
  });
  Object.keys(errors)
    .filter((k) => k.startsWith('deliveryPricing.'))
    .forEach((k) => (ids[k] = deliveryFieldId(k)));
  const categoryOptions = [{ value: '', label: 'All categories' }, ...cats.map((c) => ({ value: c.key, label: c.label }))];

  if (step === 'review' && draft) {
    const diff = diffDraft(overview.base, draft, cats, orgName, t);
    const impact = impactRows(overview.current, draft, cats, overview.exampleSaleMinor);
    const affected = affectedSellers(overview.base, draft, cats, orgName);
    const blocked = draft.immediate && !confirmImmediate;
    return (
      <div className="flex flex-col gap-4">
        <Card title="Review the new version">
          <p className="m-0 text-sm text-muted">
            {draft.immediate ? 'Takes effect immediately for orders placed from now.' : `Takes effect ${dateTime(draft.effectiveFrom)} for orders placed from then.`} Compared with v{overview.base?.version ?? '—'}. Versions are
            immutable: to undo, publish another version or cancel it before it takes effect.
          </p>
          <h3 className="m-0 text-md font-semibold">Changes</h3>
          {diff.length ? (
            <table className="w-full border-collapse" aria-label="Changes">
              <thead>
                <tr className="border-b border-border">
                  <th className={th}>What</th>
                  <th className={th}>Now</th>
                  <th className={th}>New</th>
                </tr>
              </thead>
              <tbody>
                {diff.map((r) => (
                  <tr key={r.key} className="border-b border-border">
                    <td className={td}>
                      {r.label} {r.kind !== 'changed' ? <span className="text-xs text-muted">({r.kind})</span> : null}
                    </td>
                    <td className={td}>{r.from}</td>
                    <td className={`${td} font-semibold`}>{r.to}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          ) : (
            <Notice tone="warning" title="Nothing changes.">
              The new version has the same rates and fees as v{overview.base?.version}.
            </Notice>
          )}
        </Card>
        <Card title={`Impact on a ${money(overview.exampleSaleMinor)} sale`}>
          <table className="w-full border-collapse" aria-label="Commission impact">
            <thead>
              <tr className="border-b border-border">
                <th className={th}>Category</th>
                <th className={th}>Commission now</th>
                <th className={th}>New commission</th>
                <th className={th}>Difference</th>
              </tr>
            </thead>
            <tbody>
              {impact.map((r) => (
                <tr key={r.category || 'other'} className="border-b border-border">
                  <td className={td}>{r.label}</td>
                  <td className={td}>
                    {money(r.beforeMinor)} <span className="text-xs text-muted">({formatBps(r.beforeBps)})</span>
                  </td>
                  <td className={td}>
                    {money(r.afterMinor)} <span className="text-xs text-muted">({formatBps(r.afterBps)})</span>
                  </td>
                  <td className={`${td} ${r.afterMinor === r.beforeMinor ? 'text-muted' : 'font-semibold'}`}>
                    {r.afterMinor === r.beforeMinor ? 'No change' : `${r.afterMinor > r.beforeMinor ? '+' : '−'}${money(Math.abs(r.afterMinor - r.beforeMinor))}`}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          <p className="m-0 text-xs text-muted">
            Before card fees ({formatBps(draft.cardPctBps)} + {money(draft.cardFixedMinor)} per charge) and FBA fees. {overview.retail.name}: {overview.retail.note.toLowerCase()}.
          </p>
        </Card>
        <Card title={t('st.delivery.title')}>
          {draft.deliveryPricing ? (
            <table className="w-full border-collapse" aria-label={t('st.delivery.review.examples')} data-testid="delivery-review">
              <thead>
                <tr className="border-b border-border">
                  <th className={th}>{t('st.delivery.review.trip')}</th>
                  <th className={th}>{t('st.delivery.review.customerNow')}</th>
                  <th className={th}>{t('st.delivery.review.customerNew')}</th>
                  <th className={th}>{t('st.delivery.review.sellerNow')}</th>
                  <th className={th}>{t('st.delivery.review.sellerNew')}</th>
                </tr>
              </thead>
              <tbody>
                {([3, 10, 20] as const).map((miles) => {
                  const show = (q: ReturnType<typeof previewQuote>, which: 'customerMinor' | 'sellerMinor') =>
                    q.available ? money(q[which]) : q.reason === 'BEYOND_MAX_RADIUS' ? t('st.delivery.review.beyond') : t('st.delivery.notOffered');
                  const now = previewQuote(overview.current?.deliveryPricing ?? null, '', miles, 1, overview.retail.orgId);
                  const next = previewQuote(draft.deliveryPricing ?? null, '', miles, 1, overview.retail.orgId);
                  return (
                    <tr key={miles} className="border-b border-border">
                      <td className={td}>{t('st.delivery.miles', { n: miles })}</td>
                      <td className={td}>{show(now, 'customerMinor')}</td>
                      <td className={`${td} font-semibold`}>{show(next, 'customerMinor')}</td>
                      <td className={td}>{show(now, 'sellerMinor')}</td>
                      <td className={`${td} font-semibold`}>{show(next, 'sellerMinor')}</td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          ) : (
            <p className="m-0 text-sm text-muted" data-testid="delivery-review">
              {overview.base?.deliveryPricing ? t('st.delivery.notChanged', { v: overview.base.version }) : t('st.delivery.notConfigured')}
            </p>
          )}
        </Card>
        <Card title="Sellers affected by overrides">
          {affected.length ? (
            <ul aria-label="Sellers affected" className="m-0 flex list-none flex-col gap-2 p-0">
              {affected.map((s) => (
                <li key={s.orgId} className="text-sm">
                  <strong>{s.orgName}</strong>{' '}
                  {s.changes
                    .map((c) => `${c.category}: ${c.from === null ? 'new' : formatBps(c.from)} → ${c.to === null ? 'removed (standard rate)' : formatBps(c.to)}`)
                    .join(' · ')}
                </li>
              ))}
            </ul>
          ) : (
            <p className="m-0 text-sm text-muted">No seller-specific rate changes. Sellers with overrides keep them.</p>
          )}
        </Card>
        {draft.immediate ? (
          <Checkbox
            id="fee-confirm-immediate"
            checked={confirmImmediate}
            onChange={(e) => setConfirmImmediate(e.currentTarget.checked)}
            label="I understand this version applies to every order placed from now and can’t be cancelled once published."
          />
        ) : null}
        {publish.isError ? (
          <Notice tone="danger" title="Not published." role="alert">
            {problemMessage(publish.error)}
          </Notice>
        ) : null}
        <div className="flex flex-wrap justify-end gap-2">
          <Button variant="neutral" onClick={() => setStep('edit')}>
            Back to editing
          </Button>
          <Button disabled={blocked} loading={publish.isPending} onClick={() => publish.mutate(draft)}>
            Publish version
          </Button>
        </div>
      </div>
    );
  }

  return (
    <form noValidate onSubmit={review} className="flex flex-col gap-4" aria-label="New fee schedule version">
      <FormErrors errors={errors} ids={ids} submitCount={submits} />
      <Notice tone="info" title={`${overview.retail.name}:`}>
        {overview.retail.note}. It can’t have an override.
      </Notice>
      <Card title="When it takes effect">
        <div className="grid grid-cols-[repeat(auto-fit,minmax(min(100%,260px),1fr))] gap-3">
          <TextField
            id="fee-effective"
            type="datetime-local"
            label="Effective from (your local time)"
            disabled={form.immediate}
            min={localInput(new Date(Math.max(new Date(overview.minEffectiveFrom).getTime(), now.getTime())))}
            value={form.effectiveAt}
            onChange={(e) => set('effectiveAt', e.currentTarget.value)}
            error={errors.effectiveFrom}
            help={overview.immediateAllowed ? 'Must be in the future.' : `After the scheduled version (${dateTime(overview.minEffectiveFrom)}).`}
          />
          <Checkbox
            id="fee-immediate"
            className="self-center"
            checked={form.immediate}
            disabled={!overview.immediateAllowed}
            onChange={(e) => set('immediate', e.currentTarget.checked)}
            error={errors.immediate}
            label="Take effect immediately"
            description={overview.immediateAllowed ? '(asks for confirmation)' : '(not while a version is scheduled)'}
          />
        </div>
      </Card>
      <Card title="Commission">
        <TextField
          id="fee-default"
          className="max-w-xs"
          inputMode="decimal"
          label="Default commission (%)"
          value={form.defaultCommission}
          onChange={(e) => set('defaultCommission', e.currentTarget.value)}
          error={errors.defaultCommissionBps}
          help={`Categories without their own rate. 0.00–${formatBps(overview.limits.commissionMaxBps)}.`}
        />
        <datalist id="fee-category-options">
          {cats.map((c) => (
            <option key={c.key} value={c.key}>
              {c.label}
            </option>
          ))}
        </datalist>
        {!overview.taxonomyAvailable ? <p className="m-0 text-xs text-muted">The catalog taxonomy is unavailable right now; type category names.</p> : null}
        <table className="w-full border-collapse" aria-label="Commission by category">
          <thead>
            <tr className="border-b border-border">
              <th className={th}>Category</th>
              <th className={th}>Commission (%)</th>
              <th className={th}>
                <span className="at-visually-hidden">Remove</span>
              </th>
            </tr>
          </thead>
          <tbody>
            {form.categories.map((c, i) => (
              <tr key={c.rowId} className="border-b border-border">
                <td className={td}>
                  <Field id={`fee-cat-${c.rowId}`} label={<span className="at-visually-hidden">Category {i + 1}</span>} error={errors[`categoryRates.${i}.category`]}>
                    {(a11y) => (
                      <input
                        {...a11y}
                        list="fee-category-options"
                        className={fieldStyles.control}
                        value={c.category}
                        placeholder="e.g. fashion"
                        onChange={(e) => {
                          const v = e.currentTarget.value;
                          set('categories', form.categories.map((x) => (x.rowId === c.rowId ? { ...x, category: v } : x)));
                        }}
                      />
                    )}
                  </Field>
                  {c.category ? <span className="text-xs text-muted">{categoryLabel(c.category, cats)}</span> : null}
                </td>
                <td className={td}>
                  <TextField
                    id={`fee-cat-rate-${c.rowId}`}
                    inputMode="decimal"
                    label={<span className="at-visually-hidden">Commission for {c.category || `category ${i + 1}`} (%)</span>}
                    value={c.rate}
                    onChange={(e) => {
                      const v = e.currentTarget.value;
                      set('categories', form.categories.map((x) => (x.rowId === c.rowId ? { ...x, rate: v } : x)));
                    }}
                    error={errors[`categoryRates.${i}.commissionBps`]}
                  />
                </td>
                <td className={td}>
                  <Button variant="neutral" size="sm" aria-label={`Remove ${c.category || `category ${i + 1}`}`} onClick={() => set('categories', form.categories.filter((x) => x.rowId !== c.rowId))}>
                    <Trash size={16} aria-hidden />
                  </Button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
        <div>
          <Button variant="neutral" size="sm" onClick={() => set('categories', [...form.categories, { rowId: rowId(), category: '', rate: form.defaultCommission }])}>
            <Plus size={16} aria-hidden /> Add category
          </Button>
        </div>
      </Card>
      <Card title="Seller overrides">
        <p className="m-0 text-sm text-muted">A negotiated rate for one organisation, in one category or all of them. Category overrides win over all-category ones.</p>
        {form.overrides.length ? (
          <table className="w-full border-collapse" aria-label="Seller overrides">
            <thead>
              <tr className="border-b border-border">
                <th className={th}>Organisation</th>
                <th className={th}>Category</th>
                <th className={th}>Commission (%)</th>
                <th className={th}>
                  <span className="at-visually-hidden">Remove</span>
                </th>
              </tr>
            </thead>
            <tbody>
              {form.overrides.map((o, i) => (
                <tr key={o.rowId} className="border-b border-border">
                  <td className={td} id={`fee-ovr-${o.rowId}`} tabIndex={-1}>
                    <strong>{o.orgName}</strong>
                    {errors[`overrides.${i}.orgId`] ? <p className="m-0 text-xs text-danger">{errors[`overrides.${i}.orgId`]}</p> : null}
                  </td>
                  <td className={td}>
                    <Select
                      id={`fee-ovr-cat-${o.rowId}`}
                      label={<span className="at-visually-hidden">Category for {o.orgName}</span>}
                      value={o.category}
                      options={categoryOptions}
                      error={errors[`overrides.${i}.category`]}
                      onChange={(e) => {
                        const v = e.currentTarget.value;
                        set('overrides', form.overrides.map((x) => (x.rowId === o.rowId ? { ...x, category: v } : x)));
                      }}
                    />
                  </td>
                  <td className={td}>
                    <TextField
                      id={`fee-ovr-rate-${o.rowId}`}
                      inputMode="decimal"
                      label={<span className="at-visually-hidden">Commission for {o.orgName} (%)</span>}
                      value={o.rate}
                      error={errors[`overrides.${i}.commissionBps`]}
                      onChange={(e) => {
                        const v = e.currentTarget.value;
                        set('overrides', form.overrides.map((x) => (x.rowId === o.rowId ? { ...x, rate: v } : x)));
                      }}
                    />
                  </td>
                  <td className={td}>
                    <Button variant="neutral" size="sm" aria-label={`Remove the override for ${o.orgName}`} onClick={() => set('overrides', form.overrides.filter((x) => x.rowId !== o.rowId))}>
                      <Trash size={16} aria-hidden />
                    </Button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        ) : (
          <p className="m-0 text-sm text-muted">No overrides: every seller pays the category rates.</p>
        )}
        <OrgSearch
          overview={overview}
          taken={(id) => form.overrides.some((o) => o.orgId === id)}
          onPick={(h) => {
            names.current.set(h.orgId, h.name);
            set('overrides', [...form.overrides, { rowId: rowId(), orgId: h.orgId, orgName: h.name, category: '', rate: form.defaultCommission }]);
          }}
        />
      </Card>
      <Card title="Fees">
        <div className="grid grid-cols-[repeat(auto-fit,minmax(min(100%,200px),1fr))] gap-3">
          <TextField id="fee-card-pct" inputMode="decimal" label="Card fee estimate (%)" value={form.cardPct} onChange={(e) => set('cardPct', e.currentTarget.value)} error={errors.cardPctBps} help={`Per charge, 0.00–${formatBps(overview.limits.cardPctMaxBps)}`} />
          <TextField id="fee-card-fixed" inputMode="decimal" label="Card fee, fixed ($)" value={form.cardFixed} onChange={(e) => set('cardFixed', e.currentTarget.value)} error={errors.cardFixedMinor} help={`Per charge, up to ${money(overview.limits.cardFixedMaxMinor)}`} />
          <TextField id="fee-fba" inputMode="decimal" label="FBA fee per unit ($)" value={form.fbaPerUnit} onChange={(e) => set('fbaPerUnit', e.currentTarget.value)} error={errors.fbaPerUnitMinor} help={`Up to ${money(overview.limits.fbaPerUnitMaxMinor)}`} />
        </div>
        <div className="flex flex-col gap-2">
          <h3 className="m-0 text-sm font-semibold">FBA fee per unit, by plan</h3>
          <p className="m-0 text-sm text-muted">Leave a plan blank and it pays the default above. Applies to FBA lines of sellers on that plan.</p>
          <div className="grid grid-cols-[repeat(auto-fit,minmax(min(100%,200px),1fr))] gap-3">
            {overview.plans
              .filter((p) => p.kind !== 'PROVIDER')
              .map((p) => (
                <TextField
                  key={p.code}
                  id={`fee-fba-${p.code}`}
                  inputMode="decimal"
                  label={`${p.name} ($)`}
                  placeholder={form.fbaPerUnit}
                  value={form.planFba[p.code] ?? ''}
                  onChange={(e) => set('planFba', { ...form.planFba, [p.code]: e.currentTarget.value })}
                  error={planFbaError(form, errors, p.code)}
                />
              ))}
          </div>
        </div>
        <Checkbox id="fee-reverse" checked={form.reverseOnRefund} onChange={(e) => set('reverseOnRefund', e.currentTarget.checked)} label="Reverse commission on refunds" description={`— ${overview.reverseOnRefundHelp}`} />
      </Card>
      <DeliveryPricingEditor overview={overview} form={form.delivery} onChange={(d) => set('delivery', d)} errors={errors} />
      <Card title="Why">
        <Field id="fee-note" label="Note explaining the change (required)" error={errors.note}>
          {(a11y) => <textarea {...a11y} className={fieldStyles.control} rows={3} maxLength={overview.limits.noteMaxLength} value={form.note} onChange={(e) => set('note', e.currentTarget.value)} />}
        </Field>
      </Card>
      <div className="flex flex-wrap justify-end gap-2">
        <Button variant="neutral" onClick={onClose}>
          Discard
        </Button>
        <Button type="submit">Review changes</Button>
      </div>
    </form>
  );
}
