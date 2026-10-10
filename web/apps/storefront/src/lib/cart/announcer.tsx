'use client';

import { createContext, type ReactNode, useCallback, useContext, useState } from 'react';

import { useT } from '@/i18n/t';

const Ctx = createContext<(message: string) => void>(() => undefined);

/** Visually hidden polite live region announcing cart changes to screen-reader users. */
export function CartAnnouncer({ children }: { children: ReactNode }) {
  const t = useT();
  const [message, setMessage] = useState('');
  const announce = useCallback((m: string) => {
    // Clear first so repeating the same text is announced again.
    setMessage('');
    requestAnimationFrame(() => setMessage(m));
  }, []);
  return (
    <Ctx.Provider value={announce}>
      {children}
      <div className="at-visually-hidden" role="status" aria-live="polite" aria-label={t('a11y.cartRegion')} data-testid="cart-live-region">
        {message}
      </div>
    </Ctx.Provider>
  );
}

export function useAnnounce(): (message: string) => void {
  return useContext(Ctx);
}
