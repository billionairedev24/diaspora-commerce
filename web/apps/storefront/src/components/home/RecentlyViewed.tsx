'use client';

import { siteQueries } from '@afrotransact/api-client';
import { useQuery } from '@tanstack/react-query';
import { useEffect, useState } from 'react';

import { CartAwareProductCard } from '@/components/common/CartAwareProductCard';
import { useT } from '@/i18n/t';
import { browserApi } from '@/lib/api/browser';
import { readViewed } from '@/lib/recent/recentlyViewed';

import styles from './Home.module.css';
import { RailCarousel } from './RailCarousel';

/** "Recently viewed": the products this browser opened last. Nothing renders (no heading, no empty state) until at
    least one remembered product is in stock with a real photo. */
export function RecentlyViewed() {
  const t = useT();
  const [atins, setAtins] = useState<string[]>([]);
  useEffect(() => setAtins(readViewed()), []);
  const { data } = useQuery(siteQueries.recentlyViewed(browserApi(), atins));
  if (!data?.length) return null;
  return (
    <section className={styles.rail} aria-labelledby="rail-recently-viewed">
      <div className={styles.railHead}>
        <h2 id="rail-recently-viewed" className={styles.railTitle}>
          {t('home.recentlyViewed')}
        </h2>
      </div>
      <RailCarousel label={t('home.recentlyViewed')}>
        {data.map((p) => (
          <li key={p.atin}>
            <CartAwareProductCard product={p} variant="rail" />
          </li>
        ))}
      </RailCarousel>
    </section>
  );
}
