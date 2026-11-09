import { type ReactNode, useId } from 'react';

import { Button } from '../button/Button';
import { cx } from '../cx';
import styles from './dashboard.module.css';
import { type LinkLike } from './EmptyPrompt';

export type ModuleTone = 'neutral' | 'positive' | 'attention' | 'negative';

export interface ModuleAction {
  label: string;
  href: string;
  /** Decorative icon (the label names the action). */
  icon?: ReactNode;
}

export interface ModuleSectionProps {
  /** Small icon beside the module name (decorative). */
  icon?: ReactNode;
  title: string;
  /** `ready` shows the figures; `empty` the `empty` slot; `loading` a skeleton; `error` a message with Retry. */
  state?: 'ready' | 'empty' | 'loading' | 'error';
  /** The ONE headline number with its label and period ("Last 30 days"). */
  headline?: { value: ReactNode; label: string; period?: string; tone?: ModuleTone };
  /** Compact chart (Sparkline, MiniBars or Donut). */
  chart?: ReactNode;
  /** Secondary total line under the chart ("128 orders · 94 units"). */
  totalLine?: ReactNode;
  /** Up to three quick actions. */
  actions?: readonly ModuleAction[];
  /** Friendly empty state (an EmptyPrompt), shown when `state` is `empty`. */
  empty?: ReactNode;
  /** Error text and retry (state `error`). */
  errorMessage?: string;
  retryLabel?: string;
  onRetry?: () => void;
  linkAs?: LinkLike;
  className?: string;
}

/**
 * One module of a dashboard home: icon + name, one headline figure with its period, a compact chart, a total line and a
 * row of quick actions. Flat: a single hairline above, no card, no shadow. Empty modules show an illustrated prompt
 * instead of zeros.
 */
export function ModuleSection({
  icon,
  title,
  state = 'ready',
  headline,
  chart,
  totalLine,
  actions = [],
  empty,
  errorMessage = 'We couldn’t load this just now.',
  retryLabel = 'Try again',
  onRetry,
  linkAs,
  className
}: ModuleSectionProps) {
  const Link = linkAs ?? 'a';
  const headingId = useId();
  return (
    <section aria-labelledby={headingId} aria-busy={state === 'loading' || undefined} data-state={state} className={cx(styles.module, className)}>
      <header className={styles.moduleHead}>
        {icon ? <span className={styles.moduleIcon} aria-hidden>{icon}</span> : null}
        <h2 id={headingId} className={styles.moduleTitle}>
          {title}
        </h2>
      </header>
      {state === 'loading' ? (
        <div className={styles.skeleton} role="status" aria-label={`Loading ${title}`}>
          <span className={styles.skelLine} style={{ inlineSize: '40%', blockSize: '2rem' }} />
          <span className={styles.skelLine} style={{ inlineSize: '100%', blockSize: '2.75rem' }} />
          <span className={styles.skelLine} style={{ inlineSize: '60%' }} />
        </div>
      ) : null}
      {state === 'error' ? (
        <div className={styles.error} role="alert">
          <p className={styles.errorText}>{errorMessage}</p>
          {onRetry ? (
            <Button size="sm" variant="neutral" onClick={onRetry}>
              {retryLabel}
            </Button>
          ) : null}
        </div>
      ) : null}
      {state === 'empty' ? empty : null}
      {state === 'ready' ? (
        <div className={styles.body}>
          {headline ? (
            <p className={styles.headline}>
              <strong className={cx(styles.headlineValue, headline.tone && headline.tone !== 'neutral' && styles[headline.tone])}>{headline.value}</strong>
              <span className={styles.headlineLabel}>{headline.label}</span>
              {headline.period ? <span className={styles.period}>{headline.period}</span> : null}
            </p>
          ) : null}
          {chart ? <div className={styles.chart}>{chart}</div> : null}
          {totalLine ? <p className={styles.total}>{totalLine}</p> : null}
        </div>
      ) : null}
      {actions.length && state !== 'loading' && state !== 'error' && state !== 'empty' ? (
        <ul className={styles.actions} aria-label={`${title} quick actions`}>
          {actions.slice(0, 3).map((a) => (
            <li key={a.href + a.label}>
              <Link href={a.href} className={styles.action}>
                {a.icon ? <span aria-hidden className={styles.actionIcon}>{a.icon}</span> : null}
                <span>{a.label}</span>
              </Link>
            </li>
          ))}
        </ul>
      ) : null}
    </section>
  );
}

export interface ModuleGridProps {
  children: ReactNode;
  className?: string;
}

/** The home grid: modules sit side by side from tablet up and stack to one column on a phone. */
export function ModuleGrid({ children, className }: ModuleGridProps) {
  return <div className={cx(styles.grid, className)}>{children}</div>;
}
