'use client';

import { type ApiError, type AttributeDef, isApiError, type ListingRow, type ProductTypeSchema, sellerKeys, type SellerProductDetail, sellerQueries } from '@afrotransact/api-client';
import { Button, Checkbox, ChipGroup, Select, TextField, useToast } from '@afrotransact/ui';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import Link from 'next/link';
import { useRouter, useSearchParams } from 'next/navigation';
import { type FocusEvent, useCallback, useEffect, useMemo, useRef, useState } from 'react';

import { Notice } from '@/components/ui/Notice';
import { PageHeader } from '@/components/ui/PageHeader';
import { ErrorState, problemMessage, Spinner } from '@/components/ui/States';
import { type MessageKey, useT } from '@/i18n/t';
import { sellerApi } from '@/lib/api';
import { money, time } from '@/lib/format';
import { useSeller } from '@/lib/session';

import { ActionBar } from './form/ActionBar';
import { AttributeField } from './form/AttributeField';
import { BrandField } from './form/BrandField';
import { CategoryPicker } from './form/CategoryPicker';
import { DescriptionEditor } from './form/DescriptionEditor';
import { Disclosure } from './form/Disclosure';
import { FormSection, GRID, GRID_SHORT } from './form/FormSection';
import { ImagesField } from './form/ImagesField';
import { ListingPreview } from './form/ListingPreview';
import { goToField, ReadinessPanel } from './form/ReadinessPanel';
import { SectionNav } from './form/SectionNav';
import { VariantsSection } from './form/VariantsSection';
import {
  attributesIn,
  brandOf,
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
  orderedProblems,
  previewAttributes,
  priceMinor,
  type Problems,
  qualityItems,
  qualityScore,
  type SectionKey,
  sectionsFor,
  sectionStatus,
  serverFieldId,
  SIZE_UNITS,
  toDraftPayload,
  toProductDraft,
  validateForm,
  WEIGHT_UNITS,
  withCategory,
  worthSaving
} from './form-model';

const AUTOSAVE_MS = 1500;
const HANDLING_DAYS = [1, 2, 3, 4, 5, 7, 10, 14, 21, 30];

/** One-step product form (S307): Add and Edit on one page. The category decides the product type, which decides the
    fields and the conditional sections (a field the type does not define is not shown); a sticky section navigator shows
    each section's completion, a sticky quality panel lists what is missing and previews the listing, and the form autosaves as a draft. */
export function ProductForm({ productId }: { productId?: string }) {
  const api = sellerApi();
  const t = useT();
  const qc = useQueryClient();
  const toast = useToast();
  const router = useRouter();
  const params = useSearchParams();
  const { orgId, has } = useSeller();
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
  const changesRef = useRef(0);
  /** Edits already stored in the draft: Save draft is available only for edits after this. */
  const [savedChanges, setSavedChanges] = useState(0);
  const update = useCallback((fn: (prev: FormState) => FormState) => {
    dirty.current = true;
    changesRef.current += 1;
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
  const sections = sectionsFor(type);
  const problems: Problems = { ...validateForm(s, type, { lockedSkus }), ...serverProblems };
  const ordered = orderedProblems(problems, sections);
  const err = (id: string) => (shown(id) && problems[id] ? t(problems[id].key, problems[id].vars) : undefined);
  /** Jump to a field from the quality panel: its message shows (so the field is announced with it) and the field takes focus. */
  const jump = (id: string) => {
    setTouched((prev) => new Set(prev).add(id));
    goToField(id);
  };

  /* ── Autosave as a draft ── */
  const draft = useRef<{ id: string; version: number } | null>(null);
  const [saveState, setSaveState] = useState<{ kind: 'idle' | 'saving' | 'saved' | 'error' | 'conflict'; at?: string }>({ kind: 'idle' });
  const saveDraft = useCallback(async (): Promise<boolean> => {
    if (!worthSaving(s)) return false;
    setSaveState({ kind: 'saving' });
    const at = changesRef.current;
    const body = { title: s.title.trim().slice(0, 200), categoryPath: s.categoryPath || undefined, payload: toDraftPayload(s), ...(productId ? { productId } : {}) };
    try {
      const d = draft.current ? await api.saveProductDraft(draft.current.id, { ...body, version: draft.current.version }) : await api.createProductDraft(body);
      const first = !draft.current;
      draft.current = { id: d.id, version: d.version };
      dirty.current = at === changesRef.current ? false : dirty.current;
      setSavedChanges(at);
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
      qc.setQueriesData<{ items: ListingRow[]; total: number }>({ queryKey: sellerKeys.listings(orgId) }, (old) => {
        if (!old || !old.items) return old;
        return {
          ...old,
          items: old.items.map((r) => (r.productId === row.productId ? { ...r, ...row } : r))
        };
      });
      if (productId) {
        void qc.invalidateQueries({ queryKey: sellerKeys.product(orgId, productId) });
      }
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
      jump(first[0]);
      return;
    }
    submit.mutate();
  };

  const restore = async (id: string) => {
    try {
      const saved = await api.getProductDraft(id);
      draft.current = { id: saved.id, version: saved.version };
      setS(fromDraftPayload(saved.payload));
      setSavedChanges(changesRef.current);
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
  /** A form nobody changed cannot be saved: an edit needs a change (or a restored draft) before Save changes works. */
  const unchanged = editing && changes === 0 && !draft.current;
  const draftDirty = changes > savedChanges && worthSaving(s);
  const saveLine =
    saveState.kind === 'saving' ? t('sc.pf.save.saving')
      : saveState.kind === 'saved' && saveState.at ? t('sc.pf.save.saved', { time: time(saveState.at) })
        : saveState.kind === 'error' ? t('sc.pf.save.error')
          : saveState.kind === 'conflict' ? t('sc.pf.save.conflict')
            : unchanged ? t('sc.pf.bar.noChanges')
              : t('sc.pf.save.auto');
  const variantsList = s.hasVariants ? combos(type, s) : [];
  const prices = (s.hasVariants ? variantsList.map((c) => priceMinor(s.rows[comboKey(c)]?.price ?? '')) : [priceMinor(s.single.price)]).filter((n): n is number => n !== null);
  const priceText = prices.length ? `${money(Math.min(...prices))}${prices.length > 1 && Math.max(...prices) !== Math.min(...prices) ? ` – ${money(Math.max(...prices))}` : ''}` : '$—';
  const preview = (
    <ListingPreview title={s.title} imageUrl={s.images[0]?.previewUrl ?? null} price={priceText} options={variantsList.length} attributes={previewAttributes(type, s)}
      condition={s.condition === 'NEW' ? null : t(`sc.pf.condition.${s.condition}` as MessageKey)} />
  );
  const items = qualityItems(s, type, problems);
  const percent = qualityScore(items);
  const tips = items.filter((i) => !i.required && !i.done);
  const status = sectionStatus(sections, problems);
  const sectionTitle = (k: SectionKey): string => {
    switch (k) {
      case 'basics': return t('sc.pf.s.basics');
      case 'images': return t('sc.pf.s.images');
      case 'offer': return t('sc.pf.s.offer');
      case 'details': return t('sc.pf.s.details', { type: type?.label ?? '' });
      case 'variants': return t('sc.pf.s.variants');
      case 'fulfilment': return t('sc.pf.s.fulfilment');
      case 'compliance': return t('sc.pf.s.compliance');
    }
  };
  const navItems = sections.map((key) => ({ key, label: sectionTitle(key), status: status[key] }));
  const brandDef = brandOf(type);
  const attrInput = (a: AttributeDef) => (
    <AttributeField key={a.key} def={a} value={s.attrs[a.key] ?? ''} unit={s.units[a.key] ?? ''} error={err(fieldId.attr(a.key))}
      onChange={(v) => update((prev) => ({ ...prev, attrs: { ...prev.attrs, [a.key]: v } }))} onUnit={(u) => update((prev) => ({ ...prev, units: { ...prev.units, [a.key]: u } }))} />
  );
  /** Required attributes are open; the optional ones fold under "More details" unless one is filled in or has a problem. */
  const attrGroup = (group: 'DETAILS' | 'FOOD' | 'REGULATED') => {
    const all = attributesIn(type, group);
    const optional = all.filter((a) => !a.required);
    const force = optional.some((a) => (s.attrs[a.key] ?? '').trim() || err(fieldId.attr(a.key)));
    return (
      <div className="flex flex-col gap-4" data-attrs={group}>
        <div className={GRID}>{all.filter((a) => a.required).map(attrInput)}</div>
        {optional.length ? <Disclosure title={t('sc.pf.moreDetails', { count: optional.length })} force={force}><div className={GRID}>{optional.map(attrInput)}</div></Disclosure> : null}
      </div>
    );
  };
  const idLabel = type?.identifierRule === 'isbn' ? t('sc.pf.field.isbn') : t('sc.pf.field.gtin');
  const defaultShipFrom = shipFrom.data?.find((a) => a.isDefault);
  const pkgFilled = Object.values(s.pkg).some((v) => v.trim() !== '');
  const pkgField = (key: keyof FormState['pkg'], label: string) => (
    <TextField id={`pf-pkg-${key}`} label={label} inputMode="decimal" value={s.pkg[key]} onChange={(e) => patch({ pkg: { ...s.pkg, [key]: e.target.value } })} />
  );
  const hasFood = Boolean(type && (type.traits.food || attributesIn(type, 'FOOD').length));
  const hasRegulated = Boolean(type && (type.traits.regulated || attributesIn(type, 'REGULATED').length));
  const aiState = ctx.data?.aiListingWriter.available ? 'available' : ctx.data?.aiListingWriter.reason === 'PLAN' ? 'plan' : 'none';
  const section = (key: SectionKey) => ({ id: key, title: sectionTitle(key), status: status[key] });
  const perVariant = Boolean(s.hasVariants && type?.themes.length);

  return (
    <>
      <PageHeader
        title={editing ? t('sc.pf.titleEdit') : t('sc.pf.titleNew')}
        description={editing ? d?.title : t('sc.pf.descriptionNew')}
        actions={editing || !has('bulkUpload') ? null : <Link href="/listings/bulk" className="inline-flex min-h-11 items-center text-sm sm:min-h-8">{t('sc.pf.bulkLink')}</Link>}
      />
      {d?.status === 'REJECTED' && d.moderationNote ? <Notice tone="danger" title={t('sc.pf.rejected')} role="alert">{d.moderationNote}</Notice> : null}
      {live ? <Notice tone="info" title={t('sc.pf.liveTitle')}>{t('sc.pf.liveBody')}</Notice> : null}
      {restorable && !draft.current ? (
        <Notice tone="warning" title={t('sc.pf.restoreTitle', { time: time(restorable.updatedAt) })}
          action={<Button size="sm" variant="neutral" onClick={() => void restore(restorable.id)}>{t('sc.pf.restore')}</Button>}>
          {t('sc.pf.restoreBody')}
        </Notice>
      ) : null}
      <SectionNav items={navItems} />
      <div className="grid items-start gap-6 xl:grid-cols-[minmax(0,1fr)_320px]">
        <form className="flex min-w-0 flex-col gap-6" noValidate onBlur={onBlur} onSubmit={(e) => { e.preventDefault(); primary(); }} aria-label={t('sc.pf.formLabel')}>
          <FormSection {...section('basics')} hint={editing ? undefined : t('sc.pf.s.basicsHint')}>
            <CategoryPicker tree={tree.data} s={s} locked={live} error={err(fieldId.category)}
              onChoose={(leaf: CategoryLeaf | null, request) => update((prev) => withCategory(prev, leaf, request ?? null))} />
            <TextField id={fieldId.title} label={t('sc.pf.field.title')} help={t(brandDef ? 'sc.pf.titleHelp' : 'sc.pf.titleHelpPlain', { count: s.title.trim().length })} value={s.title} error={err(fieldId.title)} maxLength={200}
              onChange={(e) => patch({ title: e.target.value })} />
            {brandDef ? (
              <div className={GRID}>
                <BrandField id={fieldId.brand} label={brandDef.required ? brandDef.label : t('sc.pf.optional', { label: brandDef.label })} value={s.attrs.brand ?? ''}
                  required={brandDef.required} error={err(fieldId.brand)} onChange={(v) => update((prev) => ({ ...prev, attrs: { ...prev.attrs, brand: v } }))} />
              </div>
            ) : null}
            <DescriptionEditor value={s.description} onChange={(v) => patch({ description: v })} error={err(fieldId.description)} ai={aiState} canWrite={s.title.trim().length >= 3}
              write={() => api.writeListing({ productType: s.productType ?? 'custom', title: s.title.trim(), attributes: Object.fromEntries(Object.entries(s.attrs).filter(([, v]) => v.trim())) })}
              onFailure={(e) => toast.show(problemMessage(e), 'error')} />
            <Disclosure title={t('sc.pf.moreBasics')} force={Boolean(s.keywords.trim() || err(fieldId.keywords))}>
              <TextField id={fieldId.keywords} label={t('sc.pf.field.keywords')} help={t('sc.pf.keywordsHelp')} value={s.keywords} error={err(fieldId.keywords)} onChange={(e) => patch({ keywords: e.target.value })} />
            </Disclosure>
          </FormSection>

          <FormSection {...section('images')} hint={t('sc.pf.s.imagesHint')}>
            <ImagesField images={s.images} update={(fn) => update((prev) => ({ ...prev, images: fn(prev.images) }))} error={err(fieldId.images)} />
          </FormSection>

          {type ? (
            <FormSection {...section('offer')}>
              <div className={GRID_SHORT}>
                <Select id="pf-condition" label={t('sc.pf.field.condition')} value={s.condition} options={CONDITIONS.map((c) => ({ value: c, label: t(`sc.pf.condition.${c}` as MessageKey) }))}
                  onChange={(e) => patch({ condition: e.target.value as FormState['condition'] })} />
                {perVariant ? null : (
                  <>
                    <TextField id={fieldId.price} label={t('sc.pf.field.priceUsd')} inputMode="decimal" value={s.single.price} error={err(fieldId.price)} onChange={(e) => patch({ single: { ...s.single, price: e.target.value } })} />
                    <TextField id={fieldId.compareAt} label={t('sc.pf.field.compareAt')} help={t('sc.pf.compareAtHelp')} inputMode="decimal" value={s.single.compareAt} error={err(fieldId.compareAt)} onChange={(e) => patch({ single: { ...s.single, compareAt: e.target.value } })} />
                    <TextField id={fieldId.qty} label={t('sc.pf.field.qty')} inputMode="numeric" value={s.single.qty} error={err(fieldId.qty)} help={s.fulfilment === 'AFROTRANSACT' ? t('sc.pf.qtyFba') : undefined} onChange={(e) => patch({ single: { ...s.single, qty: e.target.value } })} />
                    <TextField id={fieldId.sku} label={t('sc.pf.field.sku')} value={s.single.sku} error={err(fieldId.sku)} readOnly={live} onChange={(e) => patch({ single: { ...s.single, sku: e.target.value } })} />
                    {s.noGtin ? null : (
                      <TextField id={fieldId.gtin} label={idLabel} inputMode="numeric" value={s.single.gtin} error={err(fieldId.gtin)} help={t('sc.pf.gtinHelp')} onChange={(e) => patch({ single: { ...s.single, gtin: e.target.value } })} />
                    )}
                  </>
                )}
              </div>
              {perVariant ? <p className="m-0 text-sm text-muted">{t('sc.pf.offer.perVariant')}</p> : null}
              {!perVariant && !s.single.sku.trim() && s.title.trim() ? (
                <button type="button" className="link-button -mt-2 self-start border-0 bg-transparent p-0 text-left text-xs text-link hover:underline" onClick={() => update((prev) => fillSkus(type, prev))}>
                  {t('sc.pf.suggestSku')}
                </button>
              ) : null}
              <Checkbox label={type.identifierRule === 'isbn' ? t('sc.pf.noIsbn') : t('sc.pf.noGtin')} description={<span className="block text-xs text-muted">{t('sc.pf.noGtinHint')}</span>} checked={s.noGtin}
                disabled={type.identifierRule === 'isbn'} onChange={(e) => patch({ noGtin: e.target.checked })} />
            </FormSection>
          ) : null}

          {sections.includes('details') ? <FormSection {...section('details')}>{attrGroup('DETAILS')}</FormSection> : null}

          {type && type.themes.length ? (
            <FormSection {...section('variants')}>
              {live ? null : (
                <div className="flex flex-col gap-1.5">
                  <span className="text-sm font-semibold">{t('sc.pf.hasVariants')}</span>
                  <ChipGroup label={t('sc.pf.hasVariants')} value={s.hasVariants ? 'yes' : 'no'} onChange={(v) => patch({ hasVariants: v === 'yes' })}
                    options={[{ value: 'no', label: t('sc.pf.variants.no') }, { value: 'yes', label: t('sc.pf.variants.yes') }]} />
                  <span className="text-xs text-muted">{t('sc.pf.hasVariantsHint')}</span>
                </div>
              )}
              {s.hasVariants ? <VariantsSection s={s} type={type} set={update} problems={problems} shown={shown} locked={lockedCombos} /> : null}
            </FormSection>
          ) : null}

          {type ? (
            <FormSection {...section('fulfilment')}>
              <div className="flex flex-col gap-1.5">
                <span className="text-sm font-semibold">{t('sc.pf.fulfilment.how')}</span>
                <ChipGroup label={t('sc.pf.fulfilment.how')} value={s.fulfilment} onChange={(f) => patch({ fulfilment: f })}
                  options={[{ value: 'SELLER', label: t('sc.pf.fulfilment.SELLER') }, { value: 'AFROTRANSACT', label: t('sc.pf.fulfilment.AFROTRANSACT') }]} />
                <span className="text-xs text-muted">{t(`sc.pf.fulfilment.${s.fulfilment}.hint` as MessageKey)}</span>
              </div>
              {s.fulfilment === 'SELLER' ? (
                <div className={GRID}>
                  <Select id={fieldId.shipFrom} label={t('sc.pf.field.shipFrom')} value={s.shipFromId} error={err(fieldId.shipFrom)}
                    help={shipFrom.data?.length ? undefined : t('sc.pf.shipFromNone')}
                    options={[{ value: '', label: defaultShipFrom ? t('sc.pf.shipFromDefault', { label: `${defaultShipFrom.label} · ${defaultShipFrom.city}, ${defaultShipFrom.state}` }) : t('sc.pf.shipFromAccount') },
                      ...(shipFrom.data ?? []).filter((a) => !a.isDefault).map((a) => ({ value: a.id, label: `${a.label} · ${a.city}, ${a.state}` }))]}
                    onChange={(e) => patch({ shipFromId: e.target.value })} />
                  <Select id={fieldId.handling} label={t('sc.pf.field.handling')} help={t('sc.pf.handlingHelp')} value={s.handlingDays} error={err(fieldId.handling)}
                    options={[...new Set([...HANDLING_DAYS, Number(s.handlingDays) || 2])].sort((x, y) => x - y).map((n) => ({ value: String(n), label: t('sc.pf.handlingDays', { count: n }) }))}
                    onChange={(e) => patch({ handlingDays: e.target.value })} />
                </div>
              ) : null}
              {type.traits.preorder ? (
                <Checkbox label={t('sc.pf.preorder')} description={<span className="block text-xs text-muted">{t('sc.pf.preorderHint')}</span>} checked={s.preorder} onChange={(e) => patch({ preorder: e.target.checked })} />
              ) : null}
              <Disclosure title={s.fulfilment === 'AFROTRANSACT' ? t('sc.pf.field.package') : t('sc.pf.packageOptional')} force={s.fulfilment === 'AFROTRANSACT' || pkgFilled || Boolean(err(fieldId.pkg))}>
                <fieldset id={fieldId.pkg} tabIndex={-1} className="m-0 flex flex-col gap-1 border-0 p-0 outline-none">
                  <legend className="sr-only">{t('sc.pf.field.package')}</legend>
                  <div className={GRID_SHORT}>
                    {pkgField('length', t('sc.pf.pkg.length'))}
                    {pkgField('width', t('sc.pf.pkg.width'))}
                    {pkgField('height', t('sc.pf.pkg.height'))}
                    <Select label={t('sc.pf.pkg.sizeUnit')} value={s.pkgUnits.size} options={SIZE_UNITS.map((u) => ({ value: u, label: u }))}
                      onChange={(e) => patch({ pkgUnits: { ...s.pkgUnits, size: e.target.value === 'cm' ? 'cm' : 'in' } })} />
                    {pkgField('weight', t('sc.pf.pkg.weight'))}
                    <Select label={t('sc.pf.pkg.weightUnit')} value={s.pkgUnits.weight} options={WEIGHT_UNITS.map((u) => ({ value: u, label: u }))}
                      onChange={(e) => patch({ pkgUnits: { ...s.pkgUnits, weight: e.target.value === 'kg' ? 'kg' : 'lb' } })} />
                  </div>
                  {err(fieldId.pkg) ? <span role="alert" className="text-xs text-danger">{err(fieldId.pkg)}</span> : null}
                </fieldset>
              </Disclosure>
            </FormSection>
          ) : null}

          {sections.includes('compliance') ? (
            <FormSection {...section('compliance')} hint={t('sc.pf.s.complianceHint')}>
              {hasFood ? (
                <div className="flex flex-col gap-3">
                  <h3 className="m-0 text-base font-semibold">{t('sc.pf.s.food')}</h3>
                  <p className="m-0 -mt-2 text-xs text-muted">{t('sc.pf.s.foodHint')}</p>
                  {attrGroup('FOOD')}
                </div>
              ) : null}
              {hasRegulated ? (
                <div className="flex flex-col gap-3">
                  <h3 className="m-0 text-base font-semibold">{t('sc.pf.s.regulated')}</h3>
                  <p className="m-0 -mt-2 text-xs text-muted">{t('sc.pf.s.regulatedHint')}</p>
                  {attrGroup('REGULATED')}
                </div>
              ) : null}
            </FormSection>
          ) : null}
          <button type="submit" className="at-visually-hidden" tabIndex={-1}>{primaryLabel}</button>
        </form>
        <ReadinessPanel problems={ordered} tips={tips} percent={percent} preview={preview} onGo={jump} />
      </div>
      <ActionBar
        problems={ordered}
        tips={tips}
        percent={percent}
        preview={preview}
        saveState={saveLine}
        primaryLabel={primaryLabel}
        onPrimary={primary}
        primaryDisabled={unchanged}
        onSaveDraft={() => explicitSave.mutate()}
        saveDraftDisabled={!draftDirty}
        busy={submit.isPending}
        savingDraft={explicitSave.isPending}
        onGo={jump}
      />
    </>
  );
}
