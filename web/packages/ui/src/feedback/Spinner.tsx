import { forwardRef, type HTMLAttributes } from 'react';

import { cx } from '../cx';
import { CircleNotch } from '../icons';
import styles from './Spinner.module.css';

export type SpinnerSize = 'xs' | 'sm' | 'md' | 'lg' | 'xl';
export type SpinnerVariant = 'primary' | 'neutral' | 'muted' | 'white';

const SIZES: Record<SpinnerSize, number> = {
  xs: 14,
  sm: 18,
  md: 24,
  lg: 32,
  xl: 44
};

export interface SpinnerProps extends HTMLAttributes<HTMLDivElement> {
  /** Size of the spinner icon. Defaults to 'md' (24px) or 'lg' if center is true. */
  size?: SpinnerSize;
  /** Color theme of the spinner. Defaults to 'primary'. */
  variant?: SpinnerVariant;
  /** Visible or accessible text status label (e.g. 'Loading…'). */
  label?: string;
  /** If true, the label is visually hidden but read by screen readers. */
  srOnlyLabel?: boolean;
  /** Display as block (full-width flex). */
  block?: boolean;
  /** Centered block with comfortable min-height and muted surface background (standard page/section loading state). */
  center?: boolean;
}

/**
 * AfroTransact standard accessible Spinner component.
 * Used consistently across the entire ecosystem from auth & onboarding to dashboards and operations.
 */
export const Spinner = forwardRef<HTMLDivElement, SpinnerProps>(function Spinner(
  {
    size,
    variant = 'primary',
    label,
    srOnlyLabel = false,
    block = false,
    center = false,
    className,
    ...rest
  },
  ref
) {
  const iconSize = SIZES[size ?? (center ? 'lg' : 'md')];

  return (
    <div
      ref={ref}
      role="status"
      aria-live="polite"
      className={cx(
        styles.container,
        (block || center) && styles.block,
        center && styles.center,
        className
      )}
      {...rest}
    >
      <CircleNotch
        size={iconSize}
        weight="bold"
        aria-hidden="true"
        className={cx(styles.spinner, styles[variant])}
      />
      {label ? (
        <span className={srOnlyLabel ? 'sr-only' : styles.label}>
          {label}
        </span>
      ) : (
        <span className="sr-only">Loading…</span>
      )}
    </div>
  );
});
