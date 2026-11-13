import { type HomeBlock } from '@afrotransact/api-client';
import { createFormatters } from '@afrotransact/i18n';
import { buttonClassName } from '@afrotransact/ui';
import Link from 'next/link';

import { getLocale, getT } from '@/i18n/server';

import styles from './DynamicHome.module.css';
import { HomePhoto } from './HomePhoto';

/*
 * The modules the eligibility engine adds to the home (docs/engineering/dynamic-home.md). Flat on a white canvas: a heading,
 * a grid of links, no boxes and no shadows. Server components: the first HTML already holds the page. `block` arrives with its
 * copy resolved (localizeBlock). A tile or card without a real photo is text only.
 */

/** Text-only brand hero: shown when no approved campaign slide has a real photo. It makes no promise of stock. */
export function BrandHero({ block }: { block: HomeBlock }) {
  if (!block.title) return null;
  return (
    <section className={styles.brandHero} aria-labelledby="brand-hero-title" data-testid="brand-hero">
      <h1 id="brand-hero-title" className={styles.brandTitle}>
        {block.title}
      </h1>
      {block.sub ? <p className={styles.brandSub}>{block.sub}</p> : null}
      {block.href && block.cta ? (
        <Link href={block.href} className={buttonClassName({ size: 'md' })}>
          {block.cta}
        </Link>
      ) : null}
    </section>
  );
}

/** "Sell on AfroTransact": a flat band at the foot of the page. */
export function SellBand({ block }: { block: HomeBlock }) {
  if (!block.title || !block.href) return null;
  return (
    <section className={styles.sellBand} aria-labelledby="sell-band-title" data-testid="sell-band">
      <div>
        <h2 id="sell-band-title" className={styles.sellTitle}>
          {block.title}
        </h2>
        {block.body ? <p className={styles.sellBody}>{block.body}</p> : null}
      </div>
      <Link href={block.href} className={buttonClassName({ size: 'sm' })}>
        {block.cta ?? block.title}
      </Link>
    </section>
  );
}

/** Department and community tiles: a real product photo (or text only) and the label, each linking where its own key points. */
export function CellTiles({ block, lcp = false }: { block: HomeBlock; lcp?: boolean }) {
  const cells = block.cells ?? [];
  if (!block.title || cells.length === 0) return null;
  const id = `tiles-${block.id}`;
  return (
    <section className={styles.module} aria-labelledby={id} data-testid={block.type}>
      <h2 id={id} className={styles.heading}>
        {block.title}
      </h2>
      <ul className={styles.tiles}>
        {cells.map((c, i) => (
          <li key={c.key ?? c.href}>
            <Link href={c.href} className={styles.tile}>
              <HomePhoto src={c.imageUrl} priority={lcp && i === 0} />
              <span className={styles.tileLabel}>{c.label}</span>
            </Link>
          </li>
        ))}
      </ul>
    </section>
  );
}

/** Services as service cards (provider, from price, city, a real photo or none). Needs no physical product. */
export async function ServiceCards({ block }: { block: HomeBlock }) {
  const services = block.services ?? [];
  if (!block.title || services.length === 0) return null;
  const [t, locale] = await Promise.all([getT(), getLocale()]);
  const fmt = createFormatters(locale);
  const id = `services-${block.id}`;
  return (
    <section className={styles.module} aria-labelledby={id} data-testid="service-cards">
      <div className={styles.headRow}>
        <h2 id={id} className={styles.heading}>
          {block.title}
        </h2>
        {block.href && block.cta ? (
          <Link href={block.href} className={styles.more}>
            {block.cta}
          </Link>
        ) : null}
      </div>
      <ul className={styles.services}>
        {services.map((s) => (
          <li key={s.id}>
            <Link href={s.href} className={styles.service}>
              <HomePhoto src={s.imageUrl} ratio="3 / 2" />
              <span className={styles.serviceName}>{s.title}</span>
              <span className={styles.serviceMeta}>{[s.provider, s.city].filter(Boolean).join(' · ')}</span>
              {typeof s.fromPriceMinor === 'number' && s.fromPriceMinor > 0 ? <span className={styles.servicePrice}>{t('home.services.fromPrice', { price: fmt.money(s.fromPriceMinor, s.currency ?? 'USD') })}</span> : null}
            </Link>
          </li>
        ))}
      </ul>
    </section>
  );
}
