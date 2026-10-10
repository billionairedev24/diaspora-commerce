'use client';

import { CalendarStar, Storefront, Truck, X } from '@afrotransact/ui';
import { useQuery } from '@tanstack/react-query';
import Link from 'next/link';
import { useEffect, useState } from 'react';

import { useT } from '@/i18n/t';
import { browserApi } from '@/lib/api/browser';
import { siteQueries } from '@/lib/api/queries';

import styles from './AnnouncementBar.module.css';

const ICONS = { truck: Truck, 'calendar-star': CalendarStar, storefront: Storefront } as const;

/** Announcement bar driven by content placements; rotates every 6 s (paused on hover/focus and for reduced motion). */
export function AnnouncementBar() {
  const t = useT();
  const { data } = useQuery(siteQueries.home(browserApi()));
  const items = data?.announcements ?? [];
  const [idx, setIdx] = useState(0);
  const [hidden, setHidden] = useState(false);
  const [paused, setPaused] = useState(false);

  useEffect(() => {
    if (items.length < 2 || paused) return;
    if (typeof window !== 'undefined' && window.matchMedia?.('(prefers-reduced-motion: reduce)').matches) return;
    const id = setInterval(() => setIdx((i) => i + 1), 6000);
    return () => clearInterval(id);
  }, [items.length, paused]);

  if (hidden || items.length === 0) return null;
  const a = items[idx % items.length];
  if (!a) return null;
  const Icon = ICONS[a.icon as keyof typeof ICONS] ?? Truck;
  return (
    <section
      className={`${styles.bar} ${styles[a.tone]}`}
      aria-label={t('announcement.label')}
      onMouseEnter={() => setPaused(true)}
      onMouseLeave={() => setPaused(false)}
      onFocus={() => setPaused(true)}
      onBlur={() => setPaused(false)}
    >
      <Icon className={styles.icon} aria-hidden />
      <span>{a.text}</span>
      {a.href.startsWith('/sell') ? (
        <a className={styles.cta} href={a.href}>
          {a.cta}
        </a>
      ) : (
        <Link className={styles.cta} href={a.href}>
          {a.cta}
        </Link>
      )}
      {items.length > 1 && <span className={styles.pos}>{t('announcement.position', { n: (idx % items.length) + 1, total: items.length })}</span>}
      <button type="button" className={styles.dismiss} onClick={() => setHidden(true)} aria-label={t('announcement.dismiss')}>
        <X aria-hidden />
      </button>
    </section>
  );
}
