'use client';

import { Close, Content, Description, Overlay, Portal, Root, Title } from '@radix-ui/react-dialog';
import { type CSSProperties, type ReactNode, type RefObject } from 'react';

import { cx } from '../cx';
import { X } from '../icons';
import styles from './overlay.module.css';
import { useReturnFocus } from './useReturnFocus';

export interface DialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  title: ReactNode;
  description?: ReactNode;
  children?: ReactNode;
  width?: number;
  closeLabel?: string;
  className?: string;
  /** Element to focus when the dialog opens (default: the first focusable element, the close button). */
  initialFocus?: RefObject<HTMLElement | null>;
}

/** Modal dialog (focus trap, Esc to close, focus returns to the trigger). */
export function Dialog({ open, onOpenChange, title, description, children, width = 400, closeLabel = 'Close', className, initialFocus }: DialogProps) {
  const returnFocus = useReturnFocus(open);
  return (
    <Root open={open} onOpenChange={onOpenChange}>
      <Portal>
        <Overlay className={styles.overlay} />
        <Content
          className={cx(styles.dialog, className)}
          style={{ '--dialog-width': `${width}px` } as CSSProperties}
          onCloseAutoFocus={returnFocus}
          onOpenAutoFocus={(e) => {
            if (!initialFocus?.current) return;
            e.preventDefault();
            initialFocus.current.focus();
          }}
        >
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

/** The footer of a dialog (Cancel, then the primary button): wraps instead of clipping when a label is long. */
export function DialogActions({ children, className }: { children: ReactNode; className?: string }) {
  return <div className={cx(styles.actions, className)}>{children}</div>;
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
        {modal ? <Overlay className={cx(styles.overlay, styles.sheetOverlay)} /> : null}
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
