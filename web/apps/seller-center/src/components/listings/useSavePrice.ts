'use client';

import { type ListingRow, sellerKeys, sellerQueries } from '@afrotransact/api-client';
import { useQueryClient } from '@tanstack/react-query';
import { useCallback, useRef } from 'react';

import { sellerApi } from '@/lib/api';
import { useSeller } from '@/lib/session';

import { fromDetail, toProductDraft } from './form-model';

/**
 * Saves the price of a single-offer listing from its table cell. The catalog has no price-only endpoint, so the
 * listing is read, run through the product form's own model (fromDetail → toProductDraft, so nothing but the price
 * changes) and saved with the version that was read: a listing edited elsewhere meanwhile answers 409 instead of being
 * overwritten.
 */
export function useSavePrice(): (row: ListingRow, priceMinor: number) => Promise<ListingRow> {
  const api = sellerApi();
  const qc = useQueryClient();
  const { orgId } = useSeller();
  // The returned function is stable (table columns depend on it): it reads the latest client through a ref.
  const latest = useRef({ api, qc, orgId });
  latest.current = { api, qc, orgId };
  return useCallback(
    async (row, priceMinor) => {
      const { api, qc, orgId } = latest.current;
      const detail = await qc.fetchQuery({ ...sellerQueries.product(api, orgId, row.productId), staleTime: 0 });
      const type = await qc.fetchQuery(sellerQueries.productType(api, detail.productType));
      const form = fromDetail(detail, type);
      const next = { ...form, single: { ...form.single, price: (priceMinor / 100).toFixed(2) } };
      const saved = await api.updateProduct(row.productId, { ...toProductDraft(next, type), version: detail.version });
      await qc.invalidateQueries({ queryKey: sellerKeys.listings(orgId) });
      return saved;
    },
    []
  );
}
