'use client';

import { CaretLeft, CaretRight } from '@afrotransact/ui';
import { type KeyboardEvent, type ReactNode, useCallback, useEffect, useRef, useState } from 'react';

import { useT } from '@/i18n/t';

import styles from './Rails.module.css';

/**
 * A horizontally scrolling product row: as many cards as fit, scroll-snap for touch and trackpads, arrow buttons for
 * mouse users (shown only while there is more to see) and Left/Right keys when the row itself has focus. A row that
 * fits on screen shows no arrows and does not scroll. Respects prefers-reduced-motion and right-to-left.
 */
export function RailCarousel({ label, children, className }: { label: string; children: ReactNode; className?: string }) {
  const t = useT();
  const list = useRef<HTMLUListElement>(null);
  const [edges, setEdges] = useState({ overflow: false, atStart: true, atEnd: true });

  const measure = useCallback(() => {
    const el = list.current;
    if (!el) return;
    const max = el.scrollWidth - el.clientWidth;
    const x = Math.abs(el.scrollLeft);
    setEdges({ overflow: max > 2, atStart: x <= 1, atEnd: x >= max - 1 });
  }, []);

  useEffect(() => {
    const el = list.current;
    if (!el) return;
    measure();
    el.addEventListener('scroll', measure, { passive: true });
    const ro = typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(measure);
    ro?.observe(el);
    return () => {
      el.removeEventListener('scroll', measure);
      ro?.disconnect();
    };
  }, [measure]);

  const scroll = (dir: 1 | -1) => {
    const el = list.current;
    if (!el) return;
    const rtl = getComputedStyle(el).direction === 'rtl';
    const reduce = window.matchMedia?.('(prefers-reduced-motion: reduce)').matches;
    el.scrollBy({ left: dir * (rtl ? -1 : 1) * el.clientWidth * 0.85, behavior: reduce ? 'auto' : 'smooth' });
  };
  const onKeyDown = (e: KeyboardEvent<HTMLUListElement>) => {
    if (e.target !== e.currentTarget) return; // arrow keys inside a card keep their own meaning
    if (e.key === 'ArrowRight') scroll(1);
    else if (e.key === 'ArrowLeft') scroll(-1);
    else return;
    e.preventDefault();
  };

  return (
    <div className={`${styles.carousel} ${className ?? ''}`} data-overflow={edges.overflow ? 'true' : 'false'} role="group" aria-roledescription="carousel" aria-label={label}>
      <button type="button" className={`${styles.arrow} ${styles.arrowPrev}`} aria-label={t('home.railPrev', { title: label })} disabled={edges.atStart} onClick={() => scroll(-1)} hidden={!edges.overflow}>
        <CaretLeft aria-hidden />
      </button>
      {/* A scrollable region must be keyboard reachable (WCAG 2.1.1); Left/Right scroll it while it has focus. */}
      {/* eslint-disable-next-line jsx-a11y/no-noninteractive-tabindex, jsx-a11y/no-noninteractive-element-interactions */}
      <ul ref={list} className={styles.track} aria-label={label} tabIndex={0} onKeyDown={onKeyDown}>
        {children}
      </ul>
      <button type="button" className={`${styles.arrow} ${styles.arrowNext}`} aria-label={t('home.railNext', { title: label })} disabled={edges.atEnd} onClick={() => scroll(1)} hidden={!edges.overflow}>
        <CaretRight aria-hidden />
      </button>
    </div>
  );
}
