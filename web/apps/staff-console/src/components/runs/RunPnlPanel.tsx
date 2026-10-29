'use client';

import { type RunPnl, staffRunQueries } from '@afrotransact/api-client';
import { useFormatters } from '@afrotransact/i18n/react';
import { TextField } from '@afrotransact/ui';
import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';

import { Pill } from '@/components/preorders/primitives';
import { ErrorState, Spinner } from '@/components/ui/States';
import { useT } from '@/i18n/t';

import { ActualsNotes, FeeMixTable, OrdersSummary, PlatformFeesLine } from './RunPnlParts';
import { breakEvenText, CARD, EconomicsTable, reasonText, RUN_STATUS_PILL, runStatusLabel, useRunsApi } from './shared';

/**
 * The P&L of one run, built from its real orders (S265): the estimate before it, the result after it (the orders
 * delivered, those given back, the delivery fees each order was charged, the refunds, the cost, the margin and the
 * break-even at the fee the orders paid), or what went back to customers when it was cancelled. "Platform fees on
 * goods" is a separate line below, never mixed into the delivery profit.
 */
export function RunPnlPanel({ runId }: { runId: string }) {
  const t = useT();
  const fmt = useFormatters();
  const api = useRunsApi();
  const [commission, setCommission] = useState('');
  const parsed = commission.trim() === '' ? undefined : /^\d{1,7}$/.test(commission.trim()) ? Number(commission.trim()) * 100 : Number.NaN;
  const bad = parsed !== undefined && Number.isNaN(parsed);
  const q = useQuery({ ...staffRunQueries.runPnl(api, runId, bad ? undefined : parsed) });
  if (q.isPending) return <Spinner />;
  if (q.isError) return <ErrorState error={q.error} onRetry={() => void q.refetch()} />;
  const p: RunPnl = q.data;
  const e = p.economics;
  const a = p.actuals;
  return (
    <section aria-labelledby={`pnl-title-${runId}`} className={CARD} data-testid="run-pnl">
      <div className="flex flex-wrap items-center gap-2">
        <h3 id={`pnl-title-${runId}`} className="m-0 text-md font-bold">
          {t('st.runs.runs.pnl.title')}
        </h3>
        <Pill className={RUN_STATUS_PILL[p.status] ?? 'bg-ink-50 text-ink-600'}>{runStatusLabel(t, p.status)}</Pill>
        <span className="text-xs text-muted" data-testid="run-pnl-phase">
          {t(`st.runs.phase.${p.phase}`)}
        </span>
      </div>
      {p.phase === 'CANCELLED' ? (
        <div className="flex flex-col gap-1 text-sm" data-testid="run-pnl-cancelled">
          <p className="m-0">{t('st.runs.runs.pnl.cancelledHelp')}</p>
          {p.cancelReason ? <p className="m-0 text-muted">{t('st.runs.runs.pnl.reason', { reason: reasonText(t, p.cancelReason) })}</p> : null}
          <p className="m-0 font-semibold">
            {t(p.refundedFeesEstimated ? 'st.runs.runs.pnl.refundedEstimated' : 'st.runs.runs.pnl.refunded', { orders: p.ordersRefunded, fees: fmt.money(p.refundedFeesMinor, e.currency) })}
          </p>
        </div>
      ) : (
        <>
          <p className="m-0 text-sm font-semibold">{breakEvenText(t, e)}</p>
          {a ? <OrdersSummary a={a} phase={p.phase === 'AFTER' ? 'AFTER' : 'BEFORE'} /> : null}
          {a && a.feeMix.length > 0 ? <FeeMixTable a={a} currency={e.currency} /> : null}
          <EconomicsTable e={e} labelOrders={t(p.phase === 'AFTER' ? 'st.runs.pnl.atActual' : 'st.runs.pnl.soFar')} />
          {a ? <ActualsNotes a={a} currency={e.currency} /> : null}
          <PlatformFeesLine fees={p.platformFees} currency={e.currency} />
          <TextField
            label={t('st.runs.runs.pnl.commission')}
            help={t('st.runs.runs.pnl.commissionHelp')}
            inputMode="decimal"
            value={commission}
            error={bad ? t('st.runs.settings.problem.notAmount') : null}
            onChange={(ev) => setCommission(ev.currentTarget.value)}
            className="max-w-xs"
          />
        </>
      )}
    </section>
  );
}
