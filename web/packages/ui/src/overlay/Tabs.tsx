'use client';

import { Content, List, Root, Trigger } from '@radix-ui/react-tabs';
import { type ReactNode } from 'react';

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

/** Accessible tabs (arrow keys move between tabs, Home/End jump). */
export function Tabs({ items, value, defaultValue, onValueChange, label }: TabsProps) {
  return (
    <Root value={value} defaultValue={defaultValue ?? items[0]?.value} onValueChange={onValueChange}>
      <List className={styles.tabList} aria-label={label}>
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
