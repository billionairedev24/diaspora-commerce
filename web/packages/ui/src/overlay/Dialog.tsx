'use client';

import { Close, Content, Description, Overlay, Portal, Root, Title } from '@radix-ui/react-dialog';
import { type CSSProperties, type ReactNode } from 'react';

import { cx } from '../cx';
import { X } from '../icons';
import styles from './overlay.module.css';

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
  return (
    <Root open={open} onOpenChange={onOpenChange}>
      <Portal>
        <Overlay className={styles.overlay} />
        <Content className={cx(styles.dialog, className)} style={{ '--dialog-width': `${width}px` } as CSSProperties}>
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
}

/** Side sheet / drawer (used for Lola and mobile navigation). */
export function Sheet({ open, onOpenChange, title, description, header, children, side = 'right', closeLabel = 'Close', className }: SheetProps) {
  return (
    <Root open={open} onOpenChange={onOpenChange}>
      <Portal>
        <Overlay className={styles.overlay} />
        <Content className={cx(styles.sheet, styles[side], className)}>
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
