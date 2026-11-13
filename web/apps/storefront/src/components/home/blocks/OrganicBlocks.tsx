import { type HomeBlock, type ProductSummary } from '@afrotransact/api-client';
import { buttonClassName, ImagePlaceholder } from '@afrotransact/ui';
import Link from 'next/link';

import { Countdown } from '@/components/preorder/Countdown';
import { getT } from '@/i18n/server';

import blocks from '../Blocks.module.css';
import { RailSection } from '../HomeSections';

const TONES: Record<string, string> = { ink: blocks.ink ?? '', gold: blocks.gold ?? '', green: blocks.green ?? '', sand: blocks.sand ?? '' };

/** Whole-number saving of the offer price against its compare-at price (0 when there is none). */
export function dealPercent(p: Pick<ProductSummary, 'priceMinor' | 'listPriceMinor'>): number {
  const list = p.listPriceMinor;
  return list && list > p.priceMinor && p.priceMinor > 0 ? Math.floor(((list - p.priceMinor) * 100) / list) : 0;
}

/** Deals of the day: discount badge from the compare-at price and a countdown to the end of the offer window. */
export async function Deals({ block }: { block: HomeBlock }) {
  const t = await getT();
  if (!block.rail) return null;
  return (
    <RailSection
      rail={block.rail}
      extra={block.endsAt ? <Countdown variant="inline" to={block.endsAt} label={t('home.dealEnds')} className={blocks.countdown} /> : null}
      wrap={(card, i) => {
        const pct = block.rail ? dealPercent(block.rail.items[i] ?? { priceMinor: 0, listPriceMinor: null }) : 0;
        return pct >= 5 ? (
          <div className={blocks.dealCell}>
            <span className={blocks.dealPct}>{t('home.dealPct', { pct })}</span>
            {card}
          </div>
        ) : (
          card
        );
      }}
    />
  );
}

/** Community and seller spotlights: the rail with a one-line reason beside its title ("4.8 average from 120 reviews"). */
export async function Spotlight({ block }: { block: HomeBlock }) {
  const rail = block.rail;
  if (!rail || !block.href) return null;
  return <RailSection rail={rail} extra={block.sub ? <span className={blocks.sub}>{block.sub}</span> : null} />;
}

/** Membership, sign-in and services bands: a flat strip with one line and one button. */
export function PromoBand({ block }: { block: HomeBlock }) {
  if (!block.title || !block.href) return null;
  const tone = TONES[block.tone ?? 'sand'] ?? blocks.sand;
  return (
    <section className={`${blocks.promo} ${tone}`} aria-label={block.title} data-testid={`promo-${block.id}`}>
      <div className={blocks.promoText}>
        <h2 className={blocks.promoTitle}>{block.title}</h2>
        {block.sub ?? block.body ? <p className={blocks.promoSub}>{block.sub ?? block.body}</p> : null}
      </div>
      <Link href={block.href} className={buttonClassName({ size: 'sm' })}>
        {block.cta ?? block.title}
      </Link>
    </section>
  );
}

/** Shop by occasion: linked cells, each with the photo of a real product (or a fixed image). */
export function Occasions({ block }: { block: HomeBlock }) {
  const cells = block.cells ?? [];
  if (cells.length < 3) return null;
  return (
    <section className={blocks.band} aria-labelledby={`occ-${block.id}`}>
      <div className={blocks.head}>
        <h2 id={`occ-${block.id}`} className={blocks.title}>
          {block.title}
        </h2>
      </div>
      <ul className={blocks.occasions}>
        {cells.map((c) => (
          <li key={c.href + c.label}>
            <Link href={c.href} className={blocks.occasion}>
              <ImagePlaceholder className={blocks.occasionImage} src={c.imageUrl} alt="" ratio="4 / 3" />
              <span>{c.label}</span>
            </Link>
          </li>
        ))}
      </ul>
    </section>
  );
}
