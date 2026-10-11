'use client';

import { CalendarCheck, List, LogoMark, MapPin, SealCheck, ShoppingCart, Sparkle, Storefront, Tag, wordmarkClassName } from '@afrotransact/ui';
import { useQuery } from '@tanstack/react-query';
import Link from 'next/link';
import { usePathname, useSearchParams } from 'next/navigation';
import { useEffect, useRef, useState } from 'react';

import { AttributionCapture } from '@/components/preorder/AttributionCapture';
import { PREORDER_PATH, PreorderBanners } from '@/components/preorder/PreorderBanners';
import { useT } from '@/i18n/t';
import { browserApi } from '@/lib/api/browser';
import { accountQueries, cartQueries, DEPARTMENTS_LIST, searchHref, siteQueries } from '@/lib/api/queries';
import { setAttribution } from '@/lib/preorder/attribution';
import { useOpenPreorders, usePreorderResolution } from '@/lib/preorder/usePreorder';

import { AccountMenu } from './AccountMenu';
import { AnnouncementBar } from './AnnouncementBar';
import { DeliverToDialog } from './DeliverToDialog';
import styles from './Header.module.css';
import { useLolaLauncher } from './LolaLauncher';
import { SearchBox } from './SearchBox';

/** Publishes the sticky header's live height as --at-sticky-header-h (scroll-padding-top, so keyboard focus and in-page
    anchors never land under it; WCAG 2.4.11). The height changes with the breakpoint (search row) and the font size.

    Short phone screens: when the header takes more than 22% of the screen, scrolling down tucks its first row (logo,
    deliver-to, account, cart) away above the top edge — the search and the department bar stay — and scrolling up brings
    it back. Only the sticky offset changes (no layout shift); --at-header-collapse is that first row's height. */
function useStickyHeader() {
  const ref = useRef<HTMLElement>(null);
  const [compact, setCompact] = useState(false);
  const compactRef = useRef(false);
  useEffect(() => {
    const el = ref.current;
    if (!el || typeof ResizeObserver === 'undefined') return;
    const root = document.documentElement;
    let collapse = 0;
    const measure = () => {
      const search = el.querySelector<HTMLElement>('[data-header-search]');
      const top = el.getBoundingClientRect().top;
      // The search row's offset is the first row's height once the search wraps onto its own row (<900px).
      const offset = search ? Math.round(search.getBoundingClientRect().top - top - 4) : 0;
      collapse = offset > 24 && el.offsetHeight > window.innerHeight * 0.22 ? offset : 0;
      el.style.setProperty('--at-header-collapse', `${collapse}px`);
      const visible = Math.ceil(el.getBoundingClientRect().height) - (compactRef.current ? collapse : 0);
      root.style.setProperty('--at-sticky-header-h', `${visible}px`);
    };
    measure();
    const ro = new ResizeObserver(measure);
    ro.observe(el);
    let lastY = window.scrollY;
    const onScroll = () => {
      const y = window.scrollY;
      const next = collapse > 0 && y > el.offsetHeight ? (y > lastY + 4 ? true : y < lastY - 4 ? false : compactRef.current) : false;
      lastY = y;
      if (next !== compactRef.current) {
        compactRef.current = next;
        setCompact(next);
        measure();
      }
    };
    // Keyboard focus never lands in the tucked-away row (WCAG 2.4.11): focusing into the header brings it back.
    const onFocus = () => {
      if (!compactRef.current) return;
      compactRef.current = false;
      setCompact(false);
      measure();
    };
    window.addEventListener('scroll', onScroll, { passive: true });
    window.addEventListener('resize', measure);
    el.addEventListener('focusin', onFocus);
    return () => {
      ro.disconnect();
      el.removeEventListener('focusin', onFocus);
      window.removeEventListener('scroll', onScroll);
      window.removeEventListener('resize', measure);
      root.style.removeProperty('--at-sticky-header-h');
    };
  }, []);
  return { ref, compact };
}

/** Storefront header: the top bar and the department bar stay fixed at the top while the page scrolls. The announcement
    bar and the pre-order ribbon / send-to-family banner sit under it and scroll away with the page (Shop design). */
export function Header() {
  const t = useT();
  const { ref: headerRef, compact } = useStickyHeader();
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
  const { data: preorder } = usePreorderResolution();
  const { data: openPreorders } = useOpenPreorders();
  const showPreorderNav = !!preorder?.on || (openPreorders?.windows.length ?? 0) > 0;

  return (
    <>
      <header ref={headerRef} className={styles.header} data-compact={compact ? 'true' : undefined} data-testid="site-header" data-noprint>
        <div className={styles.bar}>
          <Link href="/" className={`${styles.chromeBtn} ${styles.logoBtn}`} aria-label={t('header.home')}>
            <LogoMark size={32} />
            <span className={`${wordmarkClassName} ${styles.wordmark}`}>afrotransact</span>
          </Link>
          <button type="button" className={`${styles.chromeBtn} ${styles.locBtn}`} onClick={() => setLocOpen(true)} aria-label={t('header.deliverToAria', { label })} aria-haspopup="dialog">
            <MapPin size={20} aria-hidden />
            <span className={styles.locText}>
              <span className={styles.locSmall}>{t('header.deliverTo')}</span>
              <span className={styles.locMain}>{label}</span>
            </span>
          </button>
          <div className={styles.search} data-header-search>
            <SearchBox />
          </div>
          <span className={styles.spacer} />
          <button type="button" className={`${styles.chromeBtn} ${styles.logoBtn} ${styles.lolaBtn}`} onClick={() => lola.open()} aria-label={t('header.lolaAria')} aria-haspopup="dialog">
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
            {showPreorderNav && (
              <li>
                <Link
                  href={PREORDER_PATH}
                  className={`${styles.navItem} ${styles.navBold}`}
                  aria-current={pathname === PREORDER_PATH ? 'page' : undefined}
                  onClick={() => setAttribution('page', { po: preorder?.window?.code })}
                >
                  <CalendarCheck weight="fill" aria-hidden />
                  {t('nav.preorder')}
                </Link>
              </li>
            )}
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
      <PreorderBanners />
      <AttributionCapture />
      <DeliverToDialog open={locOpen} onOpenChange={setLocOpen} />
    </>
  );
}
