import { useLayoutEffect, useRef } from 'react';

/**
 * Remembers the element that had focus when an overlay opened and returns focus to it on close (WCAG 2.4.3). Radix
 * only does this for its own <Dialog.Trigger>; our overlays are opened from app state, so we do it here.
 */
export function useReturnFocus(open: boolean): (e: Event) => void {
  const opener = useRef<HTMLElement | null>(null);
  useLayoutEffect(() => {
    // Layout effects run before Radix moves focus into the content (a passive effect).
    if (open && document.activeElement instanceof HTMLElement && document.activeElement !== document.body) opener.current = document.activeElement;
  }, [open]);
  return (e: Event) => {
    const el = opener.current;
    opener.current = null;
    if (el?.isConnected) {
      e.preventDefault();
      el.focus({ preventScroll: true });
    }
  };
}
