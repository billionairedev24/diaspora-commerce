'use client';

import { Content, Group, Item, Portal, Root, Separator, Trigger } from '@radix-ui/react-dropdown-menu';
import { forwardRef, type ReactElement, type ReactNode } from 'react';

import { cx } from '../cx';
import { ArrowSquareOut } from '../icons';
import styles from './overlay.module.css';

export interface MenuProps {
  /** The trigger element (rendered as-is; must accept a ref, e.g. a <button>). */
  trigger: ReactElement;
  children: ReactNode;
  open?: boolean;
  defaultOpen?: boolean;
  onOpenChange?: (open: boolean) => void;
  align?: 'start' | 'end';
  width?: number;
  /** Accessible name of the menu. Radix names the menu after its trigger (aria-labelledby); a label replaces that. */
  label?: string;
  /** Render inline instead of in a portal (tests, stories). */
  inline?: boolean;
  /** Reading direction of the menu (defaults to the page's). Radix flips start/end and the arrow keys with it. */
  dir?: 'ltr' | 'rtl';
  /** Fires when the menu closes by Esc or an outside click, before focus returns to the trigger. */
  onEscapeKeyDown?: (e: KeyboardEvent) => void;
}

/** Dropdown menu (roving focus, arrow keys, Esc, type-ahead) built on Radix. */
export function Menu({ trigger, children, open, defaultOpen, onOpenChange, align = 'end', width = 290, label, inline, dir, onEscapeKeyDown }: MenuProps) {
  const content = (
    <Content className={styles.menu} align={align} sideOffset={8} collisionPadding={8} style={{ width }} onEscapeKeyDown={onEscapeKeyDown} loop {...(label ? { 'aria-label': label, 'aria-labelledby': undefined } : {})}>
      {children}
    </Content>
  );
  return (
    <Root open={open} defaultOpen={defaultOpen} onOpenChange={onOpenChange} modal={false} dir={dir}>
      <Trigger asChild>{trigger}</Trigger>
      {inline ? content : <Portal>{content}</Portal>}
    </Root>
  );
}

export interface MenuItemProps {
  icon?: ReactNode;
  count?: number | string | null;
  children: ReactNode;
  onSelect?: () => void;
  /** Render as a link. */
  href?: string;
  /** The link leaves this app (opens in a new tab). */
  external?: boolean;
  className?: string;
  /** A destructive choice (Delete, Discard, Withdraw): red text. */
  danger?: boolean;
  /** Not available right now. A string says why: it shows under the label and as a tooltip. */
  disabled?: boolean | string;
}

export const MenuItem = forwardRef<HTMLDivElement, MenuItemProps>(function MenuItem({ icon, count, children, onSelect, href, external, className, danger, disabled }, ref) {
  const reason = typeof disabled === 'string' ? disabled : undefined;
  const classes = cx(styles.menuItem, danger && styles.menuDanger, className);
  const inner = (
    <>
      {icon && <span className={styles.menuIcon}>{icon}</span>}
      <span className={styles.menuText}>
        {children}
        {reason ? <span className={styles.menuReason}>{reason}</span> : null}
      </span>
      {count !== undefined && count !== null && count !== 0 && <span className={styles.menuCount}>{count}</span>}
    </>
  );
  if (href && !disabled) {
    return (
      <Item asChild onSelect={onSelect} ref={ref}>
        <a href={href} className={classes} {...(external ? { target: '_blank', rel: 'noopener noreferrer' } : {})}>
          {inner}
          {external ? <ArrowSquareOut size={16} aria-hidden className={styles.menuIcon} /> : null}
        </a>
      </Item>
    );
  }
  return (
    <Item ref={ref} onSelect={onSelect} className={classes} disabled={Boolean(disabled)} title={reason}>
      {inner}
    </Item>
  );
});

export function MenuGroup({ children, label }: { children: ReactNode; label?: string }) {
  return (
    <Group className={styles.menuGroup} aria-label={label}>
      {children}
    </Group>
  );
}

export function MenuSeparator() {
  return <Separator className={styles.menuSeparator} />;
}
