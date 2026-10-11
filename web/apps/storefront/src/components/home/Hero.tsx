'use client';

import { type HeroSlide } from '@afrotransact/api-client';
import { CaretLeft, CaretRight, ImagePlaceholder, Pause, Play } from '@afrotransact/ui';
import Link from 'next/link';
import { type TouchEvent, useEffect, useRef, useState } from 'react';

import { useT } from '@/i18n/t';

import styles from './Home.module.css';

/** Hero carousel from placements: auto-advances every 7 s, pauses on hover/focus, honours reduced motion; swipeable on
    touch screens (the arrows hide on phones). */
export function Hero({ slides }: { slides: HeroSlide[] }) {
  const t = useT();
  const [idx, setIdx] = useState(0);
  const [paused, setPaused] = useState(false);
  // Explicit pause (WCAG 2.2.2): stays paused until the shopper presses play.
  const [stopped, setStopped] = useState(false);
  const n = slides.length;
  // Swipe on touch screens: a mostly horizontal drag of 48px or more changes the slide (mirrored in right-to-left).
  const touch = useRef<{ x: number; y: number } | null>(null);
  const onTouchStart = (e: TouchEvent) => {
    const p = e.touches[0];
    touch.current = p ? { x: p.clientX, y: p.clientY } : null;
  };
  const onTouchEnd = (e: TouchEvent) => {
    const start = touch.current;
    const p = e.changedTouches[0];
    touch.current = null;
    if (!start || !p || n < 2) return;
    const dx = p.clientX - start.x;
    if (Math.abs(dx) < 48 || Math.abs(dx) < Math.abs(p.clientY - start.y) * 1.5) return;
    const rtl = getComputedStyle(e.currentTarget).direction === 'rtl';
    const step = (dx < 0) !== rtl ? 1 : -1;
    setIdx((i) => (i + step + n) % n);
  };
  useEffect(() => {
    if (n < 2 || paused || stopped) return;
    if (window.matchMedia?.('(prefers-reduced-motion: reduce)').matches) return;
    const id = setInterval(() => setIdx((i) => (i + 1) % n), 7000);
    return () => clearInterval(id);
  }, [n, paused, stopped]);
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
      onTouchStart={onTouchStart}
      onTouchEnd={onTouchEnd}
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
        <ImagePlaceholder className={styles.heroImage} src={h.imageUrl} alt="" ratio="16 / 9" loading={idx === 0 ? 'eager' : 'lazy'} />
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
            <button type="button" className={styles.pause} onClick={() => setStopped((v) => !v)} aria-label={stopped ? t('home.heroPlay') : t('home.heroPause')}>
              {stopped ? <Play size={16} weight="fill" aria-hidden /> : <Pause size={16} weight="fill" aria-hidden />}
            </button>
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
