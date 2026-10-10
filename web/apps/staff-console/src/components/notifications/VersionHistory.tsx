'use client';

import { type NotificationTemplateVariant, type NotificationTemplateVersion } from '@afrotransact/api-client';
import { Select } from '@afrotransact/ui';
import { useMemo, useState } from 'react';

import { Card } from '@/components/ui/Card';
import { StatusPill } from '@/components/ui/StatusPill';
import { useT } from '@/i18n/t';
import { type DiffLine, diffLines, hasChanges } from '@/lib/diff';
import { dateTime } from '@/lib/format';

import { CATEGORY_LABEL } from './labels';

function DiffBlock({ label, lines }: { label: string; lines: DiffLine[] }) {
  const t = useT();
  return (
    <section aria-label={label} className="flex flex-col gap-1">
      <h4 className="m-0 text-sm font-semibold">{label}</h4>
      {hasChanges(lines) ? (
        <pre className="m-0 rounded-sm border border-border bg-surface p-2 font-mono text-xs leading-relaxed break-all whitespace-pre-wrap">
          {lines.map((l, i) =>
            l.kind === 'added' ? (
              <ins key={i} className="block bg-success-bg text-text no-underline">
                {'+ '}
                {l.text}
              </ins>
            ) : l.kind === 'removed' ? (
              <del key={i} className="block bg-danger-bg text-text no-underline">
                {'− '}
                {l.text}
              </del>
            ) : (
              <span key={i} className="block text-muted">
                {'  '}
                {l.text}
              </span>
            )
          )}
        </pre>
      ) : (
        <p className="m-0 text-xs text-muted">{t('st.ntf.history.unchanged')}</p>
      )}
    </section>
  );
}

/** Versions of one locale and channel, newest first, with a line diff between any two of them. */
export function VersionHistory({ variant, selected, onSelect }: { variant: NotificationTemplateVariant; selected: number; onSelect: (version: number) => void }) {
  const t = useT();
  const versions = variant.versions;
  const [to, setTo] = useState(versions[0]?.version ?? 0);
  const [from, setFrom] = useState(versions[1]?.version ?? versions[0]?.version ?? 0);
  const byNo = (n: number): NotificationTemplateVersion | undefined => versions.find((v) => v.version === n);
  const a = byNo(from);
  const b = byNo(to);
  const diff = useMemo(
    () =>
      a && b
        ? {
            subject: diffLines(a.subject, b.subject),
            html: diffLines(a.bodyHtml, b.bodyHtml),
            text: diffLines(a.bodyText, b.bodyText),
            category: a.category === b.category ? null : `${a.category} → ${b.category}`
          }
        : null,
    [a, b]
  );
  const options = versions.map((v) => ({ value: String(v.version), label: `v${v.version} · ${dateTime(v.createdAt)}` }));
  return (
    <Card title={t('st.ntf.history.title')}>
      <ol aria-label={t('st.ntf.history.title')} className="m-0 flex list-none flex-col gap-2 p-0">
        {versions.map((v) => (
          <li key={v.version} className="flex flex-wrap items-center gap-2 rounded-md border border-border p-2 text-sm" data-testid="template-version">
            <strong>v{v.version}</strong>
            {v.version === variant.latestVersion ? <StatusPill tone="good">{t('st.ntf.history.live')}</StatusPill> : null}
            <span className="text-muted">{t(CATEGORY_LABEL[v.category])}</span>
            <span className="min-w-0 flex-1 text-xs text-muted">
              {v.createdBy ?? '—'} · {dateTime(v.createdAt)}
            </span>
            <button
              type="button"
              className="rounded-md border border-border px-2 py-1 text-xs font-semibold"
              aria-pressed={selected === v.version}
              onClick={() => onSelect(v.version)}
            >
              {selected === v.version ? t('st.ntf.history.previewing') : t('st.ntf.history.preview', { version: v.version })}
            </button>
          </li>
        ))}
      </ol>
      {versions.length > 1 ? (
        <div className="flex flex-col gap-3 border-t border-border pt-3">
          <h3 className="m-0 text-md font-semibold">{t('st.ntf.history.compare')}</h3>
          <div className="grid grid-cols-1 gap-2 sm:grid-cols-2">
            <Select label={t('st.ntf.history.from')} options={options} value={String(from)} onChange={(e) => setFrom(Number(e.currentTarget.value))} />
            <Select label={t('st.ntf.history.to')} options={options} value={String(to)} onChange={(e) => setTo(Number(e.currentTarget.value))} />
          </div>
          {diff ? (
            <div className="flex flex-col gap-3" data-testid="version-diff">
              {diff.category ? <p className="m-0 text-sm">{t('st.ntf.history.categoryChanged', { change: diff.category })}</p> : null}
              {variant.channel !== 'sms' ? <DiffBlock label={t('st.ntf.history.subject')} lines={diff.subject} /> : null}
              {variant.channel === 'email' ? <DiffBlock label={t('st.ntf.history.html')} lines={diff.html} /> : null}
              <DiffBlock label={t('st.ntf.history.text')} lines={diff.text} />
            </div>
          ) : null}
        </div>
      ) : (
        <p className="m-0 text-xs text-muted">{t('st.ntf.history.single')}</p>
      )}
    </Card>
  );
}
