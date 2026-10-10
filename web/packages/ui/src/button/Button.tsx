import { type ButtonHTMLAttributes, forwardRef, type ReactNode } from 'react';

import { cx } from '../cx';
import { CircleNotch } from '../icons';
import styles from './Button.module.css';

export type ButtonVariant = 'primary' | 'secondary' | 'neutral' | 'dark' | 'link';
export type ButtonSize = 'sm' | 'md' | 'lg';

export interface ButtonStyleOptions {
  variant?: ButtonVariant;
  size?: ButtonSize;
  block?: boolean;
}

/** Class names for a button look — use on links (e.g. Next <Link>) that should look like buttons. */
export function buttonClassName({ variant = 'primary', size = 'md', block = false }: ButtonStyleOptions = {}, extra?: string): string {
  return cx(styles.button, styles[variant], variant !== 'link' && styles[size], block && styles.block, extra);
}

export interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement>, ButtonStyleOptions {
  loading?: boolean;
  /** Icon rendered before the label. */
  icon?: ReactNode;
}

/** Pill button: gold primary (Add to cart, Search), green secondary (Buy now), neutral outline, black (passkey) or link. */
export const Button = forwardRef<HTMLButtonElement, ButtonProps>(function Button(
  { variant = 'primary', size = 'md', block, loading = false, icon, className, children, disabled, type = 'button', ...rest },
  ref
) {
  return (
    <button
      ref={ref}
      type={type}
      className={buttonClassName({ variant, size, block }, className)}
      disabled={disabled || loading}
      aria-busy={loading || undefined}
      {...rest}
    >
      {loading ? <CircleNotch aria-hidden className={styles.spinner} size={18} /> : icon}
      {children}
    </button>
  );
});

export interface IconButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  /** Accessible name (required — icon buttons have no visible text). */
  label: string;
  /** Hover style for dark chrome (header): white ring instead of a fill. */
  onDark?: boolean;
}

/** Square 44px icon-only button with an accessible label. */
export const IconButton = forwardRef<HTMLButtonElement, IconButtonProps>(function IconButton({ label, onDark, className, children, type = 'button', ...rest }, ref) {
  return (
    <button ref={ref} type={type} aria-label={label} title={label} className={cx(styles.iconButton, onDark && styles.onDark, className)} {...rest}>
      {children}
    </button>
  );
});
