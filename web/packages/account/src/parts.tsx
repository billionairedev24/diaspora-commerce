'use client';

import { isApiError } from '@afrotransact/api-client';
import { Button, Dialog, Skeleton } from '@afrotransact/ui';
import { type ReactNode, useCallback, useEffect, useId, useRef, useState } from 'react';

import styles from './Account.module.css';
import { type AccountT, useAccountT } from './i18n/t';

export { styles };

/** Wrap the account screens in an element with this class for the flat (no boxed cards) look. */
export const flatAccountClassName: string = styles.flat ?? '';

/** The problem's message (or the field's), else a generic line. */
export function errorText(t: AccountT, e: unknown, field?: string): string {
  if (isApiError(e)) return (field && e.fieldErrors[field]) || e.message || t('common.genericError');
  return t('common.genericError');
}

export function PageHead({ title, lead }: { title: string; lead?: ReactNode }) {
  return (
    <header className={styles.pageHead}>
      <h1 className={styles.h1}>{title}</h1>
      {lead ? <p className={styles.lead}>{lead}</p> : null}
    </header>
  );
}

/** A settings block: title bar (with an optional action and badge), content, optional footnote. */
export function Card({ title, lead, badge, action, note, children, id, testId }: {
  title: ReactNode;
  lead?: ReactNode;
  badge?: ReactNode;
  action?: ReactNode;
  note?: ReactNode;
  children?: ReactNode;
  id?: string;
  testId?: string;
}) {
  const auto = useId();
  const headingId = `${id ?? auto}-h`;
  return (
    <section className={styles.card} aria-labelledby={headingId} id={id} data-testid={testId}>
      <div className={styles.cardHead}>
        <div className={styles.cardTitle}>
          <h2 id={headingId} className={styles.h2}>
            {title}
            {badge}
          </h2>
          {lead ? <p className={styles.cardLead}>{lead}</p> : null}
        </div>
        {action}
      </div>
      {children}
      {note ? <div className={styles.note}>{note}</div> : null}
    </section>
  );
}

export function Row({ icon, title, sub, actions, testId }: { icon?: ReactNode; title: ReactNode; sub?: ReactNode; actions?: ReactNode; testId?: string }) {
  return (
    <li className={styles.row} data-testid={testId}>
      <span className={styles.rowIcon} aria-hidden>
        {icon}
      </span>
      <span className={styles.rowText}>
        <span className={styles.rowTitle}>{title}</span>
        {sub ? <span className={styles.rowSub}>{sub}</span> : null}
      </span>
      {actions ? <span className={styles.rowActions}>{actions}</span> : <span />}
    </li>
  );
}

export function Status({ tone, children }: { tone: 'on' | 'off' | 'required'; children: ReactNode }) {
  return <span className={`${styles.status} ${tone === 'on' ? styles.statusOn : tone === 'off' ? styles.statusOff : styles.statusRequired}`}>{children}</span>;
}

/** Accessible on/off switch (a checkbox with role="switch"). */
export function Switch({ checked, onChange, label, disabled }: { checked: boolean; onChange: (v: boolean) => void; label: string; disabled?: boolean }) {
  return (
    <span className={styles.switch}>
      <input type="checkbox" role="switch" aria-label={label} checked={checked} disabled={disabled} onChange={(e) => onChange(e.target.checked)} />
      <span aria-hidden />
    </span>
  );
}

export function Loading({ height = 120 }: { height?: number }) {
  return <Skeleton height={height} />;
}

export function LoadError({ onRetry }: { onRetry: () => void }) {
  const t = useAccountT();
  return (
    <div role="alert" className={styles.cardBody}>
      <p className={styles.error}>{t('common.loadError')}</p>
      <div>
        <Button size="sm" variant="neutral" onClick={onRetry}>
          {t('common.retry')}
        </Button>
      </div>
    </div>
  );
}

/** Yes/no confirmation dialog for destructive actions. */
export function Confirm({ open, title, body, confirmLabel, onConfirm, onClose, busy }: {
  open: boolean;
  title: string;
  body: ReactNode;
  confirmLabel: string;
  onConfirm: () => void;
  onClose: () => void;
  busy?: boolean;
}) {
  const t = useAccountT();
  return (
    <Dialog open={open} onOpenChange={(o) => !o && onClose()} title={title} closeLabel={t('common.close')} width={440}>
      <p className={styles.muted}>{body}</p>
      <div className={`${styles.actions} ${styles.actionsEnd}`}>
        <Button variant="neutral" onClick={onClose}>
          {t('common.cancel')}
        </Button>
        <Button variant="dark" onClick={onConfirm} loading={busy}>
          {confirmLabel}
        </Button>
      </div>
    </Dialog>
  );
}

/** Copies text and reports "Copied" for two seconds. */
export function useCopy(): [(text: string) => Promise<void>, boolean] {
  const [copied, setCopied] = useState(false);
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);
  useEffect(() => () => {
    if (timer.current) clearTimeout(timer.current);
  }, []);
  const copy = useCallback(async (text: string) => {
    try {
      await navigator.clipboard.writeText(text);
    } catch {
      // Clipboard blocked (insecure context): the text stays selectable on screen.
    }
    setCopied(true);
    if (timer.current) clearTimeout(timer.current);
    timer.current = setTimeout(() => setCopied(false), 2000);
  }, []);
  return [copy, copied];
}

/** Starts a file download of text (recovery codes). */
export function downloadText(filename: string, text: string): void {
  const url = URL.createObjectURL(new Blob([text], { type: 'text/plain;charset=utf-8' }));
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}

/** Shows the first `limit` items of a long list with a "Show all (n)" / "Show fewer" toggle. */
export function useShowMore<T>(items: T[], limit = 5): { visible: T[]; toggle: ReactNode } {
  const t = useAccountT();
  const [all, setAll] = useState(false);
  if (items.length <= limit) return { visible: items, toggle: null };
  return {
    visible: all ? items : items.slice(0, limit),
    toggle: (
      <div className={styles.note}>
        <button type="button" className={styles.link} aria-expanded={all} onClick={() => setAll((v) => !v)}>
          {all ? t('common.showFewer') : t('common.showAll', { n: items.length })}
        </button>
      </div>
    )
  };
}
