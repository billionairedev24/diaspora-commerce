'use client';

import { Content, Group, Item, Portal, Root, Separator, Trigger } from '@radix-ui/react-dropdown-menu';
import { forwardRef, type ReactElement, type ReactNode } from 'react';

import { cx } from '../cx';
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
  label?: string;
  /** Render inline instead of in a portal (tests, stories). */
  inline?: boolean;
}

/** Dropdown menu (roving focus, arrow keys, Esc, type-ahead) built on Radix. */
export function Menu({ trigger, children, open, defaultOpen, onOpenChange, align = 'end', width = 290, label, inline }: MenuProps) {
  const content = (
    <Content className={styles.menu} align={align} sideOffset={8} style={{ width }} aria-label={label} loop>
      {children}
    </Content>
  );
  return (
    <Root open={open} defaultOpen={defaultOpen} onOpenChange={onOpenChange} modal={false}>
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
  className?: string;
}

export const MenuItem = forwardRef<HTMLDivElement, MenuItemProps>(function MenuItem({ icon, count, children, onSelect, href, className }, ref) {
  const inner = (
    <>
      {icon && <span className={styles.menuIcon}>{icon}</span>}
      <span className={styles.menuText}>{children}</span>
      {count !== undefined && count !== null && count !== 0 && <span className={styles.menuCount}>{count}</span>}
    </>
  );
  if (href) {
    return (
      <Item asChild onSelect={onSelect} ref={ref}>
        <a href={href} className={cx(styles.menuItem, className)}>
          {inner}
        </a>
      </Item>
    );
  }
  return (
    <Item ref={ref} onSelect={onSelect} className={cx(styles.menuItem, className)}>
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
