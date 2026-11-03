'use client';

import { staffRunQueries } from '@afrotransact/api-client';
import { useFormatters } from '@afrotransact/i18n/react';
import { useQuery } from '@tanstack/react-query';

import { EmptyState, ErrorState, Spinner } from '@/components/ui/States';
import { useT } from '@/i18n/t';

import { CARD, useRunsApi } from './shared';

/**
 * Visits and sign-ups per UTM source, medium and campaign for a town, or for one of its runs: the links shared on
 * WhatsApp, social media and email carry the tags, the shop front counts them (no cookie, address or visitor id), and
 * this is what they brought. Orders credited to each advertorial are in its results.
 */
export function AttributionPanel({ locationId, town, runId }: { locationId: string; town: string; runId?: string }) {
  const t = useT();
  const fmt = useFormatters();
  const api = useRunsApi();
  const q = useQuery(staffRunQueries.attribution(api, locationId, runId));
  if (q.isPending) return <Spinner />;
  if (q.isError) return <ErrorState error={q.error} onRetry={() => void q.refetch()} />;
  const a = q.data;
  return (
    <section aria-labelledby={`attr-title-${runId ?? locationId}`} className={CARD} data-testid={runId ? 'run-attribution' : 'location-attribution'}>
      <div className="flex flex-col gap-1">
        <h3 id={`attr-title-${runId ?? locationId}`} className="m-0 text-md font-bold">
          {t(runId ? 'st.runs.attr.title.run' : 'st.runs.attr.title.location', { town })}
        </h3>
        <p className="m-0 max-w-[80ch] text-xs text-muted">{t('st.runs.attr.help')}</p>
      </div>
      {a.rows.length === 0 ? (
        <EmptyState title={t('st.runs.attr.empty.title')}>{t('st.runs.attr.empty.help')}</EmptyState>
      ) : (
        <div className="overflow-auto rounded-md border border-border">
          <table className="w-full min-w-[560px] border-collapse text-sm" aria-label={t('st.runs.attr.table')}>
            <thead className="bg-surface-muted text-left">
              <tr className="text-2xs font-bold tracking-[0.06em] text-muted uppercase">
                <th scope="col" className="px-3 py-2">
                  {t('st.runs.attr.col.source')}
                </th>
                <th scope="col" className="px-3 py-2">
                  {t('st.runs.attr.col.medium')}
                </th>
                <th scope="col" className="px-3 py-2">
                  {t('st.runs.attr.col.campaign')}
                </th>
                <th scope="col" className="px-3 py-2 text-right">
                  {t('st.runs.attr.col.visits')}
                </th>
                <th scope="col" className="px-3 py-2 text-right">
                  {t('st.runs.attr.col.signups')}
                </th>
              </tr>
            </thead>
            <tbody>
              {a.rows.map((r) => (
                <tr key={`${r.utmSource}|${r.utmMedium ?? ''}|${r.utmCampaign ?? ''}`} className="border-t border-border">
                  <td className="px-3 py-2 font-semibold">{r.utmSource}</td>
                  <td className="px-3 py-2">{r.utmMedium || '-'}</td>
                  <td className="px-3 py-2 break-words">{r.utmCampaign || '-'}</td>
                  <td className="px-3 py-2 text-right">{fmt.number(r.visits)}</td>
                  <td className="px-3 py-2 text-right">{fmt.number(r.signups)}</td>
                </tr>
              ))}
            </tbody>
            <tfoot>
              <tr className="border-t border-border bg-surface-muted font-bold" data-testid="attribution-total">
                <th scope="row" colSpan={3} className="px-3 py-2 text-left">
                  {t('st.runs.attr.total')}
                </th>
                <td className="px-3 py-2 text-right">{fmt.number(a.visits)}</td>
                <td className="px-3 py-2 text-right">{fmt.number(a.signups)}</td>
              </tr>
            </tfoot>
          </table>
        </div>
      )}
      {a.since ? <p className="m-0 text-xs text-muted">{t('st.runs.attr.since', { date: fmt.date(a.since, 'medium') })}</p> : null}
    </section>
  );
}
