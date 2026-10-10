'use client';

import { type ExportJob, staffQueries } from '@afrotransact/api-client';
import { useQuery } from '@tanstack/react-query';

import { ModulePage } from '@/components/module/ModulePage';
import { EmptyState, ErrorState, Spinner } from '@/components/ui/States';
import { StatusPill } from '@/components/ui/StatusPill';
import { useT } from '@/i18n/t';
import { staffApi } from '@/lib/api';
import { dateTime, humanize } from '@/lib/format';

const TONE = { READY: 'good', QUEUED: 'brand', RUNNING: 'info', FAILED: 'bad', EXPIRED: 'info' } as const;

const busy = (jobs: ExportJob[] | undefined) => !!jobs?.some((j) => j.status === 'QUEUED' || j.status === 'RUNNING');

/** My exports: background jobs poll until ready; links expire 24 hours after the file is built. */
export function ExportsView() {
  const t = useT();
  const q = useQuery({ ...staffQueries.exports(staffApi()), refetchInterval: (query) => (busy(query.state.data?.items) ? 3000 : false) });
  return (
    <ModulePage module="exports">
      {q.isPending ? <Spinner /> : null}
      {q.isError ? <ErrorState error={q.error} onRetry={() => void q.refetch()} /> : null}
      {q.data && q.data.items.length === 0 ? <EmptyState title={t('st.exportsView.noExportsYet')}>Use Export on any table. Up to 5,000 rows download right away; bigger exports build here in the background.</EmptyState> : null}
      {q.data && q.data.items.length ? (
        <div className="overflow-hidden rounded-md border border-border">
          <table className="at-table text-sm" aria-label={t('st.exportsView.exports')}>
            <colgroup>
              <col style={{ width: '24%' }} />
              <col style={{ width: '10%' }} />
              <col style={{ width: '12%' }} />
              <col style={{ width: '10%' }} />
              <col style={{ width: '18%' }} />
              <col style={{ width: '26%' }} />
            </colgroup>
            <thead className="bg-surface-muted text-left">
              <tr>
                {[t('st.exportsView.file'), t('st.exportsView.format'), t('st.exportsView.status'), t('st.exportsView.rows'), t('st.exportsView.linkExpires'), ''].map((h) => (
                  <th key={h || 'actions'} scope="col" className="px-3 py-2 text-xs font-bold text-muted">
                    {h}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {q.data.items.map((j) => (
                <tr key={j.id} className="border-t border-border">
                  <td className="px-3 py-2.5">
                    <strong className="font-semibold">{j.fileName}</strong>
                    <br />
                    <span className="text-xs text-muted">
                      {humanize(j.resource)} · {dateTime(j.createdAt)}
                      {j.async ? ' · background' : ''}
                    </span>
                  </td>
                  <td className="px-3 py-2.5 uppercase">{j.format}</td>
                  <td className="px-3 py-2.5">
                    <StatusPill tone={TONE[j.status]}>{humanize(j.status)}</StatusPill>
                  </td>
                  <td className="px-3 py-2.5 tabular-nums">{j.rows.toLocaleString('en-US')}</td>
                  <td className="px-3 py-2.5">{dateTime(j.expiresAt)}</td>
                  <td className="px-3 py-2.5">
                    {j.status === 'READY' && j.downloadUrl ? (
                      <a href={j.downloadUrl} download>
                        {t('st.exportsView.download')}
                      </a>
                    ) : j.error ? (
                      <span className="text-xs text-danger">{j.error}</span>
                    ) : j.status === 'EXPIRED' ? (
                      <span className="text-xs text-muted">{t('st.exportsView.exportTheTableAgain')}</span>
                    ) : (
                      <span className="text-xs text-muted" role="status">
                        {t('st.exportsView.building')}
                      </span>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : null}
    </ModulePage>
  );
}
