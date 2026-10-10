import { useEffect, useRef } from 'react';

import styles from './Field.module.css';

export interface ErrorSummaryItem {
  /** id of the field the error belongs to (the link moves focus there). */
  fieldId: string;
  message: string;
}

export interface ErrorSummaryProps {
  /** e.g. "There are 2 problems with this form". */
  title: string;
  errors: ErrorSummaryItem[];
  /** Changes on every failed submit so focus moves to the summary again (e.g. a submit counter). */
  submitCount?: number;
}

/**
 * Error summary at the top of a form (WCAG 3.3.1, 3.3.3): lists every error and links to its field. It takes focus
 * after a failed submit so screen-reader and keyboard users land on it; following a link focuses the field.
 */
export function ErrorSummary({ title, errors, submitCount }: ErrorSummaryProps) {
  const ref = useRef<HTMLDivElement>(null);
  const shown = errors.length > 0;
  useEffect(() => {
    if (shown) ref.current?.focus();
  }, [shown, submitCount]);
  if (!shown) return null;
  return (
    <div ref={ref} className={styles.summary} role="alert" tabIndex={-1} aria-labelledby="error-summary-title">
      <p id="error-summary-title" className={styles.summaryTitle}>
        {title}
      </p>
      <ul className={styles.summaryList}>
        {errors.map((e) => (
          <li key={e.fieldId}>
            <a
              href={`#${e.fieldId}`}
              onClick={(ev) => {
                const el = document.getElementById(e.fieldId);
                if (!el) return;
                ev.preventDefault();
                el.focus();
                el.scrollIntoView({ block: 'center' });
              }}
            >
              {e.message}
            </a>
          </li>
        ))}
      </ul>
    </div>
  );
}
