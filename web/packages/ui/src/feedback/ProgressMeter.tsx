import { CheckCircle, XCircle } from '@phosphor-icons/react/ssr';
import { type ReactNode } from 'react';

import { cx } from '../cx';
import styles from './ProgressMeter.module.css';

export interface ProgressMeterProps {
  /** What is being counted ("Stripe Tax"); names the progress bar for assistive technology. */
  label: string;
  value: number;
  max: number;
  /** The figure in words, shown at the end of the heading row and read out as the value ("78 of 104 outcomes verified"). */
  valueText: string;
  /** One line in words under the bar. Progress is never colour or length alone. */
  help?: ReactNode;
  /** Amber bar while the work is unfinished. */
  attention?: boolean;
  className?: string;
}

/** A flat progress bar with its figure written out and a one-line explanation (completeness of a provider's codes). */
export function ProgressMeter({ label, value, max, valueText, help, attention, className }: ProgressMeterProps) {
  const pct = max > 0 ? Math.min(100, Math.max(0, Math.round((value / max) * 100))) : 100;
  return (
    <div className={cx(styles.meter, attention && styles.attention, className)}>
      <div className={styles.head}>
        <strong className={styles.label}>{label}</strong>
        <span>{valueText}</span>
      </div>
      <div role="progressbar" aria-label={label} aria-valuemin={0} aria-valuemax={max} aria-valuenow={value} aria-valuetext={valueText} className={styles.track}>
        <div className={styles.bar} style={{ inlineSize: `${pct}%` }} />
      </div>
      {help ? <p className={styles.help}>{help}</p> : null}
    </div>
  );
}

export interface ChecklistItem {
  key: string;
  title: string;
  ok: boolean;
  /** The result in words. */
  detail: ReactNode;
}

export interface ChecklistProps {
  items: readonly ChecklistItem[];
  /** Names the list for assistive technology (translate it). */
  label: string;
  /** Spoken after a passing / failing item's title (translate them). Default "passes" / "needs work". */
  passText?: string;
  failText?: string;
  className?: string;
}

/** Pass / fail checks, each with a mark that is also written as hidden text, so a result is never colour alone. */
export function Checklist({ items, label, passText = 'passes', failText = 'needs work', className }: ChecklistProps) {
  return (
    <ul aria-label={label} className={cx(styles.list, className)}>
      {items.map((it) => (
        <li key={it.key} className={styles.item}>
          {it.ok ? <CheckCircle size={18} weight="fill" aria-hidden className={cx(styles.icon, styles.ok)} /> : <XCircle size={18} weight="fill" aria-hidden className={cx(styles.icon, styles.fail)} />}
          <span>
            <strong className={styles.title}>{it.title}</strong>
            <span className="at-visually-hidden">: {it.ok ? passText : failText}</span>
            {' — '}
            {it.detail}
          </span>
        </li>
      ))}
    </ul>
  );
}
