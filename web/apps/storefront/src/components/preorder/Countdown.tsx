'use client';

import { countdownBlocks, countdownParts, formatCountdownShort } from '@afrotransact/api-client';
import { cx } from '@afrotransact/ui';
import { useEffect, useRef } from 'react';

import { useT } from '@/i18n/t';
import { useNow } from '@/lib/preorder/useNow';

import styles from './Preorder.module.css';

export interface CountdownProps {
  /** ISO instant the countdown runs to (resolution.countdownTo). */
  to: string;
  /** "Closes in" / "Opens in" / "Delivers in". */
  label: string;
  /** chip: ribbon chip · inline: "Closes in 3d 04h…" (PDP, cart) · blocks: hero tiles · bar: the window page strip. */
  variant: 'chip' | 'inline' | 'blocks' | 'bar';
  /** Called once when the target passes (refetch the resolution: the stage has moved on). */
  onElapsed?: () => void;
  className?: string;
}

/**
 * Live countdown that ticks every second without spamming screen readers: the ticking digits are aria-hidden and the
 * role="timer" region (aria-live off) carries a minute-precision label. Server HTML shows placeholders, so the
 * hydrating render matches; the client then counts.
 */
export function Countdown({ to, label, variant, onElapsed, className }: CountdownProps) {
  const t = useT();
  const now = useNow();
  const c = now === null ? null : countdownParts(to, now);
  const fired = useRef(false);
  useEffect(() => {
    if (c && c.remainingMs === 0 && !fired.current) {
      fired.current = true;
      onElapsed?.();
    }
  }, [c, onElapsed]);

  const aria = c ? t('preorder.cd.aria', { label, days: c.days, hours: c.hours, minutes: c.minutes }) : label;
  const short = c ? formatCountdownShort(c) : '–d ––h ––m ––s';
  const blocks = c ? countdownBlocks(c) : (['–', '––', '––', '––'] as const);
  const units = [t('preorder.cd.days'), t('preorder.cd.hrs'), t('preorder.cd.min'), t('preorder.cd.sec')];

  if (variant === 'chip')
    return (
      <span role="timer" aria-live="off" aria-label={aria} className={cx(styles.cdChip, className)} data-testid="preorder-countdown">
        <span aria-hidden>
          {label} {short}
        </span>
      </span>
    );
  if (variant === 'inline')
    return (
      <span role="timer" aria-live="off" aria-label={aria} className={className} data-testid="preorder-countdown">
        <span aria-hidden>
          {label} <span className={styles.mono}>{short}</span>
        </span>
      </span>
    );
  if (variant === 'bar')
    return (
      <span role="timer" aria-live="off" aria-label={aria} className={cx(styles.cdBar, className)} data-testid="preorder-countdown">
        <span className={styles.cdBarLabel} aria-hidden>
          {label}
        </span>
        <span className={styles.cdBarValues} aria-hidden>
          {blocks.map((v, i) => (
            <span key={units[i]} className={styles.cdBarUnit}>
              <strong className={styles.cdBarValue}>{v}</strong>
              <span className={styles.cdBarUnitLabel}>{units[i]}</span>
            </span>
          ))}
        </span>
      </span>
    );
  return (
    <span role="timer" aria-live="off" aria-label={aria} className={cx(styles.cdBlocksWrap, className)} data-testid="preorder-countdown">
      <span className={styles.cdBlocksLabel} aria-hidden>
        {label}
      </span>
      <span className={styles.cdBlocks} aria-hidden>
        {blocks.map((v, i) => (
          <span key={units[i]} className={styles.cdBlock}>
            <strong className={styles.cdBlockValue}>{v}</strong>
            <span className={styles.cdBlockUnit}>{units[i]}</span>
          </span>
        ))}
      </span>
    </span>
  );
}
