'use client';

import { deliveryRunKeys, deliveryRunQueries, type RunAdvertorial, type RunAdvertorialQuery } from '@afrotransact/api-client';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import Link from 'next/link';
import { useEffect, useMemo, useState } from 'react';

import { Countdown } from '@/components/preorder/Countdown';
import { useT } from '@/i18n/t';
import { browserApi } from '@/lib/api/browser';
import { firstView, visitorId } from '@/lib/runs/visitor';

import styles from './Runs.module.css';

/** Counts a view or a click; best effort, a failure never reaches the shopper. The visitor id lets the server count each visitor once. */
function record(id: string, type: 'VIEW' | 'CLICK') {
  void browserApi()
    .recordRunAdvertorialEvent(id, type, visitorId())
    .catch(() => undefined);
}

function AdvertorialCard({ ad, onElapsed }: { ad: RunAdvertorial; onElapsed: () => void }) {
  const t = useT();
  useEffect(() => {
    if (firstView(ad.id)) record(ad.id, 'VIEW');
  }, [ad.id]);
  return (
    <article className={styles.advertorial} data-testid="run-advertorial" aria-label={ad.headline}>
      <span className={styles.advKicker}>{ad.place ?? t('runs.adv.kicker')}</span>
      {ad.imageUrl ? <div role="img" aria-label="" className={styles.advImage} style={{ backgroundImage: `url("${ad.imageUrl}")` }} /> : null}
      <h2 className={styles.advHeadline}>{ad.headline}</h2>
      {ad.body ? <p className={styles.advBody}>{ad.body}</p> : null}
      {ad.deliveryDisplay || ad.feeDisplay ? (
        <p className={styles.advFacts}>
          {[ad.deliveryDisplay ? t('runs.adv.delivers', { when: ad.deliveryDisplay }) : null, ad.feeDisplay ? t('runs.adv.fee', { fee: ad.feeDisplay }) : null].filter(Boolean).join(' · ')}
        </p>
      ) : null}
      {ad.featuredProducts.length > 0 ? (
        <ul className={styles.advProducts} aria-label={t('runs.adv.featured')} data-testid="run-advertorial-products">
          {ad.featuredProducts.map((p) => (
            <li key={p.atin}>
              <Link href={`/p/${encodeURIComponent(p.atin)}`} className={styles.advProduct} onClick={() => record(ad.id, 'CLICK')}>
                {p.imageUrl ? <span role="img" aria-label="" className={styles.advProductImage} style={{ backgroundImage: `url("${p.imageUrl}")` }} /> : null}
                <span>{p.title}</span>
              </Link>
            </li>
          ))}
        </ul>
      ) : null}
      {ad.countdownTarget ? <Countdown variant="inline" to={ad.countdownTarget} label={t('runs.adv.closesIn')} className={styles.advCountdown} onElapsed={onElapsed} /> : null}
      <a className={styles.advCta} href={ad.ctaUrl} onClick={() => record(ad.id, 'CLICK')}>
        {ad.ctaLabel}
      </a>
    </article>
  );
}

/**
 * Advertorials for the shopper's delivery location (S263): the pre-order windows and delivery runs that take orders
 * there right now, one A/B variant each, with a live countdown to the close. Each is counted once per visit as a view and
 * once per click; the button's link carries the order attribution (src=page&cr=adv-...). Renders nothing when there is none.
 */
export function RunAdvertorials({ place, limit = 2 }: { place?: Omit<RunAdvertorialQuery, 'visitor' | 'limit'>; limit?: number }) {
  const api = browserApi();
  const qc = useQueryClient();
  const [visitor, setVisitor] = useState<string | null>(null);
  useEffect(() => setVisitor(visitorId()), []);
  const query = useMemo<RunAdvertorialQuery>(() => ({ ...place, visitor: visitor ?? undefined, limit }), [place, visitor, limit]);
  const ads = useQuery({ ...deliveryRunQueries.advertorials(api, query), enabled: visitor !== null });
  const items = ads.data?.items ?? [];
  if (items.length === 0) return null;
  return (
    <section className={styles.advertorials} aria-label={items[0]?.place ?? undefined} data-testid="run-advertorials">
      {items.map((ad) => (
        <AdvertorialCard key={ad.id} ad={ad} onElapsed={() => void qc.invalidateQueries({ queryKey: deliveryRunKeys.all })} />
      ))}
    </section>
  );
}
