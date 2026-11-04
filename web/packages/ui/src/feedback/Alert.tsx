import { type AnchorHTMLAttributes, type ButtonHTMLAttributes, type ReactNode } from 'react';

import { cx } from '../cx';
import { CheckCircle, type Icon, Info, Warning, WarningCircle, X } from '../icons';
import styles from './Alert.module.css';

export type AlertVariant = 'info' | 'success' | 'warning' | 'danger' | 'neutral';

const ICONS: Record<AlertVariant, Icon> = { info: Info, success: CheckCircle, warning: Warning, danger: WarningCircle, neutral: Info };

export interface AlertProps {
  /** Meaning: info (blue), success (green), warning (amber), danger (red) or neutral (grey). Defaults to info. */
  variant?: AlertVariant;
  /** Bold lead-in, run in before the message. */
  title?: ReactNode;
  /** The message. */
  children?: ReactNode;
  /** A different Phosphor icon for the same variant (e.g. a shield for compliance). */
  icon?: Icon;
  /** One inline action, link-style: pass an `AlertAction`, or any `<a>` / `<button>`. */
  action?: ReactNode;
  /** Shows a dismiss button; called when it is pressed. The caller removes the alert. */
  onDismiss?: () => void;
  /** Accessible name of the dismiss button (translate it). Defaults to "Dismiss". */
  dismissLabel?: string;
  /** Full width of the container. Rare: by default the alert fits its content up to a readable maximum (720px). */
  fill?: boolean;
  /**
   * A warning that needs attention now: announced assertively (role="alert"), like danger. Other warnings, info,
   * success and neutral are polite status messages.
   */
  urgent?: boolean;
  /** Overrides the role derived from the variant (danger and urgent warnings: "alert"; the rest: "status"). */
  role?: 'alert' | 'status';
  className?: string;
  id?: string;
}

/** The role a variant gets: assertive only for danger and urgent warnings, so a page of notices stays quiet. */
export function alertRole(variant: AlertVariant, urgent = false): 'alert' | 'status' {
  return variant === 'danger' || (variant === 'warning' && urgent) ? 'alert' : 'status';
}

/**
 * Flat, compact alert: a tinted strip with an accent bar on the start edge, a status icon, an optional bold title,
 * the message, one inline link-style action and an optional dismiss. Fits its content (max 720px) unless `fill`.
 * Logical properties throughout, so it mirrors in RTL.
 */
export function Alert({ variant = 'info', title, children, icon, action, onDismiss, dismissLabel = 'Dismiss', fill, urgent, role, className, id }: AlertProps) {
  const Glyph = icon ?? ICONS[variant];
  return (
    <div id={id} data-page-part="alert" role={role ?? alertRole(variant, urgent)} data-variant={variant} data-fill={fill ? 'true' : undefined} className={cx(styles.alert, styles[variant], fill && styles.fill, className)}>
      <Glyph className={styles.icon} size={20} weight="fill" aria-hidden />
      <div className={styles.body}>
        {title ? <strong className={styles.title}>{title}</strong> : null} {children}
      </div>
      {action ? <div className={styles.action}>{action}</div> : null}
      {onDismiss ? (
        <button type="button" className={styles.dismiss} aria-label={dismissLabel} title={dismissLabel} onClick={onDismiss}>
          <X size={16} aria-hidden />
        </button>
      ) : null}
    </div>
  );
}

type AlertActionProps = ({ href: string } & AnchorHTMLAttributes<HTMLAnchorElement>) | ({ href?: undefined } & ButtonHTMLAttributes<HTMLButtonElement>);

/** The alert's inline action: a link-style button (or a link when `href` is given), never a pill. */
export function AlertAction(props: AlertActionProps) {
  if (props.href !== undefined) {
    const { className, children, ...rest } = props;
    return (
      <a {...rest} className={cx(styles.link, className)}>
        {children}
      </a>
    );
  }
  const { className, type = 'button', ...rest } = props;
  return <button {...rest} type={type} className={cx(styles.link, className)} />;
}
