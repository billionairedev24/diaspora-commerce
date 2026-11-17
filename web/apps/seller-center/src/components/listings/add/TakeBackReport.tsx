'use client';

import { matchingKeys, matchingQueries } from '@afrotransact/api-client';
import { TextLinkButton } from '@afrotransact/ui';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';

import { problemMessage } from '@/components/ui/States';
import { useT } from '@/i18n/t';
import { sellerApi } from '@/lib/api';
import { useSeller } from '@/lib/session';

/**
 * A wrong-product report that is still waiting stops a new page with the same barcode (so a page is not created while staff check it).
 * The seller who made the report by mistake is never locked in by it: here they take it back, and the barcode is free again as soon as it is
 * withdrawn.
 */
export function TakeBackReport() {
  const t = useT();
  const qc = useQueryClient();
  const { orgId, can } = useSeller();
  const api = sellerApi().matching;
  const list = useQuery(matchingQueries.changeRequests(api, orgId));
  const waiting = (list.data?.items ?? []).filter((r) => r.type === 'WRONG_MATCH' && r.status === 'PENDING');
  const withdraw = useMutation({
    mutationFn: async () => {
      for (const r of waiting) await api.withdrawChangeRequest(r.id);
    },
    onSuccess: () => void qc.invalidateQueries({ queryKey: matchingKeys.all })
  });
  if (!waiting.length || !can('listings')) return null;
  return (
    <span className="mt-2 block" data-testid="take-back">
      <span className="block">{t('sc.match.takeBackHint')}</span>
      <TextLinkButton disabled={withdraw.isPending} onClick={() => withdraw.mutate()}>{t('sc.match.takeBack')}</TextLinkButton>
      {withdraw.isError ? <span role="alert" className="block text-danger">{problemMessage(withdraw.error)}</span> : null}
    </span>
  );
}
