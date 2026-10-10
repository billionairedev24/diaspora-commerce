import { cx } from '@afrotransact/ui';
import { type ReactNode } from 'react';

export function Card({ title, actions, children, className }: { title?: ReactNode; actions?: ReactNode; children: ReactNode; className?: string }) {
  return (
    <section className={cx('flex min-w-0 flex-col gap-3 rounded-md border border-border bg-surface p-4', className)}>
      {title || actions ? (
        <div className="flex flex-wrap items-center gap-2">
          {title ? <h2 className="m-0 flex-1 text-md font-semibold">{title}</h2> : null}
          {actions}
        </div>
      ) : null}
      {children}
    </section>
  );
}
