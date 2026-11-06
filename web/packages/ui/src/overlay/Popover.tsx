'use client';

import { Close, Content, Description, Overlay, Portal, Root, Title } from '@radix-ui/react-dialog';
import { type CSSProperties, type ReactNode, type RefObject, useLayoutEffect, useRef, useState } from 'react';

import { cx } from '../cx';
import { X } from '../icons';
import styles from './overlay.module.css';

export interface PopoverProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  /** The element the popover hangs under (a header link). */
  anchorRef: RefObject<HTMLElement | null>;
  title: ReactNode;
  children?: ReactNode;
  /** Desktop width in px (never wider than the viewport). */
  width?: number;
  closeLabel?: string;
  className?: string;
}

const GUTTER = 8;

/**
 * Small popover anchored under a trigger on desktop; a bottom sheet on phones (<= 640px). Built on the Radix dialog
 * (portal, so nothing inside is clipped; focus trap; Esc and an outside click close; focus returns to the trigger).
 * Content flows inline: a list inside it (autocomplete suggestions) grows the popover instead of being cut off.
 */
export function Popover({ open, onOpenChange, anchorRef, title, children, width = 380, closeLabel = 'Close', className }: PopoverProps) {
  const [pos, setPos] = useState({ top: 0, left: 0 });
  const opener = useRef<HTMLElement | null>(null);
  useLayoutEffect(() => {
    if (!open) return;
    if (document.activeElement instanceof HTMLElement && document.activeElement !== document.body) opener.current = document.activeElement;
    const place = () => {
      const r = anchorRef.current?.getBoundingClientRect();
      if (!r) return;
      const w = Math.min(width, window.innerWidth - 2 * GUTTER);
      setPos({ top: Math.round(r.bottom + 6), left: Math.round(Math.max(GUTTER, Math.min(r.left, window.innerWidth - w - GUTTER))) });
    };
    place();
    window.addEventListener('resize', place);
    return () => window.removeEventListener('resize', place);
  }, [open, anchorRef, width]);

  return (
    <Root open={open} onOpenChange={onOpenChange}>
      <Portal>
        <Overlay className={styles.popoverOverlay} />
        <Content
          className={cx(styles.popover, className)}
          style={{ '--pop-top': `${pos.top}px`, '--pop-left': `${pos.left}px`, '--pop-width': `${width}px` } as CSSProperties}
          onCloseAutoFocus={(e) => {
            const el = opener.current;
            opener.current = null;
            if (el?.isConnected) {
              e.preventDefault();
              el.focus({ preventScroll: true });
            }
          }}
        >
          <div className={styles.popoverHead}>
            <Title className={styles.popoverTitle}>{title}</Title>
            <Close className={styles.close} aria-label={closeLabel}>
              <X size={18} aria-hidden />
            </Close>
          </div>
          <Description className="at-visually-hidden">{title}</Description>
          <div className={styles.popoverBody}>{children}</div>
        </Content>
      </Portal>
    </Root>
  );
}
