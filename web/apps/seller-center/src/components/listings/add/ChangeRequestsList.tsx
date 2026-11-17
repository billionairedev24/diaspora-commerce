'use client';

import { type CatalogMatch, type ChangeRequest, matchingKeys, matchingQueries } from '@afrotransact/api-client';
import { Row, RowList, Section, StatusText, type StatusTone, TextLinkButton, useToast } from '@afrotransact/ui';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';

import { problemMessage } from '@/components/ui/States';
import { type MessageKey, useT } from '@/i18n/t';
import { sellerApi } from '@/lib/api';
import { shortDate } from '@/lib/format';
import { useSeller } from '@/lib/session';

import { changeAsMatch, readyToSell, requestedSize } from './match-model';

const TONE: Record<ChangeRequest['status'], StatusTone> = { PENDING: 'info', APPLIED: 'positive', REJECTED: 'warning', WITHDRAWN: 'neutral' };

/**
 * What the seller asked for and where it stands: Waiting, Applied, or Not applied because ... (the reason staff gave). Nothing when there
 * are none. A size the seller asked for that staff applied opens the price-and-stock form for it ("Add your price and stock").
 */
export function ChangeRequestsList({ onSell }: { onSell?: (match: CatalogMatch, atin: string) => void }) {
  const t = useT();
  const toast = useToast();
  const qc = useQueryClient();
  const { orgId, can } = useSeller();
  const api = sellerApi().matching;
  const list = useQuery(matchingQueries.changeRequests(api, orgId));
  const withdraw = useMutation({
    mutationFn: (id: string) => api.withdrawChangeRequest(id),
    onSuccess: () => void qc.invalidateQueries({ queryKey: matchingKeys.changeRequests(orgId) }),
    onError: (e) => toast.show(problemMessage(e), 'error')
  });
  const items = list.data?.items ?? [];
  if (!items.length) return null;
  return (
    <Section title={t('sc.change.title')} lead={t('sc.change.lead')} testId="change-requests">
      <RowList label={t('sc.change.title')}>
        {items.map((r) => (
          <Row
            key={r.id}
            title={`${t(`sc.change.type.${r.type}` as MessageKey)}${r.title ? ` · ${r.title}` : ''}`}
            detail={
              <>
                <StatusText tone={TONE[r.status]}>{t(`sc.change.status.${r.status}` as MessageKey)}</StatusText>
                {' · '}
                {shortDate(r.requestedAt)}
                {r.status === 'REJECTED' && r.reason ? <span className="block">{t('sc.change.because', { reason: r.reason })}</span> : null}
                {r.status === 'APPLIED' && r.reason ? <span className="block">{r.reason}</span> : null}
                {requestedSize(r) ? <span className="block">{t('sc.change.size', { size: requestedSize(r) ?? '' })}</span> : null}
              </>
            }
            actions={r.status === 'PENDING' && can('listings') ? (
              <TextLinkButton disabled={withdraw.isPending} onClick={() => withdraw.mutate(r.id)}>{t('sc.change.withdraw')}</TextLinkButton>
            ) : readyToSell(r) && onSell && can('listings') ? (
              <TextLinkButton onClick={() => onSell(changeAsMatch(r), r.resultAtin ?? '')}>{t('sc.change.addPrice')}</TextLinkButton>
            ) : null}
          />
        ))}
      </RowList>
    </Section>
  );
}
