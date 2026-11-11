'use client';

import { type HomeBlock, type ProductSummary } from '@afrotransact/api-client';
import { buttonClassName, ImagePlaceholder } from '@afrotransact/ui';
import Link from 'next/link';
import { type MouseEvent, type ReactNode } from 'react';

import { CartAwareProductCard } from '@/components/common/CartAwareProductCard';
import { useT } from '@/i18n/t';
import { browserApi } from '@/lib/api/browser';
import { useAdTracking } from '@/lib/home/adTracking';

import blocks from '../Blocks.module.css';
import home from '../Home.module.css';
import { RailCarousel } from '../RailCarousel';

/*
 * Paid home modules. Each one names its advertiser in a visible "Sponsored" label (an "Ad" tag on image ads), links to
 * what the advertiser chose, and counts its own impressions and clicks per placement (useAdTracking). A module the
 * session has already seen `maxPerSession` times is not rendered.
 */

function SponsoredLabel({ advertiser }: { advertiser: string }) {
  const t = useT();
  return (
    <span className={blocks.adLabel}>
      <span className={blocks.adTag}>{t('home.sponsoredTag')}</span>
      <strong>{advertiser}</strong>
    </span>
  );
}

const TONES: Record<string, string> = { ink: blocks.ink ?? '', gold: blocks.gold ?? '', green: blocks.green ?? '', sand: blocks.sand ?? '' };

/** Full-width sponsored brand banner: advertiser logo and name, headline, image, button and an optional product strip. */
export function BrandBanner({ block }: { block: HomeBlock }) {
  const t = useT();
  const { ref, hidden, click } = useAdTracking(block.id, block.maxPerSession);
  if (hidden || !block.title || !block.href) return null;
  const advertiser = block.advertiser ?? '';
  return (
    <section ref={ref} className={`${blocks.brand} ${blocks.paid} ${TONES[block.tone ?? ''] ?? ''}`} aria-label={t('home.sponsored', { advertiser })} data-testid="brand-banner">
      <div className={blocks.brandBody}>
        <SponsoredLabel advertiser={advertiser} />
        {block.logoUrl ? <img src={block.logoUrl} alt="" className={blocks.brandLogo} loading="lazy" decoding="async" /> : null}
        <h2 className={blocks.brandTitle}>{block.title}</h2>
        {block.sub ? <p className={blocks.sub}>{block.sub}</p> : null}
        <Link href={block.href} className={buttonClassName({ size: 'sm' })} onClick={click}>
          {block.cta}
        </Link>
        {block.products?.length ? (
          <ul className={blocks.strip} aria-label={t('home.sponsoredProductsBy', { advertiser })}>
            {block.products.map((p) => (
              <li key={p.atin}>
                <Link href={`/p/${p.atin}`} className={blocks.stripItem} onClick={click}>
                  <ImagePlaceholder src={p.imageUrl} alt="" />
                  <span>{p.title}</span>
                </Link>
              </li>
            ))}
          </ul>
        ) : null}
        <span className={home.adNote ?? blocks.sub}>{t('home.adNote')}</span>
      </div>
      <ImagePlaceholder className={blocks.brandImage} src={block.imageUrl} alt="" ratio="5 / 2" />
    </section>
  );
}

/** The older single display banner (placement slot display_banner): always sponsored. */
export function DisplayBanner({ block }: { block: HomeBlock }) {
  const t = useT();
  const { ref, hidden, click } = useAdTracking(block.id, block.maxPerSession);
  if (hidden || !block.href) return null;
  const advertiser = block.advertiser ?? '';
  return (
    <section ref={ref} className={home.banner} aria-label={t('home.sponsored', { advertiser })}>
      <ImagePlaceholder className={home.bannerImage} src={block.imageUrl} alt="" ratio="970 / 250" />
      <div className={home.bannerBody}>
        <SponsoredLabel advertiser={advertiser} />
        <h2 className={home.bannerTitle}>{block.title}</h2>
        <p className={home.bannerSub}>{block.sub}</p>
        <Link href={block.href} className={buttonClassName({ size: 'sm' }, home.bannerCta)} onClick={click}>
          {block.cta}
        </Link>
        <span className={home.adNote}>{t('home.adNote')}</span>
      </div>
    </section>
  );
}

/** Two or three image ads side by side, each tagged "Ad" with the advertiser in its accessible name. */
export function AdRow({ block }: { block: HomeBlock }) {
  const t = useT();
  const { ref, hidden, click } = useAdTracking(block.id, block.maxPerSession);
  const ads = block.ads ?? [];
  if (hidden || ads.length < 2) return null;
  return (
    <section ref={ref} className={blocks.adRow} data-count={Math.min(ads.length, 3)} aria-label={t('home.adRowLabel')} data-testid="ad-row">
      {ads.slice(0, 3).map((ad) => (
        <a key={ad.href + ad.imageUrl} href={ad.href} className={blocks.adItem} onClick={click} rel="sponsored noopener" aria-label={`${t('home.adBy', { advertiser: ad.advertiser })}: ${ad.alt}`}>
          <span className={blocks.adTag} aria-hidden>
            {t('home.adTag')}
          </span>
          <ImagePlaceholder className={blocks.adImage} src={ad.imageUrl} alt="" ratio="49 / 20" />
        </a>
      ))}
    </section>
  );
}

/** "From our partners": an advertorial card labelled Sponsored with the advertiser's name. */
export function Advertorial({ block }: { block: HomeBlock }) {
  const t = useT();
  const { ref, hidden, click } = useAdTracking(block.id, block.maxPerSession);
  if (hidden || !block.title || !block.href) return null;
  const advertiser = block.advertiser ?? '';
  return (
    <section ref={ref} className={`${blocks.band} ${blocks.paid}`} aria-label={t('home.partners')} data-testid="advertorial">
      <div className={blocks.head}>
        <h2 className={blocks.sub}>{t('home.partners')}</h2>
        <SponsoredLabel advertiser={advertiser} />
      </div>
      <div className={blocks.partner}>
        <ImagePlaceholder className={blocks.partnerImage} src={block.imageUrl} alt="" ratio="3 / 2" />
        <div className={blocks.partnerText}>
          <h3 className={blocks.partnerTitle}>{block.title}</h3>
          {block.body ? <p className={blocks.partnerBody}>{block.body}</p> : null}
          <Link href={block.href} className={blocks.cta} onClick={click} rel="sponsored">
            {block.cta}
          </Link>
        </div>
      </div>
    </section>
  );
}

/**
 * Sponsored products from sellers (the sponsored-search auction): each card keeps its own "Sponsored" label, and a click
 * is reported with the search id and the card's ad token, the way sponsored search results are, so the seller's campaign
 * is charged once.
 */
export function SponsoredProducts({ block, children }: { block: HomeBlock; children?: ReactNode }) {
  const t = useT();
  const rail = block.rail;
  const { ref } = useAdTracking(block.id, block.maxPerSession);
  if (!rail || rail.items.length === 0) return null;
  const report = (p: ProductSummary, position: number) => (e: MouseEvent) => {
    if (!block.searchId || !(e.target instanceof Element) || !e.target.closest('a')) return;
    browserApi()
      .recordSearchClick({ searchId: block.searchId, atin: p.atin, position, adToken: p.sponsor?.adToken })
      .catch(() => undefined);
  };
  return (
    <section ref={ref} className={`${home.rail} ${blocks.paid}`} aria-labelledby={`rail-${block.id}`} data-testid="sponsored-products">
      <div className={home.railHead}>
        <h2 id={`rail-${block.id}`} className={home.railTitle}>
          {rail.title}
        </h2>
        <SponsoredLabel advertiser={block.advertiser ?? t('home.sponsoredTag')} />
        <Link href={rail.href} className={home.railMore}>
          {t('home.seeAll')}
        </Link>
      </div>
      <RailCarousel label={rail.title}>
        {rail.items.map((p, i) => (
          <li key={p.atin} onClickCapture={report(p, i + 1)}>
            <CartAwareProductCard product={p} variant="rail" />
          </li>
        ))}
      </RailCarousel>
      {children}
    </section>
  );
}
