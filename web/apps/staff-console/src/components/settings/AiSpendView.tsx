'use client';

import { useT } from '@/i18n/t';

import { AI_DAILY_CAP_USD } from './SettingsHome';

const CALLER_CAP_USD = '0.50';

/**
 * AI and MCP spend cap: the owner's ceiling on what AI may cost per day. It is set where ai-gateway is deployed (environment
 * variables, enforced in code), so this page tells what is in force and who changes it; it has no switch because there is
 * no endpoint that changes it, and it shows the deployment's defaults, not a live reading of today's spend.
 */
export function AiSpendView() {
  const t = useT();
  const rows: [string, string][] = [
    [t('st.sx.ai.row.total'), t('st.sx.ai.row.totalValue', { amount: AI_DAILY_CAP_USD })],
    [t('st.sx.ai.row.caller'), t('st.sx.ai.row.callerValue', { amount: CALLER_CAP_USD })],
    [t('st.sx.ai.row.rate'), t('st.sx.ai.row.rateValue')],
    [t('st.sx.ai.row.atCap'), t('st.sx.ai.row.atCapValue')],
    [t('st.sx.ai.row.mcp'), t('st.sx.ai.row.mcpValue')],
    [t('st.sx.ai.row.changed'), t('st.sx.ai.row.changedValue')]
  ];
  return (
    <>
      <p className="m-0 max-w-[80ch] text-sm text-muted">{t('st.sx.ai.intro')}</p>
      <dl className="m-0 flex flex-col" data-testid="ai-spend">
        {rows.map(([name, value]) => (
          <div key={name} className="grid grid-cols-1 gap-1 border-b border-border py-3 first:border-t sm:grid-cols-[minmax(0,14rem)_1fr] sm:gap-6">
            <dt className="text-sm font-semibold">{name}</dt>
            <dd className="m-0 text-sm text-muted">{value}</dd>
          </div>
        ))}
      </dl>
      <p className="m-0 text-sm text-muted">{t('st.sx.ai.runbook')}</p>
    </>
  );
}
