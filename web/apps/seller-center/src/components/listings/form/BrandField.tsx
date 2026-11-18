'use client';

import { type AttributeDef, isApiError, sellerQueries } from '@afrotransact/api-client';
import { Combobox } from '@afrotransact/ui';
import { useQuery } from '@tanstack/react-query';
import { useEffect, useMemo, useState } from 'react';

import { useT } from '@/i18n/t';
import { sellerApi } from '@/lib/api';
import { useSeller } from '@/lib/session';

/** The brand fields of the form state: the display name, the registry id and the no-brand reason. */
export interface BrandValue {
  brand: string;
  brand_id: string;
  brand_reason: string;
}

interface Props {
  id: string;
  /** The brand attribute of the product type: label, required, help and the no-brand choices all come from it. */
  def: AttributeDef;
  /** The chosen category (node id) the brands are for. */
  categoryId: string | null;
  productType: string | null;
  /** The brand id the opened listing already holds: kept even when it is no longer offered. */
  keepId?: string;
  /** The category's approval level is brand_gated: some named brands need approval; a new or unbranded item does not. */
  gated?: boolean;
  value: BrandValue;
  error?: string;
  onChange: (value: BrandValue) => void;
}

const REASON_PREFIX = '__reason:';
const EMPTY: BrandValue = { brand: '', brand_id: '', brand_reason: '' };

/** What a rejected request says about the brand field: the service's own words, else the error's. */
const problemText = (e: unknown): string | undefined => (isApiError(e) ? (e.fieldErrors['attributes.brand'] ?? e.fieldErrors.name ?? e.message) : undefined);

/**
 * Brand, decided by the category's rule: required (named, no way out), brand or "No brand" / "Handmade", or optional
 * (leave it empty; "No brand" listed first). A type-ahead over the department's brands, "Did you mean" for a close
 * spelling of a listed brand, and always "Add “…” as a new brand" for a name that is not listed (reviewed by AfroTransact,
 * usable at once). Changing the category re-checks the chosen brand.
 */
export function BrandField({ id, def, categoryId, productType, keepId, gated = false, value, error, onChange }: Props) {
  const t = useT();
  const api = sellerApi();
  const { orgId } = useSeller();
  const scope = useMemo(() => (categoryId ? { category: categoryId } : productType ? { productType } : {}), [categoryId, productType]);
  const brands = useQuery(sellerQueries.brands(api, orgId, scope, ''));
  const [created, setCreated] = useState<{ id: string; name: string }[]>([]);
  const [typed, setTyped] = useState('');
  const [looked, setLooked] = useState('');
  const [createError, setCreateError] = useState<string | undefined>();
  useEffect(() => {
    const h = window.setTimeout(() => setLooked(typed.trim()), 250);
    return () => window.clearTimeout(h);
  }, [typed]);
  const suggestions = useQuery(sellerQueries.brandSuggestions(api, orgId, scope, looked));
  const reasons = useMemo(() => def.options.filter((o) => o === 'UNBRANDED' || o === 'HANDMADE'), [def.options]);
  const mode = def.required ? (reasons.length ? 'exempt' : 'named') : 'optional';
  const listed = useMemo(() => brands.data?.brands ?? [], [brands.data]);
  // "Did you mean": the exact match and the close names of what is typed. A brand that is already in the list gains the
  // note and the typed words (so it stays visible while they are typed); one that is not listed gets a row of its own.
  const notes = useMemo(() => {
    const data = looked.length >= 2 && !suggestions.isError ? suggestions.data : undefined;
    return new Map([
      ...(data?.similar ?? []).map((b) => [b.id, { b, note: t('sc.pf.brand.didYouMean') }] as const),
      ...(data?.exact ? [[data.exact.id, { b: data.exact, note: t('sc.pf.brand.exact') }] as const] : [])
    ]);
  }, [suggestions.data, suggestions.isError, looked, t]);
  const pendingNote = t('sc.pf.brand.pending');
  const reasonOptions = useMemo(() => reasons.map((r) => ({ value: REASON_PREFIX + r, label: t(r === 'HANDMADE' ? 'sc.pf.brand.handmade' : 'sc.pf.brand.unbranded') })), [reasons, t]);
  const options = useMemo(() => {
    const listedIds = new Set([...listed.map((b) => b.id), ...created.map((c) => c.id)]);
    const flagged = (id: string, own: string | undefined) => {
      const n = notes.get(id);
      return n ? { description: n.note, keywords: looked } : { description: own };
    };
    return [
      ...listed.map((b) => ({ value: b.id, label: b.name, ...flagged(b.id, b.status === 'PENDING' ? pendingNote : undefined) })),
      ...created.filter((c) => !listed.some((b) => b.id === c.id)).map((c) => ({ value: c.id, label: c.name, ...flagged(c.id, pendingNote) })),
      ...[...notes.values()].filter(({ b }) => !listedIds.has(b.id)).map(({ b, note }) => ({ value: b.id, label: b.name, description: note, keywords: looked })),
      ...reasonOptions
    ];
  }, [listed, created, notes, looked, reasonOptions, pendingNote]);
  const selected = value.brand_reason ? REASON_PREFIX + value.brand_reason : value.brand_id || value.brand;
  const holdsPending = Boolean(value.brand_id) && (created.some((c) => c.id === value.brand_id) || listed.some((b) => b.id === value.brand_id && b.status === 'PENDING'));

  // Changing the category re-validates the brand: one that is not sold there is cleared. The first category (an opened
  // listing) is trusted: the brand it already holds (keepId) is never dropped.
  useEffect(() => {
    if (keepId === value.brand_id || !brands.data || brands.isPlaceholderData || !value.brand_id) return;
    if (!brands.data.brands.some((b) => b.id === value.brand_id) && !created.some((c) => c.id === value.brand_id)) onChange(EMPTY);
  }, [brands.data, brands.isPlaceholderData, created, keepId, onChange, value.brand_id]);

  // A category that must name its brand has no "No brand" or "Handmade": one carried over from another category is dropped.
  useEffect(() => {
    if (value.brand_reason && !reasons.some((r) => r === value.brand_reason)) onChange(EMPTY);
  }, [reasons, value.brand_reason, onChange]);

  const departmentName = brands.data?.departmentName;
  const lookupProblem = !suggestions.isError || looked !== typed.trim() ? undefined : problemText(suggestions.error);
  const helpParts = [def.help, gated ? t('sc.pf.brand.gated') : undefined, holdsPending ? t('sc.pf.brand.pendingHelp') : t('sc.pf.brand.note')];
  return (
    <Combobox
      id={id}
      label={def.label}
      required={def.required}
      tip={t(`sc.pf.brand.tip.${mode}`)}
      options={options}
      value={selected}
      recent={mode === 'optional' ? reasonOptions.map((o) => o.value) : []}
      recentLabel={t('sc.pf.brand.noneGroup')}
      popular={listed.map((b) => b.id)}
      popularLabel={departmentName ? t('sc.pf.brand.group', { department: departmentName }) : t('sc.pf.brand.all')}
      allLabel={mode === 'optional' ? t('sc.pf.brand.all') : t('sc.pf.brand.noneGroup')}
      placeholder={t(`sc.pf.brand.placeholder.${mode}`)}
      emptyLabel={t('sc.pf.brand.none')}
      listLabel={t('sc.pf.brand.list')}
      resultsLabel={(count) => t('sc.pf.category.count', { count })}
      help={helpParts.filter((x): x is string => Boolean(x)).join(' ')}
      clearable={!def.required}
      disabled={!categoryId && !productType}
      error={error ?? createError ?? (lookupProblem ? t('sc.pf.brand.invalid', { why: lookupProblem }) : undefined)}
      onQueryChange={(text) => {
        setTyped(text);
        setCreateError(undefined);
      }}
      onChange={(v) => {
        if (v.startsWith(REASON_PREFIX)) onChange({ brand: '', brand_id: '', brand_reason: v.slice(REASON_PREFIX.length) });
        else {
          const hit = options.find((o) => o.value === v);
          onChange(hit ? { brand: hit.label, brand_id: hit.value, brand_reason: '' } : EMPTY);
        }
      }}
      onCreate={(text) => {
        setCreateError(undefined);
        void api
          .createBrand(text, scope)
          .then((r) => {
            setCreated((prev) => [...prev, { id: r.brand.id, name: r.brand.name }]);
            onChange({ brand: r.brand.name, brand_id: r.brand.id, brand_reason: '' });
          })
          .catch((e: unknown) => setCreateError(problemText(e) ?? t('sc.pf.brand.addFailed')));
      }}
      createLabel={(text) => t('sc.pf.brand.add', { name: text })}
    />
  );
}
