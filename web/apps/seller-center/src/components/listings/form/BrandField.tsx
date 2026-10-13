'use client';

import { sellerQueries } from '@afrotransact/api-client';
import { TextField } from '@afrotransact/ui';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { useEffect, useState } from 'react';

import { sellerApi } from '@/lib/api';
import { useSeller } from '@/lib/session';

interface Props {
  id: string;
  label: string;
  value: string;
  error?: string;
  onChange: (value: string) => void;
}

/** Brand with suggestions: the organisation's brands, most used first; any other name can still be typed. */
export function BrandField({ id, label, value, error, onChange }: Props) {
  const api = sellerApi();
  const { orgId } = useSeller();
  const [q, setQ] = useState(value);
  useEffect(() => {
    const h = window.setTimeout(() => setQ(value.trim()), 200);
    return () => window.clearTimeout(h);
  }, [value]);
  const brands = useQuery({ ...sellerQueries.brands(api, orgId, q), placeholderData: keepPreviousData });
  const listId = `${id}-brands`;
  return (
    <>
      <TextField id={id} label={label} value={value} error={error} list={listId} autoComplete="off" onChange={(e) => onChange(e.target.value)} />
      <datalist id={listId}>
        {(brands.data?.brands ?? []).map((b) => <option key={b.name} value={b.name} />)}
      </datalist>
    </>
  );
}
