'use client';

import { legalHref } from '@afrotransact/api-client';
import Link from 'next/link';
import { usePathname } from 'next/navigation';

import { useT } from '@/i18n/t';
import { ACCEPT_ALL, REJECT_ALL } from '@/lib/consent/consent';
import { useConsent } from '@/lib/consent/ConsentProvider';

import styles from './Legal.module.css';

/** Publishes the banner's height as --at-cookie-banner-h: the page pads its end by it, so nothing stays under the banner. */
function publishHeight(el: HTMLElement | null) {
  if (!el || typeof ResizeObserver === 'undefined') return;
  const root = document.documentElement;
  const ro = new ResizeObserver(() => root.style.setProperty('--at-cookie-banner-h', `${Math.ceil(el.getBoundingClientRect().height)}px`));
  ro.observe(el);
  return () => {
    ro.disconnect();
    root.style.removeProperty('--at-cookie-banner-h');
  };
}

/**
 * First-visit cookie banner (design/AfroTransact Shop.dc.html): equal-weight "Accept all" and "Reject non-essential",
 * plus "Manage preferences" (the Cookie Notice). Shown until the visitor makes a choice; not on the Cookie Notice
 * itself, which has the full controls.
 */
export function CookieBanner() {
  const t = useT();
  const pathname = usePathname();
  const { decided, gpc, save } = useConsent();
  if (decided || pathname === legalHref('cookies')) return null;
  return (
    <section ref={publishHeight} aria-labelledby="cb-title" className={styles.banner} data-noprint>
      <h2 id="cb-title" className={styles.bannerTitle}>
        {t('legal.cbTitle')}
      </h2>
      <p className={styles.bannerText}>{t('legal.cbText')}</p>
      {gpc && <p className={styles.bannerGpc}>{t('legal.gpc')}</p>}
      <div className={styles.buttons}>
        <button type="button" className={`${styles.btn} ${styles.btnDark} ${styles.bannerBtn}`} onClick={() => void save(ACCEPT_ALL, 'banner')}>
          {t('legal.acceptAll')}
        </button>
        <button type="button" className={`${styles.btn} ${styles.btnDark} ${styles.bannerBtn}`} onClick={() => void save(REJECT_ALL, 'banner')}>
          {t('legal.rejectAll')}
        </button>
        <Link href={legalHref('cookies')} className={`${styles.btn} ${styles.bannerBtn}`}>
          {t('legal.manage')}
        </Link>
      </div>
    </section>
  );
}
