import { type ReactNode } from 'react';

import { cx } from '../cx';

export type SheetTableScrollProps = {
  children: ReactNode;
  className?: string;
};

/** Wraps a table inside a Sheet: horizontal scroll and single-line cells (no mid-word wrapping). */
export function SheetTableScroll({ children, className }: SheetTableScrollProps) {
  return <div className={cx('at-sheet-table-scroll', className)}>{children}</div>;
}
