'use client';

import { type HeroSlide } from '@afrotransact/api-client';
import { CaretLeft, CaretRight, ImagePlaceholder } from '@afrotransact/ui';
import Link from 'next/link';
import { useEffect, useState } from 'react';

import { t } from '@/i18n/t';

import styles from './Home.module.css';

/** Hero carousel from placements: auto-advances every 7 s, pauses on hover/focus, honours reduced motion. */
export function Hero({ slides }: { slides: HeroSlide[] }) {
  const [idx, setIdx] = useState(0);
  const [paused, setPaused] = useState(false);
  const n = slides.length;
  useEffect(() => {
    if (n < 2 || paused) return;
    if (window.matchMedia?.('(prefers-reduced-motion: reduce)').matches) return;
    const id = setInterval(() => setIdx((i) => (i + 1) % n), 7000);
    return () => clearInterval(id);
  }, [n, paused]);
  const h = slides[((idx % n) + n) % n];
  if (!h) return null;
  return (
    <section
      className={`${styles.hero} ${styles[h.theme]} full-bleed`}
      aria-roledescription="carousel"
      aria-label={t('home.carousel')}
      onMouseEnter={() => setPaused(true)}
      onMouseLeave={() => setPaused(false)}
      onFocusCapture={() => setPaused(true)}
      onBlurCapture={() => setPaused(false)}
    >
      <div className={styles.heroInner} aria-roledescription="slide" aria-label={`${(idx % n) + 1} / ${n}`}>
        <div className={styles.heroText}>
          <span className={styles.kicker}>{h.kicker}</span>
          <h1 className={styles.heroTitle}>{h.title}</h1>
          <p className={styles.heroSub}>{h.sub}</p>
          <Link href={h.href} className={`${styles.heroCta} ${styles[`cta-${h.theme}`]}`}>
            {h.cta}
          </Link>
          <span className={styles.heroLabel}>{h.label}</span>
        </div>
        <ImagePlaceholder className={styles.heroImage} src={h.imageUrl} alt={h.title} ratio="16 / 9" caption={t('home.campaignImage')} />
      </div>
      {n > 1 && (
        <>
          <button type="button" className={`${styles.arrow} ${styles.prev}`} onClick={() => setIdx((i) => (i - 1 + n) % n)} aria-label={t('home.heroPrev')}>
            <CaretLeft size={24} aria-hidden />
          </button>
          <button type="button" className={`${styles.arrow} ${styles.next}`} onClick={() => setIdx((i) => (i + 1) % n)} aria-label={t('home.heroNext')}>
            <CaretRight size={24} aria-hidden />
          </button>
          <div className={styles.dots}>
            {slides.map((s, i) => (
              <button
                key={s.id}
                type="button"
                className={`${styles.dot} ${i === idx % n ? styles.dotOn : ''}`}
                onClick={() => setIdx(i)}
                aria-label={t('home.heroGoTo', { n: i + 1 })}
                aria-current={i === idx % n ? 'true' : undefined}
              />
            ))}
          </div>
        </>
      )}
    </section>
  );
}
