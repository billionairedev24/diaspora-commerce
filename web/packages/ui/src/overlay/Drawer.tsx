'use client';

import { Close, Content, Description, Overlay, Portal, Root, Title } from '@radix-ui/react-dialog';
import { type ReactNode, type RefObject } from 'react';

import { cx } from '../cx';
import { X } from '../icons';
import styles from './Drawer.module.css';
import { useReturnFocus } from './useReturnFocus';

export interface DrawerProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  /** The drawer's accessible name (not shown: the `header` is). */
  label: string;
  /** Shown in the dark bar at the top, next to the close button. */
  header?: ReactNode;
  closeLabel?: string;
  /** Which edge it slides in from: `start` is the left in left-to-right languages and the right in right-to-left ones. */
  side?: 'start' | 'end';
  /** The control that opens the drawer: focus returns to it on close even where a click does not focus buttons (Safari). Default: whatever had focus. */
  returnFocusTo?: RefObject<HTMLElement | null>;
  /** Where focus goes when it opens (default: the first focusable element, the close button). Call `preventDefault()` to take over. */
  onOpenAutoFocus?: (e: Event) => void;
  className?: string;
  children?: ReactNode;
}

/**
 * Navigation drawer (the storefront "All" menu): a full-height modal panel that slides in from the inline-start edge with a dim backdrop,
 * a dark header bar and a close button. Modal: focus is trapped, Escape and a click on the backdrop close it, the page behind cannot scroll,
 * and focus returns to the control that opened it. 85% of the width on phones. Mirrors itself in right-to-left languages.
 */
export function Drawer({ open, onOpenChange, label, header, closeLabel = 'Close', side = 'start', returnFocusTo, onOpenAutoFocus, className, children }: DrawerProps) {
  const returnFocus = useReturnFocus(open);
  return (
    <Root open={open} onOpenChange={onOpenChange}>
      <Portal>
        <Overlay className={styles.overlay} />
        <Content
          className={cx(styles.drawer, side === 'end' && styles.end, className)}
          onCloseAutoFocus={(e) => {
            const el = returnFocusTo?.current;
            if (el?.isConnected) {
              e.preventDefault();
              el.focus({ preventScroll: true });
            } else returnFocus(e);
          }}
          onOpenAutoFocus={onOpenAutoFocus}
        >
          <Title className="at-visually-hidden">{label}</Title>
          <Description className="at-visually-hidden">{label}</Description>
          <div className={styles.head}>
            <div className={styles.headMain}>{header}</div>
            <Close className={styles.close} aria-label={closeLabel}>
              <X size={22} aria-hidden />
            </Close>
          </div>
          <div className={styles.body}>{children}</div>
        </Content>
      </Portal>
    </Root>
  );
}
