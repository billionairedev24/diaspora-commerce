'use client';

import { staffAiSpendQueries, usdFromMicros } from '@afrotransact/api-client';
import { useQuery } from '@tanstack/react-query';

import { ErrorState, Spinner } from '@/components/ui/States';
import { useT } from '@/i18n/t';
import { staffApi } from '@/lib/api';
import { count, dateTime } from '@/lib/format';

import { AI_DAILY_CAP_USD } from './SettingsHome';

const CALLER_CAP_USD = '0.50';

/**
 * AI and MCP spend cap. At the top: what AI has cost so far today (UTC day) against the daily cap, by feature, read from the AI
 * service's usage ledger; with no call recorded it says so and shows zero, never a guess. Below: what is in force. The cap itself is
 * set where the AI service is deployed (AI_GLOBAL_DAILY_COST_USD) and cannot be changed here, so there is no switch.
 */
export function AiSpendView() {
  const t = useT();
  const api = staffApi();
  const q = useQuery(staffAiSpendQueries.today(api.aiSpend));
  const today = q.data;
  const unlimited = today ? today.capMicros === 0 : false;
  const percent = today && today.capMicros > 0 ? Math.min(100, Math.round((today.spentMicros / today.capMicros) * 100)) : 0;
  const rows: [string, string][] = [
    [t('st.sx.ai.row.total'), today ? (unlimited ? t('st.sx.ai.row.totalUnlimited') : t('st.sx.ai.row.totalValue', { amount: (today.capMicros / 1_000_000).toFixed(2) })) : t('st.sx.ai.row.totalValue', { amount: AI_DAILY_CAP_USD })],
    [t('st.sx.ai.row.caller'), t('st.sx.ai.row.callerValue', { amount: today && today.callerCapMicros > 0 ? (today.callerCapMicros / 1_000_000).toFixed(2) : CALLER_CAP_USD })],
    [t('st.sx.ai.row.rate'), t('st.sx.ai.row.rateValue')],
    [t('st.sx.ai.row.atCap'), t('st.sx.ai.row.atCapValue')],
    [t('st.sx.ai.row.mcp'), t('st.sx.ai.row.mcpValue')],
    [t('st.sx.ai.row.changed'), t('st.sx.ai.row.changedValue')]
  ];
  return (
    <>
      <p className="m-0 max-w-[80ch] text-sm text-muted">{t('st.sx.ai.intro')}</p>
      <section aria-labelledby="ai-today-title" className="flex flex-col gap-3" data-testid="ai-today">
        <h2 id="ai-today-title" className="m-0 text-md font-bold">
          {t('st.sx.ai.today.title')}
        </h2>
        {q.isPending ? (
          <Spinner center={false} />
        ) : q.isError || !today ? (
          <ErrorState error={q.error} onRetry={() => void q.refetch()} />
        ) : (
          <>
            <p className="m-0 text-2xl font-bold" data-testid="ai-spent">
              {unlimited ? t('st.sx.ai.today.spentNoCap', { spent: usdFromMicros(today.spentMicros) }) : t('st.sx.ai.today.spent', { spent: usdFromMicros(today.spentMicros), cap: usdFromMicros(today.capMicros), percent })}
            </p>
            {!unlimited ? <progress className="h-2 w-full max-w-[40rem] accent-[var(--at-color-brand)]" value={today.spentMicros} max={today.capMicros} aria-label={t('st.sx.ai.today.meter', { percent })} /> : null}
            <p className="m-0 text-sm text-muted">
              {today.hasData ? t('st.sx.ai.today.calls', { calls: count(today.calls), tokens: count(today.tokens) }) : t('st.sx.ai.today.none')} {t('st.sx.ai.today.resets', { when: dateTime(today.resetsAt) })}
            </p>
            {today.hasData ? (
              <ul className="m-0 flex list-none flex-col p-0" aria-label={t('st.sx.ai.today.byFeature')} data-testid="ai-by-feature">
                {today.byFeature.map((f) => (
                  <li key={f.feature} className="grid min-h-11 grid-cols-[1fr_auto] items-center gap-x-6 border-b border-border py-1 first:border-t sm:grid-cols-[minmax(0,14rem)_1fr_auto]">
                    <span className="text-sm font-semibold">{f.label}</span>
                    <span className="text-sm text-muted max-sm:hidden">{t('st.sx.ai.today.featureCalls', { calls: f.calls })}</span>
                    <span className="text-sm tabular-nums">{usdFromMicros(f.costMicros)}</span>
                  </li>
                ))}
              </ul>
            ) : null}
            <p className="m-0 text-xs text-muted">{t('st.sx.ai.today.estimate')}</p>
          </>
        )}
      </section>
      <section aria-labelledby="ai-force-title" className="flex flex-col gap-2">
        <h2 id="ai-force-title" className="m-0 text-md font-bold">
          {t('st.sx.ai.force.title')}
        </h2>
        <dl className="m-0 flex flex-col" data-testid="ai-spend">
          {rows.map(([name, value]) => (
            <div key={name} className="grid grid-cols-1 gap-1 border-b border-border py-3 first:border-t sm:grid-cols-[minmax(0,14rem)_1fr] sm:gap-6">
              <dt className="text-sm font-semibold">{name}</dt>
              <dd className="m-0 text-sm text-muted">{value}</dd>
            </div>
          ))}
        </dl>
      </section>
      <p className="m-0 text-sm text-muted">{t('st.sx.ai.runbook')}</p>
    </>
  );
}
