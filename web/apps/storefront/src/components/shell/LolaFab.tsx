'use client';

import { ChatCircleDots, X } from '@afrotransact/ui';
import { usePathname } from 'next/navigation';
import { useEffect, useState } from 'react';

import { useT } from '@/i18n/t';

import styles from './LolaFab.module.css';
import { lolaHiddenOn, useLolaLauncher } from './LolaLauncher';

/** localStorage flag: the first-visit label was shown to this browser. */
export const LOLA_LABEL_KEY = 'at_lola_label_seen';
/** The label stays this long, then goes away on its own. */
const LABEL_MS = 15000;

const storage = {
  seen(): boolean {
    try {
      return localStorage.getItem(LOLA_LABEL_KEY) === '1';
    } catch {
      // Storage blocked (private mode): treat the visitor as new, once per page load.
      return false;
    }
  },
  remember() {
    try {
      localStorage.setItem(LOLA_LABEL_KEY, '1');
    } catch {
      /* the label may show again next visit; harmless */
    }
  }
};

/**
 * The one way into Lola: a round chat launcher fixed to the bottom end corner (bottom-right; bottom-left in right-to-left
 * languages), like WhatsApp's. It sits above the cookie banner and any bottom action bar, and is not shown on the checkout.
 * A small label appears on a browser's first visit only; it is dismissible and remembered.
 */
export function LolaFab() {
  const t = useT();
  const pathname = usePathname();
  const lola = useLolaLauncher();
  const [label, setLabel] = useState(false);
  const hidden = lolaHiddenOn(pathname);

  useEffect(() => {
    if (hidden || storage.seen()) return;
    setLabel(true);
    // Shown once: a reload no longer shows it, even if it was neither dismissed nor used.
    storage.remember();
    const timer = setTimeout(() => setLabel(false), LABEL_MS);
    return () => clearTimeout(timer);
  }, [hidden]);

  if (hidden) return null;

  return (
    <div className={styles.fab} data-noprint data-testid="lola-fab">
      {label && !lola.isOpen && (
        <div className={styles.label} data-testid="lola-fab-label">
          <span>{t('lola.fab.label')}</span>
          <button type="button" className={styles.dismiss} onClick={() => setLabel(false)} aria-label={t('lola.fab.dismiss')}>
            <X size={14} aria-hidden />
          </button>
        </div>
      )}
      <button
        type="button"
        className={styles.button}
        onClick={() => {
          setLabel(false);
          lola.open();
        }}
        aria-label={t('lola.fab.aria')}
        aria-haspopup="dialog"
        aria-expanded={lola.isOpen}
        data-testid="lola-launcher"
        data-tour="lola-launcher"
      >
        <ChatCircleDots size={30} weight="fill" aria-hidden />
      </button>
    </div>
  );
}
