'use client';

import { CaretRight } from '@phosphor-icons/react/ssr';
import { type ReactNode, useEffect, useState } from 'react';

interface Props {
  title: string;
  /** Open from the start (something inside is filled in), and opened when `force` turns true (something inside has a problem). It never closes by itself. */
  force?: boolean;
  children: ReactNode;
}

/** "More details": optional fields stay folded until wanted. */
export function Disclosure({ title, force = false, children }: Props) {
  const [open, setOpen] = useState(force);
  useEffect(() => {
    if (force) setOpen(true);
  }, [force]);
  return (
    <details open={open} onToggle={(e) => setOpen(e.currentTarget.open)} className="group flex flex-col gap-3">
      <summary className="flex cursor-pointer list-none items-center gap-1.5 text-sm font-semibold text-link [&::-webkit-details-marker]:hidden">
        <CaretRight size={12} aria-hidden className="transition-transform group-open:rotate-90" />
        {title}
      </summary>
      <div className="mt-3 flex flex-col gap-3">{children}</div>
    </details>
  );
}
