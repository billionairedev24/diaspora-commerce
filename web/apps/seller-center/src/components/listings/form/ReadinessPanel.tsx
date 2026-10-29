'use client';

import { cx } from '@afrotransact/ui';
import { CheckCircle, WarningCircle } from '@phosphor-icons/react/ssr';
import { type ReactNode } from 'react';

import { type MessageKey, useT } from '@/i18n/t';

import { type Problem, type QualityItem } from '../form-model';
import { motion } from './SectionNav';

/** Moves to a field and focuses it (the quality-panel links). A field folded in a closed "More details" is opened first. */
export function goToField(id: string) {
  const el = typeof document !== 'undefined' ? document.getElementById(id) : null;
  if (!el) return;
  const folded = el.closest('details');
  if (folded && !folded.open) folded.open = true;
  el.scrollIntoView({ behavior: motion(), block: 'center' });
  el.focus({ preventScroll: true });
}

export const labelOf = (t: ReturnType<typeof useT>, label: Problem['label']) =>
  typeof label === 'string' ? t(label) : label.key ? `${label.text} · ${t(label.key)}` : label.text;

/** Progress bar of the listing quality score. */
export function QualityBar({ percent }: { percent: number }) {
  const t = useT();
  return (
    <div className="flex flex-col gap-1">
      <div className="flex items-baseline justify-between gap-2 text-xs">
        <span className="font-semibold">{t('sc.pf.q.title')}</span>
        <span className="text-muted" data-testid="quality-percent">{t('sc.pf.q.percent', { percent })}</span>
      </div>
      <div role="progressbar" aria-label={t('sc.pf.q.title')} aria-valuemin={0} aria-valuemax={100} aria-valuenow={percent} aria-valuetext={t('sc.pf.q.percent', { percent })}
        className="h-2 overflow-hidden rounded-pill bg-surface-sunken">
        <div className={cx('h-full rounded-pill transition-[width]', percent === 100 ? 'bg-success' : 'bg-gold-500')} style={{ width: `${percent}%` }} />
      </div>
    </div>
  );
}

/** What is missing (required, with its message) and what would make the listing better; each row jumps to its field. */
export function QualityLists({ problems, tips, onGo }: { problems: [string, Problem][]; tips: QualityItem[]; onGo: (id: string) => void }) {
  const t = useT();
  const row = (key: string, id: string, title: string, note: string, tone: 'text-danger' | 'text-muted') => (
    <li key={key}>
      <a href={`#${id}`} onClick={(e) => { e.preventDefault(); onGo(id); }} className="flex flex-col rounded-sm px-2 py-1.5 text-sm hover:bg-surface-hover">
        <span className="font-semibold text-text">{title}</span>
        <span className={cx('text-xs', tone)}>{note}</span>
      </a>
    </li>
  );
  return (
    <div className="flex flex-col gap-2">
      {problems.length ? (
        <ul aria-label={t('sc.pf.q.required')} className="m-0 flex list-none flex-col gap-0.5 p-0" data-testid="missing-list">
          {problems.map(([id, p]) => row(id, id, labelOf(t, p.label), t(p.key as MessageKey, p.vars), 'text-danger'))}
        </ul>
      ) : null}
      {tips.length ? (
        <>
          <h3 className="m-0 mt-1 px-2 text-xs font-semibold text-muted">{t('sc.pf.q.improve')}</h3>
          <ul aria-label={t('sc.pf.q.improve')} className="m-0 flex list-none flex-col gap-0.5 p-0" data-testid="tips-list">
            {tips.map((i) => row(`tip-${i.id}`, i.id, labelOf(t, i.label), t(i.tip ?? 'sc.pf.tip.description'), 'text-muted'))}
          </ul>
        </>
      ) : null}
    </div>
  );
}

interface Props {
  problems: [string, Problem][];
  tips: QualityItem[];
  percent: number;
  preview: ReactNode;
  onGo: (id: string) => void;
}

/** Sticky right-hand "Listing quality" panel (wide screens): score, what is missing, what would help, and the storefront preview. */
export function ReadinessPanel({ problems, tips, percent, preview, onGo }: Props) {
  const t = useT();
  const ready = problems.length === 0;
  const heading = ready ? t('sc.pf.ready.ready') : t('sc.pf.ready.missing', { count: problems.length });
  return (
    <aside aria-label={t('sc.pf.ready.label')}
      className="sticky top-[calc(var(--at-sticky-header-h)+4.25rem)] hidden max-h-[calc(100dvh-var(--at-sticky-header-h)-9rem)] flex-col gap-3 self-start overflow-y-auto rounded-md border border-border bg-surface p-4 xl:flex">
      <QualityBar percent={percent} />
      <h2 className="m-0 flex items-center gap-2 text-base font-semibold" data-testid="readiness">
        {ready ? <CheckCircle size={20} weight="fill" aria-hidden className="text-success" /> : <WarningCircle size={20} weight="fill" aria-hidden className="text-warning" />}
        {heading}
      </h2>
      {ready ? <p className="m-0 -mt-1 text-sm text-muted">{t('sc.pf.ready.readyHint')}</p> : null}
      <QualityLists problems={problems} tips={tips} onGo={onGo} />
      {preview}
    </aside>
  );
}
