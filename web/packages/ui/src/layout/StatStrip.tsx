import { type ReactNode } from 'react';

import { cx } from '../cx';
import styles from './StatStrip.module.css';

export type StatTone = 'neutral' | 'positive' | 'attention' | 'negative';

export interface StatItem {
  /** Stable key; defaults to the position. */
  id?: string;
  /** Small muted label. */
  label: ReactNode;
  /** The large figure (tabular numerals). */
  value: ReactNode;
  /** Optional line under the value: a trend, a unit or a note. */
  sub?: ReactNode;
  /** Colours the sub line: positive (green), attention (amber), negative (red). */
  tone?: StatTone;
  /** Makes the stat a link, e.g. a stat that opens the filtered list. */
  href?: string;
  /** Makes the stat a button, for in-page filters (use `href` when it navigates). Also runs on a link click. */
  onSelect?: () => void;
}

export interface StatStripProps {
  items: readonly StatItem[];
  /** Names the group for assistive technology (translate it). */
  label?: string;
  className?: string;
}

/**
 * Flat row of key figures: no boxes, no shadows, thin vertical rules between stats. One row from 640px up (wrapping
 * if it must); two columns on phones. A stat with `href` or `onSelect` is a link or button.
 */
export function StatStrip({ items, label, className }: StatStripProps) {
  return (
    <ul data-page-part="stats" aria-label={label} className={cx(styles.strip, className)}>
      {items.map((item, i) => {
        const body = (
          <>
            <span className={styles.label}>{item.label}</span>
            <strong className={styles.value}>{item.value}</strong>
            {item.sub ? <span className={cx(styles.sub, item.tone && item.tone !== 'neutral' && styles[item.tone])}>{item.sub}</span> : null}
          </>
        );
        return (
          <li key={item.id ?? i} className={styles.stat}>
            {item.href !== undefined ? (
              <a href={item.href} className={cx(styles.inner, styles.interactive)} onClick={item.onSelect}>
                {body}
              </a>
            ) : item.onSelect ? (
              <button type="button" className={cx(styles.inner, styles.interactive)} onClick={item.onSelect}>
                {body}
              </button>
            ) : (
              <div className={styles.inner}>{body}</div>
            )}
          </li>
        );
      })}
    </ul>
  );
}
