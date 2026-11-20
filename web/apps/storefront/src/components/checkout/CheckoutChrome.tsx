'use client';

import { type Cart, legalHref } from '@afrotransact/api-client';
import { LockSimple, LogoMark, ShoppingCart, wordmarkClassName } from '@afrotransact/ui';
import { useQuery } from '@tanstack/react-query';
import Link from 'next/link';
import { type ReactNode } from 'react';

import { footerLegal } from '@/components/shell/Footer';
import { useT } from '@/i18n/t';
import { browserApi } from '@/lib/api/browser';
import { siteQueries } from '@/lib/api/queries';
import { useCart } from '@/lib/cart/useCart';

import styles from './CheckoutChrome.module.css';

/** Units the shopper ticked in the cart: what this checkout is for (the cart badge counts every item, ticked or not). */
export const selectedUnits = (cart: Cart | undefined): number =>
  cart ? cart.packages.flatMap((p) => p.lines).filter((l) => l.selected).reduce((n, l) => n + l.quantity, 0) : 0;

/**
 * Slim checkout header (Amazon pattern): the brand on the start side, "Secure checkout" centred, the cart on the end side.
 * Nothing else: no search, no departments. The cart link says how many items this checkout is for.
 */
export function CheckoutHeader() {
  const t = useT();
  const { data: cart } = useCart();
  const n = selectedUnits(cart);
  return (
    <header className={styles.header} data-testid="checkout-header" data-noprint>
      <div className={styles.bar}>
        <Link href="/" className={styles.logo} aria-label={t('header.home')}>
          <LogoMark size={28} />
          <span className={`${wordmarkClassName} ${styles.wordmark}`}>afrotransact</span>
        </Link>
        <h1 className={styles.heading} data-testid="checkout-heading">
          <LockSimple weight="fill" aria-hidden />
          <span>{t('co.secureCheckout')}</span>
        </h1>
        <Link href="/cart" className={styles.cartLink} aria-label={t('header.cartAria', { count: n })} data-testid="checkout-cart-link">
          <ShoppingCart aria-hidden />
          <span className={styles.cartText}>{t('header.cart')}</span>
          {n > 0 && (
            <span className={styles.cartCount} aria-hidden>
              {n}
            </span>
          )}
        </Link>
      </div>
    </header>
  );
}

/** Slim footer: the legal links and the copyright line, nothing else. */
export function CheckoutFooter() {
  const t = useT();
  const { data: config } = useQuery(siteQueries.config(browserApi()));
  return (
    <footer className={styles.footer} data-testid="checkout-footer" data-noprint>
      <nav aria-label={t('footer.legal')}>
        <ul className={styles.legal}>
          {footerLegal(config).map(([key, msg]) => (
            <li key={key}>
              <Link className={styles.legalLink} href={legalHref(key)}>
                {t(msg)}
              </Link>
            </li>
          ))}
          <li>
            <Link className={styles.legalLink} href="/help">
              {t('footer.helpCenter')}
            </Link>
          </li>
        </ul>
      </nav>
      <p className={styles.copy}>{t('footer.copyright', { year: new Date().getFullYear() })}</p>
    </footer>
  );
}

export function CheckoutChrome({ children }: { children: ReactNode }) {
  const t = useT();
  return (
    <div className={styles.shell}>
      <a href="#checkout-main" className="at-skip-link">
        {t('a11y.skip')}
      </a>
      <CheckoutHeader />
      <main id="checkout-main" tabIndex={-1} className={styles.main}>
        {children}
      </main>
      <CheckoutFooter />
    </div>
  );
}
