'use client';

import { type AttributeDef, sellerQueries } from '@afrotransact/api-client';
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
  value: BrandValue;
  error?: string;
  onChange: (value: BrandValue) => void;
}

const REASON_PREFIX = '__reason:';
const EMPTY: BrandValue = { brand: '', brand_id: '', brand_reason: '' };

/**
 * Brand: only the brands of the chosen category's department (never another department's), the type's label and help,
 * a type-ahead, an explicit "No brand" choice where the type allows it, and "Add “…” as a new brand" for a name that is
 * not listed (reviewed by AfroTransact, usable at once). Changing the category re-checks the chosen brand.
 */
export function BrandField({ id, def, categoryId, productType, keepId, value, error, onChange }: Props) {
  const t = useT();
  const api = sellerApi();
  const { orgId } = useSeller();
  const scope = useMemo(() => (categoryId ? { category: categoryId } : productType ? { productType } : {}), [categoryId, productType]);
  const brands = useQuery(sellerQueries.brands(api, orgId, scope, ''));
  const [created, setCreated] = useState<{ id: string; name: string }[]>([]);
  const reasons = useMemo(() => def.options.filter((o) => o === 'UNBRANDED' || o === 'HANDMADE'), [def.options]);
  const listed = useMemo(() => brands.data?.brands ?? [], [brands.data]);
  const options = useMemo(
    () => [
      ...listed.map((b) => ({ value: b.id, label: b.name, description: b.status === 'PENDING' ? t('sc.pf.brand.pending') : undefined })),
      ...created.filter((c) => !listed.some((b) => b.id === c.id)).map((c) => ({ value: c.id, label: c.name, description: t('sc.pf.brand.pending') })),
      ...reasons.map((r) => ({ value: REASON_PREFIX + r, label: t(r === 'HANDMADE' ? 'sc.pf.brand.handmade' : 'sc.pf.brand.unbranded') }))
    ],
    [listed, created, reasons, t]
  );
  const selected = value.brand_reason ? REASON_PREFIX + value.brand_reason : value.brand_id || value.brand;

  // Changing the category re-validates the brand: one that is not sold there is cleared. The first category (an opened
  // listing) is trusted: the brand it already holds (keepId) is never dropped.
  useEffect(() => {
    if (keepId === value.brand_id || !brands.data || brands.isPlaceholderData || !value.brand_id) return;
    if (!brands.data.brands.some((b) => b.id === value.brand_id) && !created.some((c) => c.id === value.brand_id)) onChange(EMPTY);
  }, [brands.data, brands.isPlaceholderData, created, keepId, onChange, value.brand_id]);

  const label = def.required ? def.label : t('sc.pf.optional', { label: def.label });
  const departmentName = brands.data?.departmentName;
  return (
    <Combobox
      id={id}
      label={label}
      options={options}
      value={selected}
      popular={listed.map((b) => b.id)}
      popularLabel={departmentName ? t('sc.pf.brand.group', { department: departmentName }) : t('sc.pf.brand.all')}
      allLabel={t('sc.pf.brand.noneGroup')}
      placeholder={t('sc.pf.brand.placeholder')}
      emptyLabel={t('sc.pf.brand.none')}
      listLabel={t('sc.pf.brand.list')}
      resultsLabel={(count) => t('sc.pf.category.count', { count })}
      help={def.help ? `${def.help} ${t('sc.pf.brand.note')}` : t('sc.pf.brand.note')}
      clearable={!def.required}
      disabled={!categoryId && !productType}
      error={error}
      onChange={(v) => {
        if (v.startsWith(REASON_PREFIX)) onChange({ brand: '', brand_id: '', brand_reason: v.slice(REASON_PREFIX.length) });
        else {
          const hit = options.find((o) => o.value === v);
          onChange(hit ? { brand: hit.label, brand_id: hit.value, brand_reason: '' } : EMPTY);
        }
      }}
      onCreate={(text) => {
        void api.createBrand(text, scope).then((r) => {
          setCreated((prev) => [...prev, { id: r.brand.id, name: r.brand.name }]);
          onChange({ brand: r.brand.name, brand_id: r.brand.id, brand_reason: '' });
        });
      }}
      createLabel={(text) => t('sc.pf.brand.add', { name: text })}
    />
  );
}
