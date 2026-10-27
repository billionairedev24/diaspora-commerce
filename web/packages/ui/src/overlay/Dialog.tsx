'use client';

import { Close, Content, Description, Overlay, Portal, Root, Title } from '@radix-ui/react-dialog';
import { type CSSProperties, type ReactNode, useLayoutEffect, useRef } from 'react';

import { cx } from '../cx';
import { X } from '../icons';
import styles from './overlay.module.css';

/**
 * Remembers the element that had focus when an overlay opened and returns focus to it on close (WCAG 2.4.3). Radix
 * only does this for its own <Dialog.Trigger>; our overlays are opened from app state, so we do it here.
 */
function useReturnFocus(open: boolean): (e: Event) => void {
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

export interface DialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  title: ReactNode;
  description?: ReactNode;
  children?: ReactNode;
  width?: number;
  closeLabel?: string;
  className?: string;
}

/** Modal dialog (focus trap, Esc to close, focus returns to the trigger). */
export function Dialog({ open, onOpenChange, title, description, children, width = 400, closeLabel = 'Close', className }: DialogProps) {
  const returnFocus = useReturnFocus(open);
  return (
    <Root open={open} onOpenChange={onOpenChange}>
      <Portal>
        <Overlay className={styles.overlay} />
        <Content className={cx(styles.dialog, className)} style={{ '--dialog-width': `${width}px` } as CSSProperties} onCloseAutoFocus={returnFocus}>
          <div className={styles.head}>
            <Title className={styles.title}>{title}</Title>
            <Close className={styles.close} aria-label={closeLabel}>
              <X size={20} aria-hidden />
            </Close>
          </div>
          <div className={styles.body}>
            {description ? <Description className={styles.desc}>{description}</Description> : <Description className="at-visually-hidden">{title}</Description>}
            {children}
          </div>
        </Content>
      </Portal>
    </Root>
  );
}

export interface SheetProps extends Omit<DialogProps, 'width'> {
  side?: 'right' | 'left';
  header?: ReactNode;
  /**
   * Modal sheets dim and block the page (focus trap, outside click closes). Default: only left sheets (the mobile
   * navigation menu) are modal. Right sheets (record details, forms) sit below the sticky header without an overlay and
   * leave the page usable, but a click anywhere outside them closes them, so a sheet is never left open behind
   * another action. Esc or the close button closes them too.
   */
  modal?: boolean;
  /** An assistant that stays beside the page while it is used (Lola): a click outside does not close it. */
  persistent?: boolean;
  /** Ignored for right sheets: every right sheet has the same width (820px, never wider than the viewport). */
  width?: number;
}

const keepOpen = (e: Event) => e.preventDefault();

/**
 * Whether an interaction happened inside another dialog or menu (a one-time secret, a confirmation, a menu) that sits on top
 * of the sheet: that is not "outside" for the sheet, so clicking in it must not close the sheet behind it.
 */
const inAnotherLayer = (e: Event) => e.target instanceof Element && e.target.closest('[role="dialog"], [role="alertdialog"], [role="menu"], [role="listbox"]') !== null;
const closeOnOutsideClick = (e: Event) => {
  if (inAnotherLayer(e)) e.preventDefault();
};

/** Side sheet / drawer (record details, Lola, mobile navigation). */
export function Sheet({ open, onOpenChange, title, description, header, children, side = 'right', modal = side === 'left', persistent = false, closeLabel = 'Close', className, width }: SheetProps) {
  const returnFocus = useReturnFocus(open);
  return (
    <Root open={open} onOpenChange={onOpenChange} modal={modal}>
      <Portal>
        {modal ? <Overlay className={styles.overlay} /> : null}
        <Content
          className={cx(styles.sheet, styles[side], !modal && styles.docked, className)}
          onCloseAutoFocus={returnFocus}
          onInteractOutside={modal ? undefined : persistent ? keepOpen : closeOnOutsideClick}
          // Only a click outside closes a docked sheet; focus moving elsewhere (a dialog closing, a menu) does not.
          onFocusOutside={modal ? undefined : keepOpen}
          style={side === 'left' && width ? ({ '--sheet-width': `${width}px` } as CSSProperties) : undefined}
        >
          <div className={styles.sheetHead}>
            {header}
            <Title className={header ? 'at-visually-hidden' : styles.title}>{title}</Title>
            <Close className={styles.close} aria-label={closeLabel}>
              <X size={20} aria-hidden />
            </Close>
          </div>
          <Description className="at-visually-hidden">{description ?? title}</Description>
          <div className={styles.sheetBody}>{children}</div>
        </Content>
      </Portal>
    </Root>
  );
}
