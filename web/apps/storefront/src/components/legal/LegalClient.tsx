'use client';

import { Printer } from '@afrotransact/ui';
import { usePathname } from 'next/navigation';
import { useEffect, useRef } from 'react';

import styles from './Legal.module.css';

export function PrintButton({ label }: { label: string }) {
  return (
    <button type="button" className={styles.printBtn} onClick={() => window.print()} data-noprint>
      <Printer aria-hidden size={16} />
      {label}
    </button>
  );
}

/** Moves focus to the document heading after switching documents (not on the first load), as the design does. */
export function FocusHeadingOnNavigate({ targetId }: { targetId: string }) {
  const pathname = usePathname();
  const first = useRef(true);
  useEffect(() => {
    if (first.current) {
      first.current = false;
      return;
    }
    document.getElementById(targetId)?.focus({ preventScroll: false });
  }, [pathname, targetId]);
  return null;
}
