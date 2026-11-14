import { type HTMLAttributes } from 'react';

import { cx } from '../cx';
import styles from './Toolbar.module.css';

/**
 * The class that puts page-level controls on one centre line: Fields (a label above the control), chip groups and
 * buttons align on the controls' bottom edge, a Field's reserved message row is dropped (its help or error hangs
 * under the control), and small buttons and chips take the full control height. Use it on a grid when the row is
 * not a plain wrap; otherwise use {@link Toolbar}.
 */
export const controlRowClassName = styles.controls ?? '';

export type ToolbarProps = HTMLAttributes<HTMLDivElement>;

/** A wrapping row of filters, toggles and buttons that share one centre line and one height (see {@link controlRowClassName}). */
export function Toolbar({ className, ...rest }: ToolbarProps) {
  return <div {...rest} className={cx(styles.toolbar, styles.controls, className)} />;
}
