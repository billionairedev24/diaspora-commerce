'use client';

import { cx } from '../cx';
import { Minus, Plus, Trash } from '../icons';
import styles from './commerce.module.css';

export interface QuantityStepperProps {
  value: number;
  /** Highest allowed value — the available stock (capped at 99). */
  max: number;
  onChange: (next: number) => void;
  /** Called instead of onChange(0) when decreasing from 1 (the minus becomes a bin). */
  onRemove?: () => void;
  busy?: boolean;
  /** "3 in cart" vs "3". */
  format?: (value: number) => string;
  size?: 'sm' | 'md';
  block?: boolean;
  /** Item name for accessible labels ("Increase quantity of Jollof Seasoning"). */
  itemLabel?: string;
  className?: string;
  decreaseLabel?: string;
  increaseLabel?: string;
  removeLabel?: string;
  maxReachedLabel?: (max: number) => string;
}

/** − / + control that replaces "Add to cart" once an item is in the cart; + stops at stock. */
export function QuantityStepper({
  value,
  max,
  onChange,
  onRemove,
  busy,
  format = (v) => String(v),
  size = 'sm',
  block,
  itemLabel,
  className,
  decreaseLabel = 'Decrease quantity',
  increaseLabel = 'Increase quantity',
  removeLabel = 'Remove from cart',
  maxReachedLabel = (m) => `Only ${m} available`
}: QuantityStepperProps) {
  const atMax = value >= max;
  const removing = value <= 1;
  const of = itemLabel ? ` of ${itemLabel}` : '';
  return (
    <div className={cx(styles.stepper, size === 'md' && styles['stepper-md'], block && styles.stepperBlock, className)} role="group" aria-label={itemLabel ? `Quantity of ${itemLabel}` : 'Quantity'} aria-busy={busy || undefined}>
      <button
        type="button"
        className={styles.stepBtn}
        disabled={busy}
        onClick={() => (removing ? (onRemove ? onRemove() : onChange(0)) : onChange(value - 1))}
        aria-label={removing ? removeLabel + (itemLabel ? `: ${itemLabel}` : '') : decreaseLabel + of}
      >
        {removing ? <Trash size={16} aria-hidden /> : <Minus size={16} aria-hidden />}
      </button>
      <span className={styles.stepValue} aria-live="polite" aria-atomic="true">
        {format(value)}
      </span>
      <button
        type="button"
        className={styles.stepBtn}
        disabled={busy || atMax}
        onClick={() => onChange(value + 1)}
        aria-label={increaseLabel + of}
        title={atMax ? maxReachedLabel(max) : undefined}
      >
        <Plus size={16} aria-hidden />
      </button>
    </div>
  );
}
