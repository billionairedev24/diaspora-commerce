import { type Banner, type Rail, type Tile } from '@afrotransact/api-client';
import { buttonClassName, ImagePlaceholder } from '@afrotransact/ui';
import Link from 'next/link';

import { CartAwareProductCard } from '@/components/common/CartAwareProductCard';
import { getT } from '@/i18n/server';

import styles from './Home.module.css';

/** Server-rendered placement sections (tiles, sponsored banners, product rails) — indexable HTML. */
export async function Tiles({ tiles }: { tiles: Tile[] }) {
  const t = await getT();
  return (
    <section className={styles.tiles} aria-label={t('home.featured')}>
      {tiles.map((tile) => (
        <div key={tile.id} className={styles.tile}>
          <h2 className={styles.tileTitle}>{tile.title}</h2>
          <ul className={styles.cells}>
            {tile.cells.map((c) => (
              <li key={c.label}>
                <Link href={c.href} className={styles.cell}>
                  <ImagePlaceholder src={c.imageUrl} alt="" />
                  <span className={styles.cellLabel}>{c.label}</span>
                </Link>
              </li>
            ))}
          </ul>
          <Link href={tile.href} className={styles.tileCta}>
            {tile.cta}
          </Link>
        </div>
      ))}
    </section>
  );
}

export async function Banners({ banners }: { banners: Banner[] }) {
  const t = await getT();
  return (
    <>
      {banners.map((b) => (
        <section key={b.id} className={styles.banner} aria-label={t('home.sponsored', { advertiser: b.advertiser })}>
          <ImagePlaceholder src={b.imageUrl} alt={b.title} ratio="970 / 250" caption={t('home.advertiserCreative')} style={{ minHeight: 200, height: '100%' }} />
          <div className={styles.bannerBody}>
            <span className={styles.muted}>{t('home.sponsored', { advertiser: b.advertiser })}</span>
            <h2 className={styles.bannerTitle}>{b.title}</h2>
            <p className={styles.bannerSub}>{b.sub}</p>
            <Link href={b.href} className={buttonClassName({ size: 'sm' })} style={{ marginTop: 8 }}>
              {b.cta}
            </Link>
            <span className={styles.adNote}>{t('home.adNote')}</span>
          </div>
        </section>
      ))}
    </>
  );
}

export async function Rails({ rails }: { rails: Rail[] }) {
  const t = await getT();
  return (
    <>
      {rails.map((r) => (
        <section key={r.id} className={styles.rail} aria-labelledby={`rail-${r.id}`}>
          <div className={styles.railHead}>
            <h2 id={`rail-${r.id}`} className={styles.railTitle}>
              {r.title}
            </h2>
            <Link href={r.href} className={styles.railMore}>
              {t('home.seeMore')}
            </Link>
          </div>
          <ul className={styles.railGrid}>
            {r.items.map((p) => (
              <li key={p.atin}>
                <CartAwareProductCard product={p} variant="rail" />
              </li>
            ))}
          </ul>
        </section>
      ))}
    </>
  );
}
