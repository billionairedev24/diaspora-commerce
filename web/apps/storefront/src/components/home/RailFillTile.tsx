'use client';

import { formatMoney, type ProductSummary, type RailFill } from '@afrotransact/api-client';
import { type MouseEvent } from 'react';

// Content-driven addresses may be /sell or /provide (a cross-origin redirect): HandoffLink renders those as plain links.
import { HandoffLink as Link } from '@/components/shell/HandoffLink';
import { useT } from '@/i18n/t';
import { browserApi } from '@/lib/api/browser';
import { useAdTracking } from '@/lib/home/adTracking';

import { HomePhoto } from './modules/HomePhoto';
import styles from './RailFill.module.css';

/**
 * The tile that takes the empty columns of a short rail (resolved by the gateway with the rail: one request, in the first
 * HTML, so nothing shifts after load). A paid tile always shows the "Sponsored" tag and the advertiser, visibly and in its
 * accessible name; a staff editorial tile carries no ad label. Impressions and clicks use the existing mechanism: the
 * placement counters (`useAdTracking`) and, for sponsored products, the search click with the card's ad token. Flat: no card,
 * no shadow, tokens only. Without a real photo the tile is text only.
 */
export function RailFillTile({ fill }: { fill: RailFill }) {
  const t = useT();
  const { ref, hidden, click } = useAdTracking(fill.id ?? `fill-${fill.searchId ?? fill.title}`, fill.maxPerSession);
  if (hidden) return null;
  const advertiser = fill.advertiser ?? '';
  const label = fill.sponsored ? t('home.sponsored', { advertiser }) : undefined;
  const tag = fill.sponsored ? (
    <span className={styles.label}>
      <span className={styles.tag}>{t('home.sponsoredTag')}</span>
      {advertiser ? <strong>{advertiser}</strong> : null}
    </span>
  ) : null;

  if (fill.kind === 'sponsored-products') {
    const products = fill.products ?? [];
    if (!products.length) return null;
    const report = (p: ProductSummary, position: number) => (e: MouseEvent) => {
      const searchId = p.sponsor?.searchId ?? fill.searchId;
      if (!searchId || !(e.target instanceof Element) || !e.target.closest('a')) return;
      browserApi()
        .recordSearchClick({ searchId, atin: p.atin, position, adToken: p.sponsor?.adToken })
        .catch(() => undefined);
    };
    return (
      <li className={styles.cell} data-testid="rail-fill" data-kind="sponsored-products">
        <article ref={ref} aria-label={label} className={styles.tile}>
          {tag}
          <ul className={styles.products}>
            {products.map((p, i) => (
              <li key={p.atin} onClickCapture={report(p, i + 1)}>
                <Link href={`/p/${p.atin}`} className={styles.product}>
                  <HomePhoto src={p.imageUrl} ratio="1 / 1" className={styles.productPhoto} />
                  <span className={styles.productTitle}>{p.title}</span>
                  <span className={styles.productPrice}>{formatMoney(p.priceMinor, p.currency)}</span>
                </Link>
              </li>
            ))}
          </ul>
          <Link href={fill.href} className={styles.cta}>
            {t('home.seeAll')}
          </Link>
        </article>
      </li>
    );
  }

  return (
    <li className={styles.cell} data-testid="rail-fill" data-kind="placement" data-sponsored={fill.sponsored ? 'true' : 'false'}>
      <article ref={ref} aria-label={label} className={`${styles.tile} ${fill.imageUrl ? styles.withPhoto : ''}`}>
        <HomePhoto src={fill.imageUrl} ratio="1 / 1" className={styles.photo} />
        <div className={styles.text}>
          {tag}
          <h3 className={styles.title}>{fill.title}</h3>
          {fill.body ? <p className={styles.body}>{fill.body}</p> : null}
          <Link href={fill.href} className={styles.cta} onClick={click} rel={fill.sponsored ? 'sponsored' : undefined}>
            {fill.cta || t('home.seeAll')}
          </Link>
          {fill.sponsored ? <span className={styles.note}>{t('home.adNote')}</span> : null}
        </div>
      </article>
    </li>
  );
}
