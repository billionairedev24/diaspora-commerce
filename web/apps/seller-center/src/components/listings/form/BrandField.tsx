'use client';

import { sellerQueries } from '@afrotransact/api-client';
import { Combobox } from '@afrotransact/ui';
import { useQuery } from '@tanstack/react-query';
import { useMemo } from 'react';

import { useT } from '@/i18n/t';
import { sellerApi } from '@/lib/api';
import { useSeller } from '@/lib/session';

interface Props {
  id: string;
  label: string;
  value: string;
  required: boolean;
  error?: string;
  onChange: (value: string) => void;
}

/** Brand: the organisation's brands (most used first) and the catalog's, or add a new one by typing it. */
export function BrandField({ id, label, value, required, error, onChange }: Props) {
  const t = useT();
  const api = sellerApi();
  const { orgId } = useSeller();
  const brands = useQuery(sellerQueries.brands(api, orgId, ''));
  const options = useMemo(() => (brands.data?.brands ?? []).map((b) => ({ value: b.name, label: b.name })), [brands.data]);
  const own = useMemo(() => (brands.data?.brands ?? []).filter((b) => b.source === 'OWN').map((b) => b.name), [brands.data]);
  return (
    <Combobox
      id={id}
      label={label}
      options={options}
      value={value}
      recent={own}
      recentLabel={t('sc.pf.brand.yours')}
      allLabel={t('sc.pf.brand.all')}
      placeholder={t('sc.pf.brand.placeholder')}
      emptyLabel={t('sc.pf.brand.none')}
      listLabel={t('sc.pf.brand.list')}
      resultsLabel={(count) => t('sc.pf.category.count', { count })}
      clearable={!required}
      error={error}
      onChange={onChange}
      onCreate={onChange}
      createLabel={(text) => t('sc.pf.brand.add', { name: text })}
    />
  );
}
