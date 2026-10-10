'use client';

import { createContext, type ReactNode, useCallback, useContext, useEffect, useMemo, useRef, useState } from 'react';

import { cx } from '../cx';
import { CheckCircle, WarningCircle } from '../icons';
import styles from './overlay.module.css';

export interface ToastMessage {
  id: number;
  text: string;
  tone: 'info' | 'error';
}

interface ToastApi {
  show: (text: string, tone?: ToastMessage['tone']) => void;
}

const ToastContext = createContext<ToastApi>({ show: () => undefined });

/** Toast host with an ARIA live region (polite for info, assertive for errors). */
export function ToastProvider({ children, duration = 2600 }: { children: ReactNode; duration?: number }) {
  const [items, setItems] = useState<ToastMessage[]>([]);
  const seq = useRef(0);
  const timers = useRef(new Map<number, ReturnType<typeof setTimeout>>());

  const show = useCallback(
    (text: string, tone: ToastMessage['tone'] = 'info') => {
      seq.current += 1;
      const id = seq.current;
      setItems((cur) => [...cur.slice(-2), { id, text, tone }]);
      timers.current.set(id, setTimeout(() => setItems((cur) => cur.filter((t) => t.id !== id)), duration));
    },
    [duration]
  );

  useEffect(() => {
    const map = timers.current;
    return () => map.forEach((t) => clearTimeout(t));
  }, []);

  const api = useMemo(() => ({ show }), [show]);
  return (
    <ToastContext.Provider value={api}>
      {children}
      <div className={styles.toastRegion} role="status" aria-live="polite" aria-atomic="false">
        {items.filter((t) => t.tone === 'info').map((t) => (
          <div key={t.id} className={styles.toast}>
            <CheckCircle weight="fill" className={styles.toastIcon} aria-hidden />
            {t.text}
          </div>
        ))}
      </div>
      <div className={styles.toastRegion} role="alert" aria-live="assertive">
        {items.filter((t) => t.tone === 'error').map((t) => (
          <div key={t.id} className={cx(styles.toast, styles.toastError)}>
            <WarningCircle weight="fill" className={styles.toastIcon} aria-hidden />
            {t.text}
          </div>
        ))}
      </div>
    </ToastContext.Provider>
  );
}

export function useToast(): ToastApi {
  return useContext(ToastContext);
}
