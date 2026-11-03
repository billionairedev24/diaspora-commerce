'use client';

import { type ContentDocument } from '@afrotransact/api-client';
import { useMemo } from 'react';

import { useT } from '@/i18n/t';
import { type DiffLine, diffLines, hasChanges } from '@/lib/diff';

import { LOCALE_NAME } from './ContentPreview';

function DiffBlock({ label, lines }: { label: string; lines: DiffLine[] }) {
  const t = useT();
  return (
    <section aria-label={label} className="flex flex-col gap-1">
      <h4 className="m-0 text-sm font-semibold">{label}</h4>
      {hasChanges(lines) ? (
        <pre className="m-0 max-h-[28rem] overflow-auto rounded-sm border border-border bg-surface p-2 font-mono text-xs leading-relaxed break-words whitespace-pre-wrap">
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
        <p className="m-0 text-xs text-muted">{t('st.pages.diff.unchanged')}</p>
      )}
    </section>
  );
}

/** Compares two versions of a page: each language's text, line by line, and the settings that changed. */
export function ContentDiff({ from, to, fromLabel, toLabel }: { from: ContentDocument; to: ContentDocument; fromLabel: string; toLabel: string }) {
  const t = useT();
  const blocks = useMemo(() => {
    const locales = [...new Set([...Object.keys(from.bodies), ...Object.keys(to.bodies)])].sort((a, b) => (a === 'en' ? -1 : b === 'en' ? 1 : a.localeCompare(b)));
    return locales.map((l) => ({ locale: l, lines: diffLines(from.bodies[l] ?? '', to.bodies[l] ?? '') }));
  }, [from, to]);
  const settings: [string, string | null, string | null][] = [
    [t('st.pages.diff.version'), from.version, to.version],
    [t('st.pages.diff.effective'), from.effectiveDate, to.effectiveDate],
    [t('st.pages.diff.banner'), from.draftBanner === null ? null : from.draftBanner ? t('st.pages.diff.on') : t('st.pages.diff.off'), to.draftBanner === null ? null : to.draftBanner ? t('st.pages.diff.on') : t('st.pages.diff.off')],
    [t('st.pages.diff.title'), from.title, to.title],
    [t('st.pages.diff.summary'), from.summary, to.summary],
    [t('st.pages.diff.topic'), from.topic, to.topic]
  ];
  const changed = settings.filter(([, a, b]) => (a ?? '') !== (b ?? '') && (a !== null || b !== null));
  return (
    <div className="flex flex-col gap-3" data-testid="content-diff">
      <p className="m-0 text-sm text-muted">{t('st.pages.diff.legend', { from: fromLabel, to: toLabel })}</p>
      {changed.length > 0 ? (
        <dl className="m-0 grid grid-cols-[auto_1fr] gap-x-3 gap-y-1 text-sm">
          {changed.map(([label, a, b]) => (
            <div key={label} className="contents">
              <dt className="font-semibold">{label}</dt>
              <dd className="m-0">
                <del className="bg-danger-bg no-underline">{a ?? '—'}</del> → <ins className="bg-success-bg no-underline">{b ?? '—'}</ins>
              </dd>
            </div>
          ))}
        </dl>
      ) : null}
      {blocks.map((b) => (
        <DiffBlock key={b.locale} label={LOCALE_NAME[b.locale as keyof typeof LOCALE_NAME] ?? b.locale} lines={b.lines} />
      ))}
    </div>
  );
}
