'use client';

import { CalendarCheck, List, LogoMark, MapPin, SealCheck, ShoppingCart, Sparkle, Storefront, Tag, wordmarkClassName } from '@afrotransact/ui';
import { useQuery } from '@tanstack/react-query';
import Link from 'next/link';
import { usePathname, useSearchParams } from 'next/navigation';
import { useState } from 'react';

import { t } from '@/i18n/t';
import { browserApi } from '@/lib/api/browser';
import { accountQueries, cartQueries, DEPARTMENTS_LIST, searchHref, siteQueries } from '@/lib/api/queries';

import { AccountMenu } from './AccountMenu';
import { AnnouncementBar } from './AnnouncementBar';
import { DeliverToDialog } from './DeliverToDialog';
import styles from './Header.module.css';
import { useLolaLauncher } from './LolaLauncher';
import { SearchBox } from './SearchBox';

export function Header() {
  const api = browserApi();
  const { data: location } = useQuery(siteQueries.deliveryLocation(api));
  const { data: cart } = useQuery(cartQueries.current(api));
  const { data: me } = useQuery(accountQueries.me(api));
  const [locOpen, setLocOpen] = useState(false);
  const lola = useLolaLauncher();
  const pathname = usePathname();
  const params = useSearchParams();
  const count = cart?.itemCount ?? 0;
  const label = location?.label ?? '';
  const activeDept = pathname === '/s' ? params.get('dept') : null;

  return (
    <>
      <header className={styles.header}>
        <div className={styles.bar}>
          <Link href="/" className={`${styles.chromeBtn} ${styles.logoBtn}`} aria-label={t('header.home')}>
            <LogoMark size={32} />
            <span className={`${wordmarkClassName} ${styles.wordmark}`}>afrotransact</span>
          </Link>
          <button type="button" className={styles.chromeBtn} onClick={() => setLocOpen(true)} aria-label={t('header.deliverToAria', { label })} aria-haspopup="dialog">
            <MapPin size={20} aria-hidden />
            <span className={styles.locText}>
              <span className={styles.locSmall}>{t('header.deliverTo')}</span>
              <span className={styles.locMain}>{label}</span>
            </span>
          </button>
          <div className={styles.search}>
            <SearchBox />
          </div>
          <span className={styles.spacer} />
          <button type="button" className={`${styles.chromeBtn} ${styles.logoBtn}`} onClick={() => lola.open()} aria-label={t('header.lolaAria')} aria-haspopup="dialog">
            <Sparkle size={20} weight="fill" className={styles.sparkle} aria-hidden />
            <span className={styles.lolaLabel}>{t('header.lola')}</span>
          </button>
          <AccountMenu me={me} onAskLola={() => lola.open()} />
          <Link href="/cart" className={styles.chromeBtn} aria-label={t('header.cartAria', { count })}>
            <span className={styles.cartIcon}>
              <ShoppingCart size={30} aria-hidden />
              <span className={styles.cartCount} aria-hidden data-testid="cart-count">
                {count}
              </span>
            </span>
            <span className={styles.cartLabel} aria-hidden>
              {t('header.cart')}
            </span>
          </Link>
        </div>
        <nav className={styles.nav} aria-label={t('nav.primary')}>
          <ul className={styles.navList}>
            <li>
              <Link href="/s" className={`${styles.navItem} ${styles.navBold}`}>
                <List aria-hidden />
                {t('nav.all')}
              </Link>
            </li>
            <li>
              <button type="button" className={styles.navItem} onClick={() => lola.open()} aria-haspopup="dialog">
                <Sparkle weight="fill" className={styles.gold} aria-hidden />
                {t('nav.lola')}
              </button>
            </li>
            <li>
              <Link href={searchHref({ sort: 'price_asc' })} className={styles.navItem}>
                <Tag aria-hidden />
                {t('nav.deals')}
              </Link>
            </li>
            <li>
              <Link href={searchHref({ sellerKind: 'PLATFORM' })} className={styles.navItem}>
                <SealCheck className={styles.gold} aria-hidden />
                {t('nav.brand')}
              </Link>
            </li>
            {DEPARTMENTS_LIST.map((d) => (
              <li key={d}>
                <Link href={searchHref({ department: d })} className={styles.navItem} aria-current={activeDept === d ? 'page' : undefined}>
                  {d}
                </Link>
              </li>
            ))}
            <li>
              <Link href="/services" className={styles.navItem}>
                <CalendarCheck aria-hidden />
                {t('nav.services')}
              </Link>
            </li>
            <li>
              <a href="/sell" className={styles.navItem}>
                <Storefront aria-hidden />
                {t('nav.sell')}
              </a>
            </li>
            <li>
              <a href="/provide" className={styles.navItem}>
                <CalendarCheck aria-hidden />
                {t('nav.provide')}
              </a>
            </li>
          </ul>
        </nav>
      </header>
      <AnnouncementBar />
      <DeliverToDialog open={locOpen} onOpenChange={setLocOpen} />
    </>
  );
}
