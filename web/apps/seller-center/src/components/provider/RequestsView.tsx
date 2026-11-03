'use client';

import { type BookingCard, formatBps, sellerQueries } from '@afrotransact/api-client';
import { useQuery } from '@tanstack/react-query';

import { Card } from '@/components/ui/Card';
import { ModulePage } from '@/components/ui/ModulePage';
import { EmptyState, ErrorState, Spinner } from '@/components/ui/States';
import { StatusPill, toneOf } from '@/components/ui/StatusPill';
import { useT } from '@/i18n/t';
import { sellerApi } from '@/lib/api';
import { dateTime, minutesLabel, money } from '@/lib/format';
import { useSeller } from '@/lib/session';

import { BookingActions } from './BookingActions';

function expiresIn(iso: string | null): string | null {
  if (!iso) return null;
  const mins = Math.round((new Date(iso).getTime() - Date.now()) / 60000);
  if (mins <= 0) return 'Expiring now';
  return mins < 120 ? `Expires in ${mins} min` : `Expires in ${Math.round(mins / 60)} h`;
}

/** 800 → "8%", 750 → "7.50%". */
const compactBps = (bps: number) => (bps % 100 === 0 ? `${bps / 100}%` : formatBps(bps));

function RequestCard({ b }: { b: BookingCard }) {
  const t = useT();
  const expiry = expiresIn(b.expiresAt);
  const home = b.takeHome;
  return (
    <Card>
      <div className="flex flex-wrap items-start gap-3">
        <div className="flex min-w-0 flex-1 basis-64 flex-col gap-1">
          <div className="flex flex-wrap items-center gap-2">
            <strong className="text-md">{b.customerName}</strong>
            <StatusPill tone={toneOf(b.status)}>{b.statusLabel}</StatusPill>
            {expiry ? <span className="text-xs font-semibold text-warning">{expiry}</span> : null}
          </div>
          <span className="text-sm">{b.serviceName} · {minutesLabel(b.durationMinutes)}</span>
          <span className="text-sm text-muted">
            {dateTime(b.startAt)}
            {b.proposedStartAt ? ` → proposed ${dateTime(b.proposedStartAt)}` : ''} · {b.reference}
          </span>
          {b.note ? <p className="m-0 text-sm text-muted">“{b.note}”</p> : null}
        </div>
        <div className="flex flex-col items-end gap-1 text-sm">
          <strong>{money(b.priceMinor, b.currency)}</strong>
          {b.depositMinor ? <span className="text-xs text-muted">Deposit {money(b.depositMinor, b.currency)} · {b.depositState}</span> : null}
          {home ? (
            <span className="text-right text-xs" data-testid="take-home">
              <strong className="text-sm">{t('sc.requests.takeHome', { keep: money(home.keepMinor, home.currency), pct: compactBps(home.connectionFeeBps) })}</strong>
              <span className="block text-muted">
                {t('sc.requests.takeHomeDetail', { fee: money(home.connectionFeeMinor, home.currency), card: money(home.cardFeesMinor, home.currency) })}
              </span>
            </span>
          ) : null}
        </div>
      </div>
      <BookingActions booking={b} />
    </Card>
  );
}

/** Booking requests: new requests and reschedules waiting for the provider, oldest expiry first. */
export function RequestsView() {
  const api = sellerApi();
  const { orgId } = useSeller();
  const q = useQuery(sellerQueries.requests(api, orgId));
  const items = [...(q.data ?? [])].sort((a, b) => (a.expiresAt ?? a.startAt).localeCompare(b.expiresAt ?? b.startAt));
  return (
    <ModulePage module="requests">
      {q.isPending ? <Spinner /> : q.isError ? <ErrorState error={q.error} onRetry={() => void q.refetch()} /> : items.length === 0 ? (
        <EmptyState title="No requests waiting">New booking and reschedule requests appear here. Requests you don’t answer in time expire and the deposit hold is released.</EmptyState>
      ) : (
        <div className="flex flex-col gap-3">{items.map((b) => <RequestCard key={b.id} b={b} />)}</div>
      )}
    </ModulePage>
  );
}
