'use client';

import { type HelpRequest } from '@afrotransact/api-client';
import { useFormatters } from '@afrotransact/i18n/react';
import { EmptyState, FlatPage, Message, Row, RowList, Section, Skeleton, StatusText, type StatusTone, TextLinkButton,textLinkClassName } from '@afrotransact/ui';
import { useInfiniteQuery, useQuery } from '@tanstack/react-query';
import Link from 'next/link';
import { useState } from 'react';

import { type MessageKey, useT } from '@/i18n/t';
import { browserApi } from '@/lib/api/browser';
import { shopperQueries } from '@/lib/api/queries';

import { AccountHeader } from './AccountHeader';
import help from './HelpRequests.module.css';

function RequestRow({ r }: { r: HelpRequest }) {
  const fmt = useFormatters();
  const t = useT();
  const [open, setOpen] = useState(false);
  const detailId = `hr-${r.id}`;
  const tone: StatusTone = r.status === 'RESOLVED' ? 'positive' : r.status === 'CLOSED' || r.status === 'WITHDRAWN' ? 'neutral' : 'info';
  return (
    <Row
      testId="help-request"
      title={
        <>
          <span>{t(`hr.type.${r.type}` as MessageKey)}</span>
          <StatusText tone={tone}>{t(`hr.status.${r.status}` as MessageKey)}</StatusText>
        </>
      }
      detail={
        <>
          <span className={help.line}>
            {r.reference} · {t('hr.meta', { order: r.orderNumber, date: fmt.date(r.createdAt, 'dayMonthShort') })}
          </span>
          <span className={help.line}>{t(`hr.next.${r.status}` as MessageKey)}</span>
          {r.resolution && (
            <span className={help.resolution}>
              <strong>
                {r.resolution.outcome === 'REFUNDED' && r.resolution.refundMinor !== null
                  ? t('hr.outcome.REFUNDED', { amount: fmt.money(r.resolution.refundMinor, r.currency) })
                  : t(`hr.outcome.${r.resolution.outcome}` as MessageKey)}
              </strong>
              {r.resolution.note && <span className={help.note}>{r.resolution.note}</span>}
              {r.resolution.outcome === 'REFUNDED' && <span>{t('hr.refundNote')}</span>}
            </span>
          )}
          {open && (
            <span id={detailId} className={help.resolution}>
              <strong>{t('hr.yourWords')}</strong>
              <span className={help.description}>{r.description}</span>
              <span>{t('hr.photos', { n: r.evidenceCount })}</span>
            </span>
          )}
        </>
      }
      actions={
        <TextLinkButton onClick={() => setOpen((o) => !o)} aria-expanded={open} aria-controls={detailId}>
          {open ? t('hr.hide') : t('hr.details')}
        </TextLinkButton>
      }
    />
  );
}

/** Get help with an order: the customer's requests with their status in plain words, and the orders to ask about. */
export function HelpView() {
  const t = useT();
  const fmt = useFormatters();
  const api = browserApi();
  const requests = useInfiniteQuery(shopperQueries.helpRequests(api));
  const orders = useQuery(shopperQueries.orders(api));
  const delivered = (orders.data?.items ?? []).filter((o) => o.shipments.some((s) => s.deliveredAt));
  const items = requests.data?.pages.flatMap((page) => page.items) ?? [];
  return (
    <FlatPage>
      <AccountHeader title={t('hr.title')} lead={t('hr.intro')} />
      <p className={help.policy}>{t('hr.policy')}</p>

      <Section title={t('hr.yours')} id="hr-yours">
        {requests.isPending && <Skeleton height={96} />}
        {requests.isError && (
          <Message tone="error">
            {t('acct.loadError')} <TextLinkButton onClick={() => void requests.refetch()}>{t('acct.retry')}</TextLinkButton>
          </Message>
        )}
        {requests.isSuccess && items.length === 0 && <EmptyState>{t('hr.empty')}</EmptyState>}
        {items.length > 0 && (
          <RowList label={t('hr.yours')}>
            {items.map((r) => (
              <RequestRow key={r.id} r={r} />
            ))}
          </RowList>
        )}
        {requests.hasNextPage && (
          <div>
            <TextLinkButton onClick={() => void requests.fetchNextPage()} disabled={requests.isFetchingNextPage}>
              {requests.isFetchingNextPage ? t('common.loading') : t('hr.loadMore')}
            </TextLinkButton>
          </div>
        )}
      </Section>

      <Section title={t('hr.chooseOrder')} id="hr-orders">
        {orders.isPending && <Skeleton height={80} />}
        {orders.isSuccess && delivered.length === 0 && <EmptyState>{t('hr.noOrders')}</EmptyState>}
        {delivered.length > 0 && (
          <RowList>
            {delivered.map((o) => (
              <Row
                key={o.orderNumber}
                title={t('ord.number', { number: o.orderNumber })}
                detail={
                  <>
                    <span className={help.line}>{o.shipments.map((s) => s.items).join(' · ')}</span>
                    {o.placedAt && <span className={help.line}>{fmt.date(o.placedAt, 'dayMonthShort')}</span>}
                  </>
                }
                actions={
                  <Link href={`/account/help/new?order=${encodeURIComponent(o.orderNumber)}`} className={textLinkClassName}>
                    {t('hr.getHelp')}
                    <span className={help.srOnly}> {t('ord.number', { number: o.orderNumber })}</span>
                  </Link>
                }
              />
            ))}
          </RowList>
        )}
      </Section>
    </FlatPage>
  );
}
