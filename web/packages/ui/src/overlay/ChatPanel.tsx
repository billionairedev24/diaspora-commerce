'use client';

import { Close, Content, Description, Overlay, Portal, Root, Title } from '@radix-ui/react-dialog';
import { type ReactNode } from 'react';

import { cx } from '../cx';
import { X } from '../icons';
import styles from './ChatPanel.module.css';
import { useReturnFocus } from './useReturnFocus';

export interface ChatPanelProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  /** Accessible name of the panel (also the heading when no `header` is given). */
  title: ReactNode;
  description?: ReactNode;
  /** Replaces the plain title row (avatar and name). The title stays available to screen readers. */
  header?: ReactNode;
  closeLabel?: string;
  className?: string;
  children?: ReactNode;
}

/**
 * Chat window anchored to the bottom end corner above a floating launcher (the WhatsApp pattern): a dialog with a focus trap,
 * Esc to close and focus returned to the launcher. From 641px it is a card (the page behind stays undimmed); on phones it is
 * a full-height sheet, safe-area aware. Placement uses logical properties, so it sits bottom-left in right-to-left languages.
 */
export function ChatPanel({ open, onOpenChange, title, description, header, closeLabel = 'Close', className, children }: ChatPanelProps) {
  const returnFocus = useReturnFocus(open);
  return (
    <Root open={open} onOpenChange={onOpenChange} modal>
      <Portal>
        <Overlay className={styles.overlay} />
        <Content className={cx(styles.panel, className)} onCloseAutoFocus={returnFocus} data-testid="chat-panel">
          <div className={styles.head}>
            {header}
            <Title className={header ? 'at-visually-hidden' : styles.title}>{title}</Title>
            <Close className={styles.close} aria-label={closeLabel}>
              <X size={20} aria-hidden />
            </Close>
          </div>
          <Description className="at-visually-hidden">{description ?? title}</Description>
          <div className={styles.body}>{children}</div>
        </Content>
      </Portal>
    </Root>
  );
}
