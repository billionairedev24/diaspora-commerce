'use client';

import { CaretDoubleLeft, CaretDoubleRight } from '@phosphor-icons/react/ssr';
import { type MouseEvent, type ReactNode, useEffect, useId, useState } from 'react';

import { List, X } from '../icons';
import styles from './BackofficeFrame.module.css';

export interface BackofficeFrameProps {
  /** The sticky header; receives the phone "Menu" button to place in it (hidden from 768px). */
  header: (menuButton: ReactNode) => ReactNode;
  /** The full navigation (phones when the menu is open, tablets when the rail is expanded, desktops). */
  nav: ReactNode;
  /** The icon rail (tablets, 768–1023px, while collapsed). */
  rail: ReactNode;
  /** The page (usually <main>). */
  children: ReactNode;
  /** Closes the phone menu when it changes (the pathname). */
  routeKey?: string;
  menuLabel?: string;
  expandLabel?: string;
  collapseLabel?: string;
}

/**
 * Back-office layout (Seller Center, staff console). Phones get a "Menu" disclosure in the header that expands the
 * navigation inline under it — no overlay, the page stays where it is — and closes again on navigation. Tablets get a
 * collapsible icon rail; desktops the full sidebar, fixed while the page scrolls.
 */
export function BackofficeFrame({
  header,
  nav,
  rail,
  children,
  routeKey,
  menuLabel = 'Menu',
  expandLabel = 'Expand menu',
  collapseLabel = 'Collapse menu'
}: BackofficeFrameProps) {
  const navId = useId();
  // Open for one route: navigating closes the phone menu and folds an expanded rail back to icons.
  const [openOn, setOpenOn] = useState<string | null>(null);
  const [expandedOn, setExpandedOn] = useState<string | null>(null);
  const here = routeKey ?? '';
  const open = openOn === here;
  const expanded = expandedOn === here;
  const setOpen = (v: boolean) => setOpenOn(v ? here : null);
  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setOpenOn(null);
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [open]);
  // A link chosen in the menu closes it even when the route doesn't change (the current page).
  const onNavClick = (e: MouseEvent<HTMLElement>) => {
    if ((e.target as HTMLElement).closest('a[href]')) setOpen(false);
  };

  const menuButton = (
    <button type="button" className={styles.menuButton} aria-expanded={open} aria-controls={navId} onClick={() => setOpen(!open)}>
      {open ? <X size={22} aria-hidden /> : <List size={22} aria-hidden />}
      <span>{menuLabel}</span>
    </button>
  );

  return (
    <>
      {header(menuButton)}
      <div className={styles.frame} data-rail={expanded ? 'expanded' : 'collapsed'}>
        {/* Clicks inside only close the phone menu after a link was chosen; links keep their own keyboard handling. */}
        {/* eslint-disable-next-line jsx-a11y/click-events-have-key-events, jsx-a11y/no-noninteractive-element-interactions */}
        <aside id={navId} className={styles.aside} data-open={open ? 'true' : 'false'} onClick={onNavClick}>
          <button type="button" className={styles.railToggle} aria-expanded={expanded} onClick={() => setExpandedOn(expanded ? null : here)} aria-label={expanded ? collapseLabel : expandLabel}>
            {expanded ? <CaretDoubleLeft size={18} aria-hidden /> : <CaretDoubleRight size={18} aria-hidden />}
            {expanded ? <span>{collapseLabel}</span> : null}
          </button>
          <div className={styles.full}>{nav}</div>
          <div className={styles.rail}>{rail}</div>
        </aside>
        {children}
      </div>
    </>
  );
}
