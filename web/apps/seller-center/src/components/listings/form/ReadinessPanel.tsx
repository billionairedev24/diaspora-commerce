'use client';

import { Button, cx } from '@afrotransact/ui';
import { CaretUp, CheckCircle, WarningCircle } from '@phosphor-icons/react/ssr';
import { type ReactNode, useState } from 'react';

import { type MessageKey, useT } from '@/i18n/t';

import { type Problem } from '../form-model';

interface Props {
  problems: [string, Problem][];
  /** Autosave state line ("Draft saved 10:42"). */
  saveState: ReactNode;
  preview: ReactNode;
  primaryLabel: string;
  onPrimary: () => void;
  onSaveDraft: (() => void) | null;
  busy: boolean;
  savingDraft: boolean;
}

/** Moves to a field and focuses it (the readiness links). */
export function goToField(id: string) {
  const el = typeof document !== 'undefined' ? document.getElementById(id) : null;
  if (!el) return;
  el.scrollIntoView({ behavior: 'smooth', block: 'center' });
  el.focus({ preventScroll: true });
}

function Items({ problems, onGo }: { problems: [string, Problem][]; onGo: (id: string) => void }) {
  const t = useT();
  const label = (p: Problem) => (typeof p.label === 'string' ? t(p.label) : p.label.key ? `${p.label.text} · ${t(p.label.key)}` : p.label.text);
  return (
    <ul className="m-0 flex list-none flex-col gap-1 p-0">
      {problems.map(([id, p]) => (
        <li key={id}>
          <a href={`#${id}`} onClick={(e) => { e.preventDefault(); onGo(id); }} className="flex flex-col rounded-sm px-2 py-1 text-sm hover:bg-surface-hover">
            <span className="font-semibold text-text">{label(p)}</span>
            <span className="text-xs text-danger">{t(p.key as MessageKey, p.vars)}</span>
          </a>
        </li>
      ))}
    </ul>
  );
}

/** Sticky readiness panel: "Ready to publish" or "N things missing" with links to each field, autosave state and the actions.
    On phones it is a bar at the bottom of the screen whose list opens upwards. */
export function ReadinessPanel({ problems, saveState, preview, primaryLabel, onPrimary, onSaveDraft, busy, savingDraft }: Props) {
  const t = useT();
  const [open, setOpen] = useState(false);
  const ready = problems.length === 0;
  const heading = ready ? t('sc.pf.ready.ready') : t('sc.pf.ready.missing', { count: problems.length });
  const icon = ready ? <CheckCircle size={20} weight="fill" aria-hidden className="text-success" /> : <WarningCircle size={20} weight="fill" aria-hidden className="text-warning" />;
  const actions = (
    <div className="flex flex-col gap-2">
      <Button block loading={busy} onClick={onPrimary} data-testid="pf-primary">{primaryLabel}</Button>
      {onSaveDraft ? <Button block variant="neutral" loading={savingDraft} onClick={onSaveDraft}>{t('sc.pf.actions.saveDraft')}</Button> : null}
    </div>
  );
  return (
    <>
      <aside aria-label={t('sc.pf.ready.label')} className="sticky top-[calc(var(--at-sticky-header-h)+1rem)] hidden flex-col gap-3 self-start rounded-md border border-border bg-surface p-4 lg:flex">
        <h2 className="m-0 flex items-center gap-2 text-lg font-semibold" data-testid="readiness">{icon}{heading}</h2>
        {ready ? <p className="m-0 text-sm text-muted">{t('sc.pf.ready.readyHint')}</p> : (
          <div className="flex max-h-[40vh] flex-col overflow-y-auto"><Items problems={problems} onGo={goToField} /></div>
        )}
        {actions}
        <p className="m-0 text-xs text-muted" aria-live="polite">{saveState}</p>
        {preview}
      </aside>
      <div className="fixed inset-x-0 bottom-0 z-30 border-t border-border bg-surface px-4 pt-2 pb-[calc(0.5rem+env(safe-area-inset-bottom,0px))] shadow-lg lg:hidden">
        {open && !ready ? (
          <div id="pf-ready-list" className="mb-2 max-h-[45vh] overflow-y-auto">
            <Items problems={problems} onGo={(id) => { setOpen(false); goToField(id); }} />
          </div>
        ) : null}
        <div className="flex items-center gap-2">
          <button type="button" aria-expanded={ready ? undefined : open} aria-controls={ready ? undefined : 'pf-ready-list'} disabled={ready} onClick={() => setOpen(!open)}
            className="flex min-w-0 flex-1 items-center gap-1.5 border-0 bg-transparent p-0 text-left text-sm font-semibold text-text">
            {icon}<span className="truncate" data-testid="readiness-mobile">{heading}</span>
            {ready ? null : <CaretUp size={14} aria-hidden className={cx('transition-transform', !open && 'rotate-180')} />}
          </button>
          {onSaveDraft ? <Button size="sm" variant="neutral" loading={savingDraft} onClick={onSaveDraft}>{t('sc.pf.actions.saveDraftShort')}</Button> : null}
          <Button size="sm" loading={busy} onClick={onPrimary}>{primaryLabel}</Button>
        </div>
        <p className="m-0 mt-1 text-2xs text-muted">{saveState}</p>
      </div>
    </>
  );
}
