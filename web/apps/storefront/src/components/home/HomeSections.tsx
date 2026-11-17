import { type Rail, RAIL_FILL_TARGET, type Tile } from '@afrotransact/api-client';
import { type ReactNode } from 'react';

import { CartAwareProductCard } from '@/components/common/CartAwareProductCard';
// Content-driven addresses may be /sell or /provide (a cross-origin redirect): HandoffLink renders those as plain links.
import { HandoffLink as Link } from '@/components/shell/HandoffLink';
import { getT } from '@/i18n/server';

import styles from './Home.module.css';
import { HomePhoto } from './modules/HomePhoto';
import { RailCarousel } from './RailCarousel';
import { RailFillTile } from './RailFillTile';

/** Server-rendered tiles under the hero. The columns follow the tile count (see Home.module.css): no empty tracks. */
export async function Tiles({ tiles }: { tiles: Tile[] }) {
  const t = await getT();
  if (tiles.length === 0) return null;
  return (
    <section className={styles.tiles} data-count={tiles.length} aria-label={t('home.featured')}>
      {tiles.map((tile) => (
        <div key={tile.id} className={styles.tile}>
          <h2 className={styles.tileTitle}>{tile.title}</h2>
          <ul className={styles.cells}>
            {tile.cells.map((c) => (
              <li key={c.atin ?? c.label}>
                <Link href={c.href} className={styles.cell}>
                  <HomePhoto src={c.imageUrl} ratio="1 / 1" />
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

/** A rail with fewer cards than fit the row and no fill tile narrows to its own width (left-aligned, no hollow area). */
export function shortRail(rail: Rail): 'true' | 'false' {
  return !rail.fill && rail.items.length < RAIL_FILL_TARGET ? 'true' : 'false';
}

/** A product rail: heading, "See all" and a scrolling row of cards. `extra` is rendered in the header (countdown, label). */
export async function RailSection({ rail, extra, wrap }: { rail: Rail; extra?: ReactNode; wrap?: (card: ReactNode, index: number) => ReactNode }) {
  const t = await getT();
  return (
    <section className={styles.rail} aria-labelledby={`rail-${rail.id}`} data-short={shortRail(rail)}>
      <div className={styles.railHead}>
        <h2 id={`rail-${rail.id}`} className={styles.railTitle}>
          {rail.title}
        </h2>
        {extra}
        <Link href={rail.href} className={styles.railMore}>
          {t('home.seeAll')}
        </Link>
      </div>
      <RailCarousel label={rail.title} fill={rail.fill ? <RailFillTile fill={rail.fill} /> : undefined}>
        {rail.items.map((p, i) => {
          const card = <CartAwareProductCard product={p} variant="rail" />;
          return <li key={p.atin}>{wrap ? wrap(card, i) : card}</li>;
        })}
      </RailCarousel>
    </section>
  );
}
