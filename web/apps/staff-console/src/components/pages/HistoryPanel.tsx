'use client';

import { type ContentKind, type ContentPage, type ContentVersionRef, staffContentQueries } from '@afrotransact/api-client';
import { Button, Dialog, Select, TextField } from '@afrotransact/ui';
import { useQuery } from '@tanstack/react-query';
import { type FormEvent, useState } from 'react';

import { Card } from '@/components/ui/Card';
import { ErrorState, Spinner } from '@/components/ui/States';
import { StatusPill } from '@/components/ui/StatusPill';
import { useT } from '@/i18n/t';
import { staffApi } from '@/lib/api';
import { dateTime, shortDate } from '@/lib/format';
import { personName } from '@/lib/names';

import { ContentDiff } from './ContentDiff';

const labelOf = (kind: ContentKind, v: string): string => (kind === 'legal' ? `v${v}` : `#${v}`);

/** Compares two versions of the page, line by line. */
function Compare({ page, from, to }: { page: ContentPage; from: string; to: string }) {
  const t = useT();
  const api = staffApi().content;
  const a = useQuery(staffContentQueries.version(api, page.kind, page.key, from));
  const b = useQuery(staffContentQueries.version(api, page.kind, page.key, to));
  if (a.isError || b.isError) return <ErrorState error={a.error ?? b.error} />;
  if (!a.data || !b.data) return <Spinner label={t('st.pages.loading')} />;
  return <ContentDiff from={a.data} to={b.data} fromLabel={labelOf(page.kind, from)} toLabel={labelOf(page.kind, to)} />;
}

function RollbackDialog({ open, onClose, page, version, busy, errors, onConfirm }: { open: boolean; onClose: () => void; page: ContentPage; version: ContentVersionRef | null; busy: boolean; errors: Record<string, string>; onConfirm: (reason: string) => void }) {
  const t = useT();
  const [reason, setReason] = useState('');
  const legal = page.kind === 'legal';
  const submit = (e: FormEvent) => {
    e.preventDefault();
    if (legal || reason.trim().length >= 5) onConfirm(reason.trim());
  };
  return (
    <Dialog
      open={open}
      onOpenChange={(o) => !o && onClose()}
      title={t('st.pages.rollback.title', { version: version ? labelOf(page.kind, version.version) : '' })}
      description={legal ? t('st.pages.rollback.legalIntro') : t('st.pages.rollback.helpIntro')}
      width={520}
      closeLabel={t('st.pages.close')}
    >
      <form noValidate onSubmit={submit} className="flex flex-col gap-3">
        {legal ? null : (
          <TextField id="rollback-reason" label={t('st.pages.rollback.reason')} value={reason} maxLength={500} error={errors.reason} onChange={(e) => setReason(e.currentTarget.value)} />
        )}
        {errors._ ? <p role="alert" className="m-0 text-sm text-danger">{errors._}</p> : null}
        <div className="flex justify-end gap-2">
          <Button variant="neutral" onClick={onClose}>
            {t('st.pages.cancel')}
          </Button>
          <Button type="submit" disabled={busy || (!legal && reason.trim().length < 5)}>
            {legal ? t('st.pages.rollback.startDraft') : t('st.pages.rollback.publish')}
          </Button>
        </div>
      </form>
    </Dialog>
  );
}

export interface HistoryPanelProps {
  page: ContentPage;
  canRollback: boolean;
  onPreview: (version: string) => void;
  onRollback: (version: string, reason: string) => Promise<void>;
  busy: boolean;
  errors: Record<string, string>;
}

/** Every published version, who made it and why, a line diff between any two, and rollback (publishing an old version as a new one). */
export function HistoryPanel({ page, canRollback, onPreview, onRollback, busy, errors }: HistoryPanelProps) {
  const t = useT();
  const versions = page.versions;
  const [to, setTo] = useState(versions[0]?.version ?? '');
  const [from, setFrom] = useState(versions[1]?.version ?? versions[0]?.version ?? '');
  const [rolling, setRolling] = useState<ContentVersionRef | null>(null);
  const options = versions.map((v) => ({ value: v.version, label: `${labelOf(page.kind, v.version)} · ${v.publishedAt ? dateTime(v.publishedAt) : ''}` }));
  return (
    <div className="flex flex-col gap-4">
      <Card title={t('st.pages.history.title')}>
        <div className="overflow-x-auto">
          <table aria-label={t('st.pages.history.title')} className="w-full min-w-[40rem] border-collapse text-sm">
            <thead>
              <tr className="text-start text-xs text-muted">
                <th scope="col" className="p-2 text-start font-semibold">{t('st.pages.history.version')}</th>
                <th scope="col" className="p-2 text-start font-semibold">{t('st.pages.history.published')}</th>
                <th scope="col" className="p-2 text-start font-semibold">{t('st.pages.history.what')}</th>
                <th scope="col" className="p-2 text-end font-semibold">
                  <span className="sr-only">{t('st.pages.history.actions')}</span>
                </th>
              </tr>
            </thead>
            <tbody>
              {versions.map((v) => (
                <tr key={v.version} data-testid="content-version" className="border-t border-border align-top">
                  <th scope="row" className="p-2 text-start font-bold">
                    <span className="flex flex-wrap items-center gap-2">
                      {labelOf(page.kind, v.version)}
                      {v.status === 'CURRENT' ? <StatusPill tone="good">{t('st.pages.history.live')}</StatusPill> : null}
                      {v.draftBanner ? <StatusPill tone="warn">{t('st.pages.status.DRAFT_FOR_COUNSEL')}</StatusPill> : null}
                    </span>
                    {v.effectiveDate ? <span className="block text-xs font-normal text-muted">{t('st.pages.history.effective', { date: shortDate(v.effectiveDate) })}</span> : null}
                  </th>
                  <td className="p-2">
                    {personName(t, v.publishedByName)}
                    <span className="block text-xs text-muted">{v.publishedAt ? dateTime(v.publishedAt) : '—'}</span>
                  </td>
                  <td className="max-w-[28rem] p-2">
                    {v.changeSummary ?? <span className="text-muted">—</span>}
                    {v.reason ? <span className="block text-xs text-muted">{t('st.pages.history.reason', { reason: v.reason })}</span> : null}
                  </td>
                  <td className="p-2 text-end">
                    <span className="inline-flex flex-wrap justify-end gap-1">
                      <Button size="sm" variant="neutral" onClick={() => onPreview(v.version)}>
                        {t('st.pages.history.preview')}
                      </Button>
                      {canRollback && v.status !== 'CURRENT' ? (
                        <Button size="sm" variant="neutral" onClick={() => setRolling(v)}>
                          {t('st.pages.history.rollback')}
                        </Button>
                      ) : null}
                    </span>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </Card>
      {versions.length > 1 ? (
        <Card title={t('st.pages.history.compare')}>
          <div className="grid grid-cols-1 gap-2 sm:grid-cols-2">
            <Select label={t('st.pages.history.from')} options={options} value={from} onChange={(e) => setFrom(e.currentTarget.value)} />
            <Select label={t('st.pages.history.to')} options={options} value={to} onChange={(e) => setTo(e.currentTarget.value)} />
          </div>
          {from && to ? <Compare key={`${from}-${to}`} page={page} from={from} to={to} /> : null}
        </Card>
      ) : (
        <p className="m-0 text-sm text-muted">{t('st.pages.history.single')}</p>
      )}
      <RollbackDialog
        key={rolling?.version ?? 'none'}
        open={rolling !== null}
        onClose={() => setRolling(null)}
        page={page}
        version={rolling}
        busy={busy}
        errors={errors}
        onConfirm={(reason) => {
          if (rolling) void onRollback(rolling.version, reason).then(() => setRolling(null)).catch(() => undefined);
        }}
      />
    </div>
  );
}
