import { type ReactNode } from 'react';

import { cx } from '../cx';
import styles from './PageStack.module.css';

export interface PageStackProps {
  children: ReactNode;
  className?: string;
}

/**
 * The vertical rhythm of a page body, set once from the --at-page-gap-* tokens: blocks are 24px apart, an alert sits
 * 16px above the block that follows it. Put alerts, a StatStrip, a toolbar and a table in one PageStack (the Alert and
 * StatStrip components mark themselves with data-page-part). The toolbar-to-table gap (12px) belongs to the table.
 */
export function PageStack({ children, className }: PageStackProps) {
  return <div className={cx(styles.stack, className)}>{children}</div>;
}
