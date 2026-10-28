'use client';

import { staffRunQueries, type WindowPlanInput } from '@afrotransact/api-client';
import { useFormatters } from '@afrotransact/i18n/react';
import { useQuery } from '@tanstack/react-query';
import { useMemo } from 'react';

import { ErrorState, Spinner } from '@/components/ui/States';
import { useT } from '@/i18n/t';

import { breakEvenText, CARD, EconomicsTable, marginClass, useDebounced, useRunsApi } from './shared';

/** What the window form holds so far (the break-even is calculated from it before anything is saved). */
export interface BreakEvenForm {
  targets: string[];
  door: boolean;
  pickup: boolean;
  /** The window's own fees in minor units; null = the delivery fee rules decide. */
  doorFeeMinor: number | null;
  pickupFeeMinor: number | null;
  /** Null while the field is blank (the platform default applies). */
  minOrders: number | null;
  defaultMinOrders: number;
}

/**
 * Break-even beside the minimum order count while a window is created or edited (S265): the fewest orders whose
 * delivery fees cover the estimated driver cost for the location the targets resolve to, and whether the minimum
 * reaches it. Calculated by delivery-runs from the staff-set cost model.
 */
export function BreakEvenPanel({ form }: { form: BreakEvenForm }) {
  const t = useT();
  const fmt = useFormatters();
  const api = useRunsApi();
  const minimum = form.minOrders ?? form.defaultMinOrders;
  const body = useMemo<WindowPlanInput>(
    () => ({
      targets: form.targets,
      door: form.door,
      pickup: form.pickup,
      doorFeeMinor: form.doorFeeMinor,
      pickupFeeMinor: form.pickupFeeMinor,
      minOrders: minimum
    }),
    [form.targets, form.door, form.pickup, form.doorFeeMinor, form.pickupFeeMinor, minimum]
  );
  const settled = useDebounced(body, 400);
  const plan = useQuery({ ...staffRunQueries.windowPlan(api, settled), enabled: settled.targets !== undefined && settled.targets.length > 0, placeholderData: (prev) => prev });
  if (form.targets.length === 0) {
    return (
      <p className="m-0 text-xs text-muted" data-testid="break-even-hint">
        {t('st.runs.breakEven.chooseArea')}
      </p>
    );
  }
  if (plan.isPending) return <Spinner label={t('st.runs.breakEven.loading')} />;
  if (plan.isError) return <ErrorState error={plan.error} onRetry={() => void plan.refetch()} />;
  const p = plan.data;
  const e = p.economics;
  const covers = p.minimumCoversBreakEven;
  return (
    <section aria-labelledby="break-even-title" className={`${CARD} bg-ink-25`} data-testid="break-even">
      <div className="flex flex-col gap-1">
        <h3 id="break-even-title" className="m-0 text-md font-bold">
          {t('st.runs.breakEven.title')}
        </h3>
        <p className="m-0 text-xs text-muted">{p.locationName ? t('st.runs.breakEven.for', { place: p.locationName }) : t('st.runs.breakEven.noLocation')}</p>
      </div>
      {e ? (
        <>
          <p className="m-0 text-sm font-semibold" data-testid="break-even-line">
            {breakEvenText(t, e)}
            {e.breakEvenOrders !== null ? ` · ${t('st.runs.breakEven.perOrder', { fee: fmt.money(p.feeMinor, p.currency) })}` : ''}
          </p>
          {covers === null ? null : (
            <p role="status" className={`m-0 text-sm ${covers ? 'text-success' : 'text-danger'}`} data-testid="break-even-verdict">
              {covers
                ? t('st.runs.breakEven.covers', { min: p.minOrders })
                : t('st.runs.breakEven.short', { min: p.minOrders, loss: fmt.money(Math.abs(Math.min(0, e.atMinimum.contributionMarginMinor)), p.currency) })}
            </p>
          )}
          <EconomicsTable e={e} labelOrders={t('st.runs.pnl.atExpected')} />
        </>
      ) : (
        <p className="m-0 text-sm text-muted" data-testid="break-even-unknown">
          {t('st.runs.breakEven.unknownHelp')}
        </p>
      )}
    </section>
  );
}

/**
 * A pre-order window's economics on its Overview (S265): orders, revenue and delivery fees against the estimated cost
 * and break-even; for a cancelled window what went back to customers (refunded in full, delivery fee included).
 */
export function WindowEconomicsCard({ id }: { id: string }) {
  const t = useT();
  const fmt = useFormatters();
  const api = useRunsApi();
  const q = useQuery(staffRunQueries.windowEconomics(api, id));
  if (q.isPending) return <Spinner />;
  if (q.isError) return <ErrorState error={q.error} onRetry={() => void q.refetch()} />;
  const w = q.data;
  const e = w.economics;
  const delivered = w.status === 'DELIVERED';
  return (
    <section aria-labelledby="window-economics-title" className={CARD} data-testid="window-economics">
      <div className="flex flex-col gap-1">
        <h3 id="window-economics-title" className="m-0 text-md font-bold">
          {t('st.runs.window.title')}
        </h3>
        <p className="m-0 text-xs text-muted">{w.cancelled ? t('st.runs.window.cancelledHelp') : delivered ? t('st.runs.window.afterHelp') : t('st.runs.window.beforeHelp')}</p>
      </div>
      {w.cancelled ? (
        <dl className="m-0 grid gap-x-6 gap-y-1 text-sm sm:grid-cols-[auto_1fr]" data-testid="window-refunds">
          <dt className="text-muted">{t('st.runs.window.refunded.orders')}</dt>
          <dd className="m-0">{fmt.number(w.ordersRefunded)}</dd>
          <dt className="text-muted">{t('st.runs.window.refunded.amount')}</dt>
          <dd className="m-0 font-semibold">{fmt.money(w.refundedMinor, w.currency)}</dd>
          <dt className="text-muted">{t('st.runs.window.refunded.settled')}</dt>
          <dd className="m-0">{t('st.runs.window.refunded.settledOf', { done: w.refundsSettled, total: w.ordersRefunded })}</dd>
        </dl>
      ) : (
        <dl className="m-0 grid grid-cols-[repeat(auto-fit,minmax(150px,1fr))] gap-3 text-sm">
          <div className="flex flex-col gap-0.5">
            <dt className="text-xs text-subtle">{t('st.runs.window.orders')}</dt>
            <dd className="m-0 text-xl font-black" data-testid="window-orders">
              {t('st.runs.window.ordersOf', { orders: w.orders, min: w.minOrders })}
            </dd>
          </div>
          <div className="flex flex-col gap-0.5">
            <dt className="text-xs text-subtle">{t('st.runs.window.revenue')}</dt>
            <dd className="m-0 text-xl font-black">{fmt.money(w.revenueMinor, w.currency)}</dd>
          </div>
          <div className="flex flex-col gap-0.5">
            <dt className="text-xs text-subtle">{t('st.runs.window.fees')}</dt>
            <dd className="m-0 text-xl font-black">{fmt.money(w.deliveryFeesMinor, w.currency)}</dd>
          </div>
          {e ? (
            <div className="flex flex-col gap-0.5">
              <dt className="text-xs text-subtle">{t('st.runs.pnl.margin')}</dt>
              <dd className={`m-0 text-xl font-black ${marginClass(e.atOrders.contributionMarginMinor)}`} data-testid="window-margin">
                {fmt.money(e.atOrders.contributionMarginMinor, w.currency)}
              </dd>
            </div>
          ) : null}
        </dl>
      )}
      {!w.cancelled && e ? (
        <>
          <p className="m-0 text-sm font-semibold">{breakEvenText(t, e)}</p>
          <EconomicsTable e={e} labelOrders={t(delivered ? 'st.runs.pnl.atActual' : 'st.runs.pnl.soFar')} />
        </>
      ) : null}
      {!w.cancelled && !e ? <p className="m-0 text-sm text-muted">{t('st.runs.window.noLocation')}</p> : null}
    </section>
  );
}
