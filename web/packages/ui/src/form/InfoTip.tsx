'use client';

import { type CSSProperties, type KeyboardEvent as ReactKeyboardEvent, type ReactNode, useEffect, useId, useLayoutEffect, useRef, useState } from 'react';

import { Info } from '../icons';
import styles from './InfoTip.module.css';

export interface InfoTipProps {
  /** Accessible name of the button ("About the main photo"). */
  label: string;
  children: ReactNode;
  className?: string;
}

const GUTTER = 8;

/**
 * An info icon that opens a short explanation in a small panel next to it. It works without a mouse: click, Enter, Space or
 * keyboard focus opens it, Escape closes it and returns to the button, and so do a click outside or leaving it with Tab.
 * The panel is not hover-only, stays open while read (links and live status inside it are reachable), and never leaves the screen.
 */
export function InfoTip({ label, children, className }: InfoTipProps) {
  const id = useId().replace(/:/g, '');
  const [open, setOpen] = useState(false);
  const [pos, setPos] = useState<CSSProperties>({});
  const wrap = useRef<HTMLSpanElement>(null);
  const button = useRef<HTMLButtonElement>(null);
  /** Set while Escape hands focus back to the button, so that focus does not open the panel again. */
  const returning = useRef(false);

  useLayoutEffect(() => {
    if (!open) return;
    const place = () => {
      const r = button.current?.getBoundingClientRect();
      if (!r) return;
      const width = Math.min(352, window.innerWidth - 2 * GUTTER);
      const left = Math.max(GUTTER, Math.min(r.left, window.innerWidth - width - GUTTER));
      setPos({ top: Math.round(r.bottom + 6), left: Math.round(left), width });
    };
    place();
    window.addEventListener('resize', place);
    window.addEventListener('scroll', place, true);
    return () => {
      window.removeEventListener('resize', place);
      window.removeEventListener('scroll', place, true);
    };
  }, [open]);

  useEffect(() => {
    if (!open) return;
    const away = (e: Event) => {
      if (e.target instanceof Node && !wrap.current?.contains(e.target)) setOpen(false);
    };
    document.addEventListener('pointerdown', away);
    return () => document.removeEventListener('pointerdown', away);
  }, [open]);

  const onKeyDown = (e: ReactKeyboardEvent) => {
    if (e.key === 'Escape' && open) {
      e.preventDefault();
      e.stopPropagation();
      setOpen(false);
      returning.current = true;
      button.current?.focus();
      returning.current = false;
    }
  };
  const keyboardFocus = (el: HTMLElement) => {
    try {
      return el.matches(':focus-visible');
    } catch {
      return false;
    }
  };

  return (
    <span ref={wrap} className={className ? `${styles.wrap} ${className}` : styles.wrap}>
      <button ref={button} type="button" className={styles.button} aria-label={label} aria-expanded={open} aria-controls={id} aria-describedby={open ? id : undefined}
        onClick={() => setOpen((o) => !o)} onKeyDown={onKeyDown} onBlur={() => setOpen(false)} onFocus={(e) => { if (!returning.current && keyboardFocus(e.currentTarget)) setOpen(true); }}>
        <Info size={16} weight="fill" aria-hidden />
      </button>
      {open ? (
        // Pressing inside the panel keeps focus on the button, so reading or selecting its text does not close it.
        // eslint-disable-next-line jsx-a11y/no-noninteractive-element-interactions
        <div id={id} role="note" className={styles.panel} style={pos} onMouseDown={(e) => e.preventDefault()}>{children}</div>
      ) : null}
    </span>
  );
}
