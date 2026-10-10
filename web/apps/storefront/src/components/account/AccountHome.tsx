'use client';

import { ArrowUUpLeft, CalendarCheck, CreditCard, MapPin, Package, ShieldCheck, Sparkle } from '@afrotransact/ui';
import { useQuery } from '@tanstack/react-query';
import Link from 'next/link';
import { type ReactNode } from 'react';

import { type MessageKey, t } from '@/i18n/t';
import { browserApi } from '@/lib/api/browser';
import { accountQueries } from '@/lib/api/queries';

import styles from './Pages.module.css';
import { PhoneVerificationCard } from './PhoneVerificationCard';

const TILES: Array<{ href: string; title: MessageKey; sub: MessageKey; icon: ReactNode }> = [
  { href: '/orders', title: 'account.orders', sub: 'acct.ordersSub', icon: <Package aria-hidden /> },
  { href: '/account/returns', title: 'account.returns', sub: 'acct.returnsSub', icon: <ArrowUUpLeft aria-hidden /> },
  { href: '/account/addresses', title: 'account.addresses', sub: 'acct.addressesSub', icon: <MapPin aria-hidden /> },
  { href: '/account/payments', title: 'account.payments', sub: 'acct.paymentsSub', icon: <CreditCard aria-hidden /> },
  { href: '/account/security', title: 'account.security', sub: 'acct.securitySub', icon: <ShieldCheck aria-hidden /> },
  { href: '/account/bookings', title: 'acct.bookings', sub: 'acct.bookingsSub', icon: <CalendarCheck aria-hidden /> }
];

/** Your Account: tiles to every section (design: ACCOUNT › acctHome). */
export function AccountHome() {
  const me = useQuery(accountQueries.me(browserApi()));
  const name = me.data?.user?.firstName;
  return (
    <div className={styles.page}>
      <h1 className={styles.h1}>{name ? t('acct.hello', { name }) : t('account.yourAccount')}</h1>
      <div className={styles.grid}>
        {TILES.map((tile) => (
          <Link key={tile.href} href={tile.href} className={styles.tile}>
            <span className={styles.tileIcon}>{tile.icon}</span>
            <span className={styles.tileText}>
              <strong>{t(tile.title)}</strong>
              <span className={styles.tileSub}>{t(tile.sub)}</span>
            </span>
          </Link>
        ))}
        <Link href="/services" className={styles.tile}>
          <span className={styles.tileIcon}>
            <Sparkle aria-hidden />
          </span>
          <span className={styles.tileText}>
            <strong>{t('acct.services')}</strong>
            <span className={styles.tileSub}>{t('acct.servicesSub')}</span>
          </span>
        </Link>
      </div>
      <PhoneVerificationCard />
    </div>
  );
}
