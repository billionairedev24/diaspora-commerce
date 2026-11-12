'use client';

import { siteQueries } from '@afrotransact/api-client';
import { useQuery } from '@tanstack/react-query';
import { useEffect, useState } from 'react';

import { CartAwareProductCard } from '@/components/common/CartAwareProductCard';
import { useT } from '@/i18n/t';
import { browserApi } from '@/lib/api/browser';
import { railItems, readViewed } from '@/lib/recent/recentlyViewed';

import styles from './Home.module.css';
import { RailCarousel } from './RailCarousel';

/** "Recently viewed": the products this browser opened last, each once, name + price + rating only (no delivery date,
    no "Fulfilled by"). The gateway keeps only products that are live, in stock, photographed and tax-resolved. Nothing
    renders (no heading, no empty state) below the minimum. */
export function RecentlyViewed() {
  const t = useT();
  const [atins, setAtins] = useState<string[]>([]);
  useEffect(() => setAtins(readViewed()), []);
  const { data } = useQuery(siteQueries.recentlyViewed(browserApi(), atins));
  const items = railItems(data ?? []);
  if (!items.length) return null;
  return (
    <section className={styles.rail} aria-labelledby="rail-recently-viewed">
      <div className={styles.railHead}>
        <h2 id="rail-recently-viewed" className={styles.railTitle}>
          {t('home.recentlyViewed')}
        </h2>
      </div>
      <RailCarousel label={t('home.recentlyViewed')}>
        {items.map((p) => (
          <li key={p.atin}>
            <CartAwareProductCard product={p} variant="rail" showDelivery={false} />
          </li>
        ))}
      </RailCarousel>
    </section>
  );
}
