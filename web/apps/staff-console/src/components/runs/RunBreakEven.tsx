'use client';

import { type RunEconomics } from '@afrotransact/api-client';
import { useFormatters } from '@afrotransact/i18n/react';
import { type UseQueryResult } from '@tanstack/react-query';

import { ErrorState, Spinner } from '@/components/ui/States';
import { useT } from '@/i18n/t';

import { breakEvenText, CARD, EconomicsTable } from './shared';

/**
 * Break-even beside the minimum order count while a run is scheduled (S265): the fewest orders whose delivery fees cover
 * the estimated driver cost for the chosen location and fee, and whether the minimum reaches it. The estimate is
 * calculated by delivery-runs from the staff-set cost model and the route geo-service measures.
 */
export function RunBreakEven({ plan, minOrders, place }: { plan: UseQueryResult<RunEconomics>; minOrders: number | null; place: string }) {
  const t = useT();
  const fmt = useFormatters();
  if (plan.isPending && plan.fetchStatus === 'idle') {
    return (
      <p className="m-0 text-xs text-muted" data-testid="run-break-even-hint">
        {t('st.runs.lc.breakEven.chooseLocation')}
      </p>
    );
  }
  if (plan.isPending) return <Spinner label={t('st.runs.breakEven.loading')} />;
  if (plan.isError) return <ErrorState error={plan.error} onRetry={() => void plan.refetch()} />;
  const e = plan.data;
  const covers = minOrders !== null && e.breakEvenOrders !== null ? minOrders >= e.breakEvenOrders : null;
  return (
    <section aria-labelledby="run-break-even-title" className={`${CARD} bg-ink-25`} data-testid="run-break-even">
      <div className="flex flex-col gap-1">
        <h3 id="run-break-even-title" className="m-0 text-md font-bold">
          {t('st.runs.breakEven.title')}
        </h3>
        <p className="m-0 text-xs text-muted">{t('st.runs.breakEven.for', { place })}</p>
      </div>
      <p className="m-0 text-sm font-semibold" data-testid="run-break-even-line">
        {breakEvenText(t, e)}
        {e.breakEvenOrders !== null ? ` · ${t('st.runs.breakEven.perOrder', { fee: fmt.money(e.feeMinor, e.currency) })}` : ''}
      </p>
      {covers === null || minOrders === null ? null : (
        <p role="status" className={`m-0 text-sm ${covers ? 'text-success' : 'text-danger'}`} data-testid="run-break-even-verdict">
          {covers
            ? t('st.runs.breakEven.covers', { min: minOrders })
            : t('st.runs.breakEven.short', { min: minOrders, loss: fmt.money(Math.abs(Math.min(0, e.atMinimum.contributionMarginMinor)), e.currency) })}
        </p>
      )}
      <EconomicsTable e={e} labelOrders={t('st.runs.pnl.atExpected')} />
    </section>
  );
}
