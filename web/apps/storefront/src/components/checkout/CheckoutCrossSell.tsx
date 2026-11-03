'use client';

import { type Rail } from '@afrotransact/api-client';
import Link from 'next/link';

import { CartAwareProductCard } from '@/components/common/CartAwareProductCard';
import { useT } from '@/i18n/t';

import styles from './Checkout.module.css';

/** checkout_cross_sell placement from content; omitted when the slot is empty. */
export function CheckoutCrossSell({ rail }: { rail: Rail }) {
  const t = useT();
  if (!rail.items.length) return null;
  return (
    <section className={styles.promo} aria-labelledby={`checkout-promo-${rail.id}`}>
      <div className={styles.promoHead}>
        <h3 id={`checkout-promo-${rail.id}`} className={styles.promoTitle}>
          {rail.title}
        </h3>
        <Link href={rail.href} className={styles.promoMore}>
          {t('home.seeMore')}
        </Link>
      </div>
      <ul className={styles.promoGrid}>
        {rail.items.slice(0, 6).map((p) => (
          <li key={p.atin}>
            <CartAwareProductCard product={p} variant="rail" />
          </li>
        ))}
      </ul>
    </section>
  );
}
