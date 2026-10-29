import { CheckCircle, WarningCircle } from '@phosphor-icons/react/ssr';
import { type ReactNode } from 'react';

import { useT } from '@/i18n/t';

import { type SectionKey, type SectionStatus } from '../form-model';

interface Props {
  id: SectionKey;
  title: string;
  hint?: ReactNode;
  status?: SectionStatus;
  children: ReactNode;
}

/** Completion badge of a section: a check when nothing required is open, otherwise how many items are left. */
export function SectionBadge({ status }: { status: SectionStatus }) {
  const t = useT();
  return status.state === 'complete' ? (
    <span className="inline-flex flex-none items-center gap-1 text-xs font-semibold text-success" data-testid="section-state">
      <CheckCircle size={16} weight="fill" aria-hidden />{t('sc.pf.nav.done')}
    </span>
  ) : (
    <span className="inline-flex flex-none items-center gap-1 text-xs font-semibold text-warning" data-testid="section-state">
      <WarningCircle size={16} weight="fill" aria-hidden />{t('sc.pf.nav.todo', { count: status.missing })}
    </span>
  );
}

/** A wide card of the one-page form. Its content is a container (`@container`), so the field grids inside follow the card's width and not the screen's. */
export function FormSection({ id, title, hint, status, children }: Props) {
  return (
    <section id={`pf-s-${id}`} aria-labelledby={`pf-h-${id}`} data-section={id} data-state={status?.state}
      className="scroll-mt-[calc(var(--at-sticky-header-h)+4.5rem)] rounded-md border border-border bg-surface">
      <header className="flex items-start justify-between gap-3 border-b border-border px-4 py-3 sm:px-6 sm:py-4">
        <div className="flex min-w-0 flex-col gap-0.5">
          <h2 id={`pf-h-${id}`} tabIndex={-1} className="m-0 text-lg font-semibold outline-none">{title}</h2>
          {hint ? <p className="m-0 text-sm text-muted">{hint}</p> : null}
        </div>
        {status ? <SectionBadge status={status} /> : null}
      </header>
      <div className="@container px-4 py-4 sm:px-6 sm:py-5">
        <div className="flex flex-col gap-5">{children}</div>
      </div>
    </section>
  );
}

/** Field grid inside a section: one column on a phone, two in a wide card, three in a very wide one. */
export const GRID = 'grid grid-cols-1 gap-x-4 gap-y-1 @lg:grid-cols-2 @3xl:grid-cols-3';
/** Short fields side by side (condition / price / quantity, SKU / barcode, dimensions). */
export const GRID_SHORT = 'grid grid-cols-1 gap-x-4 gap-y-1 @md:grid-cols-3';
