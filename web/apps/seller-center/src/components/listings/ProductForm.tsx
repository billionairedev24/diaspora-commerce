'use client';

import { type ApiError, isApiError, type ProductTypeSchema, sellerKeys, type SellerProductDetail, sellerQueries } from '@afrotransact/api-client';
import { Button, Checkbox, Field, fieldStyles, Select, TextField, useToast } from '@afrotransact/ui';
import { Sparkle } from '@phosphor-icons/react/ssr';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import Link from 'next/link';
import { useRouter, useSearchParams } from 'next/navigation';
import { type FocusEvent, type ReactNode, useCallback, useEffect, useMemo, useRef, useState } from 'react';

import { Notice } from '@/components/ui/Notice';
import { PageHeader } from '@/components/ui/PageHeader';
import { ErrorState, problemMessage, Spinner } from '@/components/ui/States';
import { type MessageKey, useT } from '@/i18n/t';
import { sellerApi } from '@/lib/api';
import { money, time } from '@/lib/format';
import { useSeller } from '@/lib/session';

import { AttributeField } from './form/AttributeField';
import { CategoryPicker } from './form/CategoryPicker';
import { ImagesField } from './form/ImagesField';
import { goToField, ReadinessPanel } from './form/ReadinessPanel';
import { VariantsSection } from './form/VariantsSection';
import {
  attributesIn,
  type CategoryLeaf,
  comboKey,
  combos,
  CONDITIONS,
  emptyForm,
  fieldId,
  fillSkus,
  type FormState,
  fromDetail,
  fromDraftPayload,
  MAX_DESCRIPTION,
  orderedProblems,
  priceMinor,
  type Problems,
  type SectionKey,
  sectionsFor,
  serverFieldId,
  toDraftPayload,
  toProductDraft,
  validateForm,
  withCategory,
  worthSaving
} from './form-model';

const AUTOSAVE_MS = 1500;

function Section({ id, title, hint, children }: { id: SectionKey; title: string; hint?: ReactNode; children: ReactNode }) {
  return (
    <section id={`pf-s-${id}`} aria-labelledby={`pf-h-${id}`} className="flex flex-col gap-3 rounded-md border border-border bg-surface p-4 sm:p-5" data-section={id}>
      <div className="flex flex-col gap-0.5">
        <h2 id={`pf-h-${id}`} className="m-0 text-lg font-semibold">{title}</h2>
        {hint ? <p className="m-0 text-sm text-muted">{hint}</p> : null}
      </div>
      {children}
    </section>
  );
}

/** One-step product form (S307): Add and Edit on one page. The category decides the product type, which decides the
    fields and the conditional sections; a sticky readiness panel lists what is missing; the form autosaves as a draft. */
export function ProductForm({ productId }: { productId?: string }) {
  const api = sellerApi();
  const t = useT();
  const qc = useQueryClient();
  const toast = useToast();
  const router = useRouter();
  const params = useSearchParams();
  const { orgId } = useSeller();
  const editing = Boolean(productId);
  const draftParam = params.get('draft') ?? '';

  const tree = useQuery(sellerQueries.categories(api));
  const ctx = useQuery(sellerQueries.listingContext(api, orgId));
  const shipFrom = useQuery(sellerQueries.shipFrom(api, orgId));
  const detail = useQuery(sellerQueries.product(api, orgId, productId ?? ''));
  const savedDraft = useQuery(sellerQueries.productDraft(api, orgId, draftParam));
  const drafts = useQuery({ ...sellerQueries.productDrafts(api, orgId), enabled: editing && !draftParam });

  const [s, setS] = useState<FormState>(emptyForm);
  const [ready, setReady] = useState(!editing && !draftParam);
  const typeCode = s.productType ?? detail.data?.productType ?? '';
  const typeQ = useQuery(sellerQueries.productType(api, typeCode));
  const type: ProductTypeSchema | undefined = typeQ.data;

  // Load the listing (edit) or the draft once, when what it needs has arrived.
  const loaded = useRef(false);
  useEffect(() => {
    if (loaded.current) return;
    if (draftParam && savedDraft.data) {
      loaded.current = true;
      draft.current = { id: savedDraft.data.id, version: savedDraft.data.version };
      setS(fromDraftPayload(savedDraft.data.payload));
      setReady(true);
    } else if (!draftParam && editing && detail.data && (typeQ.data || typeQ.isError)) {
      loaded.current = true;
      setS(fromDetail(detail.data, typeQ.data));
      setReady(true);
    }
  }, [draftParam, savedDraft.data, editing, detail.data, typeQ.data, typeQ.isError]);

  /* ── Change tracking, touched fields and server errors ── */
  const [touched, setTouched] = useState<Set<string>>(new Set());
  const [showAll, setShowAll] = useState(false);
  const [serverProblems, setServerProblems] = useState<Problems>({});
  const dirty = useRef(false);
  const [changes, setChanges] = useState(0);
  const update = useCallback((fn: (prev: FormState) => FormState) => {
    dirty.current = true;
    setServerProblems({});
    setChanges((c) => c + 1);
    setS(fn);
  }, []);
  const patch = (p: Partial<FormState>) => update((prev) => ({ ...prev, ...p }));
  const onBlur = (e: FocusEvent<HTMLFormElement>) => {
    const id = e.target.id;
    if (id && id.startsWith('pf-') && !touched.has(id)) setTouched((prev) => new Set(prev).add(id));
  };
  const shown = (id: string) => showAll || touched.has(id) || id in serverProblems;

  const live = editing && (detail.data?.status === 'LIVE' || detail.data?.status === 'PAUSED');
  const lockedCombos = useMemo(() => {
    const d = detail.data;
    if (!live || !d || !d.variationTheme) return new Set<string>();
    const th = type?.themes.find((x) => x.name === d.variationTheme);
    return new Set(d.variants.map((v) => comboKey((th?.dimensions ?? []).map((dim) => v.options[dim.name] ?? ''))));
  }, [live, detail.data, type]);
  const lockedSkus = live ? (detail.data?.variants.map((v) => v.sellerSku) ?? []) : [];
  const sections = sectionsFor(type, s);
  const problems: Problems = { ...validateForm(s, type, { lockedSkus }), ...serverProblems };
  const ordered = orderedProblems(problems, sections);
  const err = (id: string) => (shown(id) && problems[id] ? t(problems[id].key, problems[id].vars) : undefined);

  /* ── Autosave as a draft ── */
  const draft = useRef<{ id: string; version: number } | null>(null);
  const [saveState, setSaveState] = useState<{ kind: 'idle' | 'saving' | 'saved' | 'error' | 'conflict'; at?: string }>({ kind: 'idle' });
  const saveDraft = useCallback(async (): Promise<boolean> => {
    if (!worthSaving(s)) return false;
    setSaveState({ kind: 'saving' });
    const body = { title: s.title.trim().slice(0, 200), categoryPath: s.categoryPath || undefined, payload: toDraftPayload(s), ...(productId ? { productId } : {}) };
    try {
      const d = draft.current ? await api.saveProductDraft(draft.current.id, { ...body, version: draft.current.version }) : await api.createProductDraft(body);
      const first = !draft.current;
      draft.current = { id: d.id, version: d.version };
      dirty.current = false;
      setSaveState({ kind: 'saved', at: d.updatedAt });
      void qc.invalidateQueries({ queryKey: sellerKeys.productDrafts(orgId) });
      if (first && !productId) router.replace(`/listings/new?draft=${encodeURIComponent(d.id)}`, { scroll: false });
      return true;
    } catch (e) {
      setSaveState({ kind: isApiError(e) && e.status === 409 ? 'conflict' : 'error' });
      return false;
    }
  }, [s, api, productId, qc, orgId, router]);
  useEffect(() => {
    if (!ready || !dirty.current || changes === 0) return;
    const h = window.setTimeout(() => void saveDraft(), AUTOSAVE_MS);
    return () => window.clearTimeout(h);
  }, [changes, ready, saveDraft]);

  const explicitSave = useMutation({
    mutationFn: saveDraft,
    onSuccess: (ok) => {
      if (ok) toast.show(t('sc.pf.toast.draftSaved'));
    }
  });

  /* ── Submit / save changes ── */
  const submit = useMutation({
    mutationFn: async () => {
      const input = toProductDraft(s, type, draft.current?.id);
      if (productId) return api.updateProduct(productId, { ...input, version: detail.data?.version });
      return api.submitProduct(input);
    },
    onSuccess: (row) => {
      dirty.current = false;
      void qc.invalidateQueries({ queryKey: sellerKeys.listings(orgId) });
      void qc.invalidateQueries({ queryKey: sellerKeys.productDrafts(orgId) });
      toast.show(row.status === 'PENDING_CATEGORY_APPROVAL' ? t('sc.pf.toast.pendingCategory') : row.status === 'IN_REVIEW' ? t('sc.pf.toast.inReview') : t('sc.pf.toast.saved'));
      router.push('/listings');
    },
    onError: (e: Error) => {
      const fields = isApiError(e) ? (e as ApiError).fieldErrors : {};
      const mapped: Problems = {};
      Object.entries(fields).forEach(([field, message]) => {
        const id = serverFieldId(field);
        const section = (Object.entries(problems).find(([k]) => k === id)?.[1].section ?? 'basics') as SectionKey;
        mapped[id] = { key: 'sc.pf.err.server', vars: { message }, section, label: { text: field } };
      });
      if (!Object.keys(mapped).length) mapped[fieldId.title] = { key: 'sc.pf.err.server', vars: { message: problemMessage(e) }, section: 'basics', label: 'sc.pf.field.form' };
      setServerProblems(mapped);
      setShowAll(true);
    }
  });
  const primary = () => {
    setShowAll(true);
    const first = ordered[0];
    if (first) {
      goToField(first[0]);
      return;
    }
    submit.mutate();
  };

  /* ── AI listing writer ── */
  const [suggestion, setSuggestion] = useState<{ description: string; bullets: string[] } | null>(null);
  const writer = useMutation({
    mutationFn: () => api.writeListing({ productType: s.productType ?? 'custom', title: s.title.trim(), attributes: Object.fromEntries(Object.entries(s.attrs).filter(([, v]) => v.trim())) }),
    onSuccess: (d) => setSuggestion({ description: d.description, bullets: d.bullets }),
    onError: (e) => toast.show(problemMessage(e), 'error')
  });

  const restore = async (id: string) => {
    try {
      const saved = await api.getProductDraft(id);
      draft.current = { id: saved.id, version: saved.version };
      setS(fromDraftPayload(saved.payload));
      setSaveState({ kind: 'saved', at: saved.updatedAt });
    } catch (e) {
      toast.show(problemMessage(e), 'error');
    }
  };

  if (editing && detail.isError) return <ErrorState error={detail.error} onRetry={() => void detail.refetch()} />;
  if (draftParam && savedDraft.isError) return <ErrorState error={savedDraft.error} onRetry={() => void savedDraft.refetch()} />;
  if (!ready || !tree.data) return <Spinner label={t('sc.pf.loading')} />;

  const d: SellerProductDetail | undefined = detail.data;
  const restorable = editing && !draftParam ? drafts.data?.items.find((x) => x.productId === productId) : undefined;
  const moderated = ctx.data?.moderated ?? true;
  const primaryLabel = editing
    ? d?.status === 'REJECTED' ? t('sc.pf.actions.resubmit') : t('sc.pf.actions.saveChanges')
    : s.request ? t('sc.pf.actions.saveForCategory') : moderated ? t('sc.pf.actions.submit') : t('sc.pf.actions.publish');
  const saveLine =
    saveState.kind === 'saving' ? t('sc.pf.save.saving')
      : saveState.kind === 'saved' && saveState.at ? t('sc.pf.save.saved', { time: time(saveState.at) })
        : saveState.kind === 'error' ? t('sc.pf.save.error')
          : saveState.kind === 'conflict' ? t('sc.pf.save.conflict')
            : t('sc.pf.save.auto');
  const variantsList = s.hasVariants ? combos(type, s) : [];
  const prices = (s.hasVariants ? variantsList.map((c) => priceMinor(s.rows[comboKey(c)]?.price ?? '')) : [priceMinor(s.single.price)]).filter((n): n is number => n !== null);
  const main = s.images[0];
  const preview = (
    <div className="flex gap-3 border-t border-border pt-3" aria-label={t('sc.pf.preview')}>
      {main ? <img src={main.previewUrl} alt="" className="size-16 flex-none rounded-sm border border-border object-cover" /> : <div className="grid size-16 flex-none place-items-center rounded-sm bg-surface-muted text-2xs text-subtle">{t('sc.pf.noImage')}</div>}
      <div className="flex min-w-0 flex-col gap-0.5">
        <span className="line-clamp-2 text-sm leading-tight font-medium">{s.title || t('sc.pf.previewTitle')}</span>
        <strong className="text-sm">{prices.length ? money(Math.min(...prices)) : '$—'}{prices.length > 1 && Math.max(...prices) !== Math.min(...prices) ? ` – ${money(Math.max(...prices))}` : ''}</strong>
        {variantsList.length > 1 ? <span className="text-xs text-muted">{t('sc.pf.previewOptions', { count: variantsList.length })}</span> : null}
      </div>
    </div>
  );
  const brandDef = type?.attributes.find((a) => a.key === 'brand');
  const attrField = (key: SectionKey, section: 'DETAILS' | 'FOOD' | 'REGULATED') => (
    <div className="grid gap-x-3 sm:grid-cols-2" data-attrs={key}>
      {attributesIn(type, section).map((a) => (
        <AttributeField key={a.key} def={a} value={s.attrs[a.key] ?? ''} unit={s.units[a.key] ?? ''} error={err(fieldId.attr(a.key))}
          onChange={(v) => update((prev) => ({ ...prev, attrs: { ...prev.attrs, [a.key]: v } }))} onUnit={(u) => update((prev) => ({ ...prev, units: { ...prev.units, [a.key]: u } }))} />
      ))}
    </div>
  );
  const idLabel = type?.identifierRule === 'isbn' ? t('sc.pf.field.isbn') : t('sc.pf.field.gtin');
  const noGtin = (
    <Checkbox label={type?.identifierRule === 'isbn' ? t('sc.pf.noIsbn') : t('sc.pf.noGtin')} description={<span className="block text-xs text-muted">{t('sc.pf.noGtinHint')}</span>} checked={s.noGtin}
      disabled={type?.identifierRule === 'isbn'} onChange={(e) => patch({ noGtin: e.target.checked })} />
  );
  const defaultShipFrom = shipFrom.data?.find((a) => a.isDefault);

  return (
    <>
      <PageHeader
        title={editing ? t('sc.pf.titleEdit') : t('sc.pf.titleNew')}
        description={editing ? d?.title : t('sc.pf.descriptionNew')}
        actions={editing ? null : <Link href="/listings/bulk" className="text-sm">{t('sc.pf.bulkLink')}</Link>}
      />
      {d?.status === 'REJECTED' && d.moderationNote ? <Notice tone="danger" title={t('sc.pf.rejected')} role="alert">{d.moderationNote}</Notice> : null}
      {live ? <Notice tone="info" title={t('sc.pf.liveTitle')}>{t('sc.pf.liveBody')}</Notice> : null}
      {restorable && !draft.current ? (
        <Notice tone="warning" title={t('sc.pf.restoreTitle', { time: time(restorable.updatedAt) })}
          action={<Button size="sm" variant="neutral" onClick={() => void restore(restorable.id)}>{t('sc.pf.restore')}</Button>}>
          {t('sc.pf.restoreBody')}
        </Notice>
      ) : null}
      <div className="grid items-start gap-5 pb-28 lg:grid-cols-[minmax(0,1fr)_320px] lg:pb-0">
        <form className="flex min-w-0 flex-col gap-4" noValidate onBlur={onBlur} onSubmit={(e) => { e.preventDefault(); primary(); }} aria-label={t('sc.pf.formLabel')}>
          <Section id="category" title={t('sc.pf.s.category')} hint={editing ? undefined : t('sc.pf.s.categoryHint')}>
            <CategoryPicker tree={tree.data} s={s} locked={live} error={err(fieldId.category)}
              onChoose={(leaf: CategoryLeaf | null, request) => update((prev) => withCategory(prev, leaf, request ?? null))} />
          </Section>

          <Section id="basics" title={t('sc.pf.s.basics')}>
            <TextField id={fieldId.title} label={t('sc.pf.field.title')} help={t('sc.pf.titleHelp', { count: s.title.trim().length })} value={s.title} error={err(fieldId.title)} maxLength={200}
              onChange={(e) => patch({ title: e.target.value })} />
            <div className="grid gap-x-3 sm:grid-cols-2">
              <TextField id={fieldId.brand} label={brandDef && !brandDef.required ? t('sc.pf.optional', { label: brandDef.label }) : (brandDef?.label ?? t('sc.pf.field.brandOptional'))} value={s.attrs.brand ?? ''}
                error={err(fieldId.brand)} onChange={(e) => update((prev) => ({ ...prev, attrs: { ...prev.attrs, brand: e.target.value } }))} />
              <Select id="pf-condition" label={t('sc.pf.field.condition')} value={s.condition} options={CONDITIONS.map((c) => ({ value: c, label: t(`sc.pf.condition.${c}` as MessageKey) }))}
                onChange={(e) => patch({ condition: e.target.value as FormState['condition'] })} />
            </div>
            <Field id={fieldId.description} label={t('sc.pf.field.description')} error={err(fieldId.description)} help={t('sc.pf.descriptionHelp', { count: s.description.length, max: MAX_DESCRIPTION })}>
              {(a11y) => <textarea {...a11y} rows={5} className={`${fieldStyles.control} min-h-28 py-2`} value={s.description} onChange={(e) => patch({ description: e.target.value })} />}
            </Field>
            {ctx.data?.aiListingWriter.available ? (
              <div className="flex flex-col gap-2 rounded-sm bg-surface-muted p-3">
                <div className="flex flex-wrap items-center gap-2">
                  <Button size="sm" variant="neutral" loading={writer.isPending} disabled={s.title.trim().length < 3} onClick={() => writer.mutate()}>
                    <Sparkle size={16} aria-hidden /> {t('sc.pf.ai.write')}
                  </Button>
                  <span className="text-xs text-muted">{t('sc.pf.ai.hint')}</span>
                </div>
                {suggestion ? (
                  <div className="flex flex-col gap-2" data-testid="ai-suggestion">
                    <p className="m-0 text-sm">{suggestion.description}</p>
                    {suggestion.bullets.length ? <ul className="m-0 pl-5 text-sm">{suggestion.bullets.map((b) => <li key={b}>{b}</li>)}</ul> : null}
                    <div className="flex gap-2">
                      <Button size="sm" onClick={() => { patch({ description: [suggestion.description, ...suggestion.bullets.map((b) => `• ${b}`)].join('\n') }); setSuggestion(null); }}>{t('sc.pf.ai.use')}</Button>
                      <Button size="sm" variant="neutral" onClick={() => setSuggestion(null)}>{t('sc.pf.ai.discard')}</Button>
                    </div>
                  </div>
                ) : null}
              </div>
            ) : ctx.data?.aiListingWriter.reason === 'PLAN' ? (
              <p className="m-0 text-xs text-muted"><Sparkle size={14} aria-hidden /> {t('sc.pf.ai.plan')} <Link href="/billing">{t('sc.pf.ai.upgrade')}</Link></p>
            ) : null}
            <TextField id={fieldId.keywords} label={t('sc.pf.field.keywords')} help={t('sc.pf.keywordsHelp')} value={s.keywords} error={err(fieldId.keywords)} onChange={(e) => patch({ keywords: e.target.value })} />
          </Section>

          <Section id="images" title={t('sc.pf.s.images')} hint={t('sc.pf.s.imagesHint')}>
            <ImagesField images={s.images} update={(fn) => update((prev) => ({ ...prev, images: fn(prev.images) }))} error={err(fieldId.images)} />
          </Section>

          {sections.includes('details') ? <Section id="details" title={t('sc.pf.s.details', { type: type?.label ?? '' })}>{attrField('details', 'DETAILS')}</Section> : null}
          {sections.includes('food') ? (
            <Section id="food" title={t('sc.pf.s.food')} hint={t('sc.pf.s.foodHint')}>{attrField('food', 'FOOD')}</Section>
          ) : null}
          {sections.includes('regulated') ? (
            <Section id="regulated" title={t('sc.pf.s.regulated')} hint={t('sc.pf.s.regulatedHint')}>{attrField('regulated', 'REGULATED')}</Section>
          ) : null}

          {type ? (
            <Section id={s.hasVariants && type.themes.length ? 'variants' : 'offer'} title={t('sc.pf.s.offer')}>
              {type.themes.length ? (
                <Checkbox label={t('sc.pf.hasVariants')} description={<span className="block text-xs text-muted">{t('sc.pf.hasVariantsHint')}</span>} checked={s.hasVariants} disabled={live}
                  onChange={(e) => patch({ hasVariants: e.target.checked })} />
              ) : null}
              {s.hasVariants && type.themes.length ? (
                <VariantsSection s={s} type={type} set={update} problems={problems} shown={shown} locked={lockedCombos} />
              ) : (
                <div className="grid gap-x-3 sm:grid-cols-3">
                  <TextField id={fieldId.price} label={t('sc.pf.field.priceUsd')} inputMode="decimal" value={s.single.price} error={err(fieldId.price)} onChange={(e) => patch({ single: { ...s.single, price: e.target.value } })} />
                  <TextField id={fieldId.compareAt} label={t('sc.pf.field.compareAt')} help={t('sc.pf.compareAtHelp')} inputMode="decimal" value={s.single.compareAt} error={err(fieldId.compareAt)} onChange={(e) => patch({ single: { ...s.single, compareAt: e.target.value } })} />
                  <TextField id={fieldId.qty} label={t('sc.pf.field.qty')} inputMode="numeric" value={s.single.qty} error={err(fieldId.qty)} help={s.fulfilment === 'AFROTRANSACT' ? t('sc.pf.qtyFba') : undefined} onChange={(e) => patch({ single: { ...s.single, qty: e.target.value } })} />
                  <div className="flex items-end gap-2 sm:col-span-1">
                    <TextField id={fieldId.sku} className="flex-1" label={t('sc.pf.field.sku')} value={s.single.sku} error={err(fieldId.sku)} readOnly={live} onChange={(e) => patch({ single: { ...s.single, sku: e.target.value } })} />
                  </div>
                  {s.noGtin ? null : (
                    <div className="sm:col-span-2">
                      <TextField id={fieldId.gtin} label={idLabel} inputMode="numeric" value={s.single.gtin} error={err(fieldId.gtin)} help={t('sc.pf.gtinHelp')} onChange={(e) => patch({ single: { ...s.single, gtin: e.target.value } })} />
                    </div>
                  )}
                  {!s.single.sku.trim() && s.title.trim() ? (
                    <button type="button" className="link-button self-start border-0 bg-transparent p-0 text-left text-xs text-link hover:underline sm:col-span-3" onClick={() => update((prev) => fillSkus(type, prev))}>
                      {t('sc.pf.suggestSku')}
                    </button>
                  ) : null}
                </div>
              )}
              {noGtin}
            </Section>
          ) : null}

          {type ? (
            <Section id="fulfilment" title={t('sc.pf.s.fulfilment')}>
              <div role="radiogroup" aria-label={t('sc.pf.s.fulfilment')} className="grid gap-2 sm:grid-cols-2">
                {(['SELLER', 'AFROTRANSACT'] as const).map((f) => (
                  <label key={f} aria-label={t(`sc.pf.fulfilment.${f}` as MessageKey)} className={`flex cursor-pointer gap-2 rounded-sm border p-3 ${s.fulfilment === f ? 'border-gold-500 bg-gold-50' : 'border-border-strong bg-surface'}`}>
                    <input type="radio" name="pf-fulfilment" value={f} checked={s.fulfilment === f} onChange={() => patch({ fulfilment: f })} className="mt-1" />
                    <span className="flex flex-col">
                      <strong className="text-sm">{t(`sc.pf.fulfilment.${f}` as MessageKey)}</strong>
                      <span className="text-xs text-muted">{t(`sc.pf.fulfilment.${f}.hint` as MessageKey)}</span>
                    </span>
                  </label>
                ))}
              </div>
              <fieldset id={fieldId.pkg} tabIndex={-1} className="m-0 flex flex-col gap-1 border-0 p-0 outline-none">
                <legend className="mb-1 text-sm font-semibold">{s.fulfilment === 'AFROTRANSACT' ? t('sc.pf.field.package') : t('sc.pf.packageOptional')}</legend>
                <div className="grid grid-cols-2 gap-x-3 sm:grid-cols-4">
                  <TextField id="pf-pkg-length" label={t('sc.pf.pkg.length')} inputMode="decimal" value={s.pkg.length} onChange={(e) => patch({ pkg: { ...s.pkg, length: e.target.value } })} />
                  <TextField id="pf-pkg-width" label={t('sc.pf.pkg.width')} inputMode="decimal" value={s.pkg.width} onChange={(e) => patch({ pkg: { ...s.pkg, width: e.target.value } })} />
                  <TextField id="pf-pkg-height" label={t('sc.pf.pkg.height')} inputMode="decimal" value={s.pkg.height} onChange={(e) => patch({ pkg: { ...s.pkg, height: e.target.value } })} />
                  <TextField id="pf-pkg-weight" label={t('sc.pf.pkg.weight')} inputMode="decimal" value={s.pkg.weight} onChange={(e) => patch({ pkg: { ...s.pkg, weight: e.target.value } })} />
                </div>
                {err(fieldId.pkg) ? <span role="alert" className="text-xs text-danger">{err(fieldId.pkg)}</span> : null}
              </fieldset>
            </Section>
          ) : null}

          {sections.includes('shipping') ? (
            <Section id="shipping" title={t('sc.pf.s.shipping')}>
              <div className="grid gap-x-3 sm:grid-cols-2">
                <Select id={fieldId.shipFrom} label={t('sc.pf.field.shipFrom')} value={s.shipFromId} error={err(fieldId.shipFrom)}
                  help={shipFrom.data?.length ? undefined : t('sc.pf.shipFromNone')}
                  options={[{ value: '', label: defaultShipFrom ? t('sc.pf.shipFromDefault', { label: `${defaultShipFrom.label} · ${defaultShipFrom.city}, ${defaultShipFrom.state}` }) : t('sc.pf.shipFromAccount') },
                    ...(shipFrom.data ?? []).filter((a) => !a.isDefault).map((a) => ({ value: a.id, label: `${a.label} · ${a.city}, ${a.state}` }))]}
                  onChange={(e) => patch({ shipFromId: e.target.value })} />
                <TextField id={fieldId.handling} label={t('sc.pf.field.handling')} help={t('sc.pf.handlingHelp')} inputMode="numeric" value={s.handlingDays} error={err(fieldId.handling)}
                  onChange={(e) => patch({ handlingDays: e.target.value })} />
              </div>
            </Section>
          ) : null}

          {sections.includes('preorder') ? (
            <Section id="preorder" title={t('sc.pf.s.preorder')}>
              <Checkbox label={t('sc.pf.preorder')} description={<span className="block text-xs text-muted">{t('sc.pf.preorderHint')}</span>} checked={s.preorder} onChange={(e) => patch({ preorder: e.target.checked })} />
            </Section>
          ) : null}
          <button type="submit" className="at-visually-hidden" tabIndex={-1}>{primaryLabel}</button>
        </form>
        <ReadinessPanel
          problems={ordered}
          saveState={saveLine}
          preview={preview}
          primaryLabel={primaryLabel}
          onPrimary={primary}
          onSaveDraft={() => explicitSave.mutate()}
          busy={submit.isPending}
          savingDraft={explicitSave.isPending}
        />
      </div>
    </>
  );
}
