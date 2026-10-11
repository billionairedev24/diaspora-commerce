'use client';

import { Content, List, Root, Trigger } from '@radix-ui/react-tabs';
import { type ReactNode, useEffect, useRef } from 'react';

import styles from './overlay.module.css';

export interface TabItem {
  value: string;
  label: ReactNode;
  content: ReactNode;
}

export interface TabsProps {
  items: TabItem[];
  value?: string;
  defaultValue?: string;
  onValueChange?: (value: string) => void;
  label: string;
}

/** Accessible tabs (arrow keys move between tabs, Home/End jump). The tab row scrolls sideways on narrow screens and
    keeps the selected tab in view. */
export function Tabs({ items, value, defaultValue, onValueChange, label }: TabsProps) {
  const list = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const el = list.current;
    const active = el?.querySelector<HTMLElement>('[data-state="active"]');
    if (!el || !active || el.scrollWidth <= el.clientWidth) return;
    const left = active.offsetLeft - el.offsetLeft;
    if (left < el.scrollLeft || left + active.offsetWidth > el.scrollLeft + el.clientWidth) el.scrollLeft = left - 16;
  }, [value]);
  return (
    <Root value={value} defaultValue={defaultValue ?? items[0]?.value} onValueChange={onValueChange}>
      <List ref={list} className={styles.tabList} aria-label={label}>
        {items.map((t) => (
          <Trigger key={t.value} value={t.value} className={styles.tab}>
            {t.label}
          </Trigger>
        ))}
      </List>
      {items.map((t) => (
        <Content key={t.value} value={t.value} className={styles.tabPanel}>
          {t.content}
        </Content>
      ))}
    </Root>
  );
}
