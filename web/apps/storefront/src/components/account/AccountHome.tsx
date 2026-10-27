'use client';

import { type AccountMessageKey, useAccountT } from '@afrotransact/account';
import { ArrowUUpLeft, BellRinging, CalendarCheck, CreditCard, Gift, IdentificationCard, MapPin, Package, ShieldCheck, Sparkle,Truck } from '@phosphor-icons/react';
import { useQuery } from '@tanstack/react-query';
import Link from 'next/link';
import { type ReactNode } from 'react';

import { useLolaLauncher } from '@/components/shell/LolaLauncher';
import { browserApi } from '@/lib/api/browser';
import { accountQueries } from '@/lib/api/queries';

import styles from './Pages.module.css';

interface Tile {
  title: AccountMessageKey;
  sub: AccountMessageKey;
  icon: ReactNode;
  href?: string;
  count?: number;
}

function TileBody({ tile }: { tile: Tile }) {
  const t = useAccountT();
  return (
    <>
      <span className={styles.tileIcon} aria-hidden>
        {tile.icon}
      </span>
      <span className={styles.tileText}>
        <strong className={styles.tileTitle}>{t(tile.title)}</strong>
        <span className={styles.tileSub}>{t(tile.sub)}</span>
      </span>
    </>
  );
}

/** Your Account (design: ACCOUNT › acctHome): one tile per customer area, Lola one click away. */
export function AccountHome() {
  const t = useAccountT();
  const lola = useLolaLauncher();
  const me = useQuery(accountQueries.me(browserApi()));
  const user = me.data?.user;
  const tiles: Tile[] = [
    { href: '/account/security', title: 'hub.security', sub: 'hub.securitySub', icon: <ShieldCheck /> },
    { href: '/orders', title: 'hub.orders', sub: 'hub.ordersSub', icon: <Package /> },
    { href: '/account/returns', title: 'hub.returns', sub: 'hub.returnsSub', icon: <ArrowUUpLeft /> },
    { href: '/account/addresses', title: 'hub.addresses', sub: 'hub.addressesSub', icon: <MapPin /> },
    { href: '/account/payments', title: 'hub.payments', sub: 'hub.paymentsSub', icon: <CreditCard /> },
    { href: '/account/credit', title: 'hub.credit', sub: 'hub.creditSub', icon: <Gift /> },
    { href: '/account/bookings', title: 'hub.bookings', sub: 'hub.bookingsSub', icon: <CalendarCheck /> },
    { href: '/account/profile', title: 'hub.profile', sub: 'hub.profileSub', icon: <IdentificationCard /> },
    { href: '/account/notifications', title: 'hub.notifications', sub: 'hub.notificationsSub', icon: <BellRinging /> },
    { href: '/account/delivery-alerts', title: 'hub.deliveryAlerts', sub: 'hub.deliveryAlertsSub', icon: <Truck /> }
  ];
  return (
    <div className={styles.accountPage}>
      <header className={styles.hubHead}>
        <h1 className={styles.h1}>{t('hub.title')}</h1>
        {user ? <p className={styles.muted}>{t('hub.signedInAs', { email: user.email })}</p> : null}
      </header>
      <div className={styles.hubGrid}>
        {tiles.map((tile) => (
          <Link key={tile.href} href={tile.href ?? '/account'} className={styles.hubTile}>
            <TileBody tile={tile} />
          </Link>
        ))}
        <button type="button" className={styles.hubTile} onClick={() => lola.open()}>
          <TileBody tile={{ title: 'hub.lola', sub: 'hub.lolaSub', icon: <Sparkle weight="fill" /> }} />
        </button>
      </div>
    </div>
  );
}
