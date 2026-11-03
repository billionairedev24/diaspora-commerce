'use client';

import { type RunOrderBag, sellerQueries,type SellerRunPickup } from '@afrotransact/api-client';
import { Button, buttonClassName } from '@afrotransact/ui';
import { CheckCircle, Printer } from '@phosphor-icons/react/ssr';
import { useQuery } from '@tanstack/react-query';
import Link from 'next/link';

import { Card } from '@/components/ui/Card';
import { Notice } from '@/components/ui/Notice';
import { PageHeader } from '@/components/ui/PageHeader';
import { EmptyState, ErrorState, Spinner } from '@/components/ui/States';
import { StatusPill, type Tone } from '@/components/ui/StatusPill';
import { type MessageKey, type Translator, useT } from '@/i18n/t';
import { sellerApi } from '@/lib/api';
import { dateTime } from '@/lib/format';
import { useSeller } from '@/lib/session';

/* Delivery run pick lists (S264). When a pre-order window closes into a run, delivery-runs tells each seller when the
   AfroTransact driver collects and what to pack: per product across all orders, and per order (one bag each). */

const RUN_TONE: Record<SellerRunPickup['runStatus'], Tone> = { CONFIRMED: 'warn', COLLECTING: 'info', OUT_FOR_DELIVERY: 'info', COMPLETED: 'good' };

const runStatusLabel = (t: Translator<MessageKey>, s: SellerRunPickup['runStatus']) => t(`sc.runPickups.status.${s}`);

function RunHeading({ run }: { run: SellerRunPickup }) {
  const t = useT();
  return (
    <>
      <span>{t('sc.runPickups.run', { town: run.town, state: run.state })}</span>{' '}
      <StatusPill tone={RUN_TONE[run.runStatus]}>{runStatusLabel(t, run.runStatus)}</StatusPill>
    </>
  );
}

function PickupTime({ run }: { run: SellerRunPickup }) {
  const t = useT();
  return (
    <span data-testid="run-pickup-time">
      {run.pickupAt ? <strong>{t('sc.runPickups.pickupAt', { when: dateTime(run.pickupAt) })}</strong> : <strong>{t('sc.runPickups.pickupTbc')}</strong>}
      <span className="text-muted">
        {' · '}
        {t('sc.runPickups.window', { start: dateTime(run.windowStart), end: dateTime(run.windowEnd) })}
        {' · '}
        {t(`sc.runPickups.drop.${run.dropMode}`)}
      </span>
    </span>
  );
}

function Counts({ run }: { run: SellerRunPickup }) {
  const t = useT();
  return (
    <dl className="m-0 flex flex-wrap gap-x-6 gap-y-1 text-sm">
      <div className="flex gap-1">
        <dt className="text-muted">{t('sc.runPickups.orders')}</dt>
        <dd className="m-0 font-semibold">{run.orders}</dd>
      </div>
      <div className="flex gap-1">
        <dt className="text-muted">{t('sc.runPickups.units')}</dt>
        <dd className="m-0 font-semibold">{run.units}</dd>
      </div>
      <div className="flex gap-1">
        <dt className="text-muted">{t('sc.runPickups.collected')}</dt>
        <dd className="m-0 font-semibold">{t('sc.runPickups.collectedOf', { done: run.collectedOrders, total: run.orders })}</dd>
      </div>
    </dl>
  );
}

/** The runs that collect from the organisation: when, how much, and a link to the pick list. */
export function RunPickupsView() {
  const t = useT();
  const { orgId } = useSeller();
  const q = useQuery(sellerQueries.runPickups(sellerApi(), orgId));
  return (
    <>
      <PageHeader
        title={t('sc.runPickups.title')}
        description={t('sc.runPickups.description')}
        actions={
          <Link href="/orders" className={buttonClassName({ variant: 'neutral' })}>
            {t('sc.runPickups.back')}
          </Link>
        }
      />
      {q.isPending ? <Spinner /> : null}
      {q.isError ? <ErrorState error={q.error} onRetry={() => void q.refetch()} /> : null}
      {q.data && q.data.items.length === 0 ? <EmptyState title={t('sc.runPickups.empty')}>{t('sc.runPickups.emptyHelp')}</EmptyState> : null}
      <div className="flex flex-col gap-4">
        {q.data?.items.map((run) => (
          <Card
            key={run.runId}
            title={<RunHeading run={run} />}
            actions={
              <Link href={`/orders/run-pickups/${run.runId}`} className={buttonClassName({ variant: 'neutral' })} aria-label={t('sc.runPickups.openAria', { town: run.town })}>
                {t('sc.runPickups.open')}
              </Link>
            }
          >
            <div data-testid="run-pickup">
              <p className="m-0 mb-2 text-sm">
                <PickupTime run={run} />
              </p>
              <Counts run={run} />
            </div>
          </Card>
        ))}
      </div>
    </>
  );
}

function BagCard({ bag }: { bag: RunOrderBag }) {
  const t = useT();
  return (
    <li className="flex flex-col gap-2 rounded-md border border-border p-3" data-testid="run-bag">
      <div className="flex flex-wrap items-center gap-2">
        <strong className="flex-1 text-md">{t('sc.runPickups.bag', { order: bag.orderNumber })}</strong>
        {bag.collected ? (
          <StatusPill tone="good">
            <CheckCircle size={14} weight="fill" aria-hidden /> {t('sc.runPickups.bagCollected')}
          </StatusPill>
        ) : (
          <StatusPill tone="warn">{t('sc.runPickups.bagWaiting')}</StatusPill>
        )}
      </div>
      <ul className="m-0 flex list-none flex-col gap-1 p-0 text-sm">
        {bag.lines.map((l) => (
          <li key={l.atin} className="flex gap-2">
            <span className="min-w-8 font-semibold">{l.units} ×</span>
            <span className="flex-1">{l.name}</span>
            <span className="text-muted">{l.atin}</span>
          </li>
        ))}
      </ul>
    </li>
  );
}

/** One run's pick list: the total per product, then what goes in each order's bag. Printable. */
export function RunPickupDetailView({ runId }: { runId: string }) {
  const t = useT();
  const { orgId } = useSeller();
  const q = useQuery(sellerQueries.runPickup(sellerApi(), orgId, runId));
  const run = q.data;
  return (
    <>
      <PageHeader
        title={run ? t('sc.runPickups.run', { town: run.town, state: run.state }) : t('sc.runPickups.title')}
        description={run ? <PickupTime run={run} /> : undefined}
        actions={
          <div className="flex flex-wrap gap-2">
            <Link href="/orders/run-pickups" className={buttonClassName({ variant: 'neutral' })}>
              {t('sc.runPickups.backToRuns')}
            </Link>
            {run ? (
              <Button variant="neutral" onClick={() => window.print()}>
                <Printer size={18} aria-hidden /> {t('sc.runPickups.print')}
              </Button>
            ) : null}
          </div>
        }
      />
      {q.isPending ? <Spinner /> : null}
      {q.isError ? <ErrorState error={q.error} onRetry={() => void q.refetch()} /> : null}
      {run ? (
        <>
          <div className="flex flex-wrap items-center gap-3">
            <StatusPill tone={RUN_TONE[run.runStatus]}>{runStatusLabel(t, run.runStatus)}</StatusPill>
            <Counts run={run} />
          </div>
          {run.runStatus === 'CONFIRMED' ? <Notice tone="info" title={t('sc.runPickups.packTitle')}>{t('sc.runPickups.packHelp')}</Notice> : null}
          <Card title={t('sc.runPickups.items')}>
            <table className="w-full border-collapse text-sm" data-testid="run-pick-list">
              <thead>
                <tr className="text-left text-muted">
                  <th className="py-1 font-semibold">{t('sc.runPickups.product')}</th>
                  <th className="py-1 font-semibold">{t('sc.runPickups.atin')}</th>
                  <th className="py-1 text-right font-semibold">{t('sc.runPickups.unitsCol')}</th>
                </tr>
              </thead>
              <tbody>
                {run.items.map((i) => (
                  <tr key={i.atin} className="border-t border-border">
                    <td className="py-1">{i.name}</td>
                    <td className="py-1 text-muted">{i.atin}</td>
                    <td className="py-1 text-right font-semibold">{i.units}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </Card>
          <Card title={t('sc.runPickups.bags')}>
            <p className="m-0 text-sm text-muted">{t('sc.runPickups.bagsHelp')}</p>
            <ul className="m-0 grid list-none gap-3 p-0 sm:grid-cols-2">
              {run.bags.map((b) => (
                <BagCard key={b.orderNumber} bag={b} />
              ))}
            </ul>
          </Card>
        </>
      ) : null}
    </>
  );
}
