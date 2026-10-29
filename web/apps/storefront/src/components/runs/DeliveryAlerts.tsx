'use client';

import { deliveryRunQueries } from '@afrotransact/api-client';
import { useQuery } from '@tanstack/react-query';
import Link from 'next/link';

import { AccountEnv } from '@/components/account/AccountEnv';
import accountStyles from '@/components/account/Pages.module.css';
import { useT } from '@/i18n/t';
import { browserApi } from '@/lib/api/browser';

import { ChannelToggles } from './ChannelToggles';
import styles from './Runs.module.css';
import { useRunSubscriptions } from './useRunSubscriptions';

function DeliveryAlertsContent() {
  const t = useT();
  const api = browserApi();
  const locations = useQuery(deliveryRunQueries.locations(api));
  const { mine, subscriptions, pending, error, toggle } = useRunSubscriptions('account');
  return (
    <div className={accountStyles.accountPage}>
      <nav className={accountStyles.crumb} aria-label={t('acct.breadcrumb')}>
        <Link href="/account">{t('account.yourAccount')}</Link>
        <span aria-hidden>›</span>
        <span aria-current="page" className={accountStyles.crumbCurrent}>
          {t('runs.alerts.title')}
        </span>
      </nav>
      <h1 className={accountStyles.h1}>{t('runs.alerts.title')}</h1>
      <p className={accountStyles.muted}>{t('runs.alerts.intro')}</p>
      <p className={accountStyles.callout}>
        {t('runs.alerts.phone')} <Link href="/account/profile">{t('runs.alerts.phoneLink')}</Link>
      </p>
      {error ? (
        <p role="alert" className={accountStyles.error}>
          {error}
        </p>
      ) : null}
      {locations.isPending || mine.isPending ? <p className={accountStyles.muted}>{t('runs.loading')}</p> : null}
      {locations.isError || mine.isError ? (
        <p role="alert" className={accountStyles.error}>
          {t('runs.error.load')}
        </p>
      ) : null}
      {locations.data && locations.data.items.length === 0 ? <p className={accountStyles.muted}>{t('runs.alerts.none')}</p> : null}
      <ul className={accountStyles.cards}>
        {locations.data?.items.map((l) => (
          <li key={l.id} className={accountStyles.card} data-testid={`alerts-${l.slug}`}>
            <div className={accountStyles.cardHead}>
              <strong>{l.name}</strong>
              <Link href={`/delivery-runs/${l.slug}`}>{t('runs.alerts.viewPage')}</Link>
            </div>
            <ChannelToggles
              locationName={l.name}
              subscription={subscriptions.find((s) => s.locationId === l.id)}
              pending={pending?.locationId === l.id ? pending.channel : null}
              onToggle={(channel, on) => toggle(l.id, channel, on)}
            />
            {l.whatsappInviteUrl ? (
              <a className={styles.hint} href={l.whatsappInviteUrl} rel="noopener noreferrer" target="_blank">
                {t('runs.community', { place: l.name })}
              </a>
            ) : null}
          </li>
        ))}
      </ul>
      <p className={accountStyles.muted}>{t('runs.alerts.footer')}</p>
    </div>
  );
}

/** Your Account › Delivery alerts: per delivery location and channel, with the consent kept in delivery-runs. */
export function DeliveryAlertsView() {
  return (
    <AccountEnv>
      <DeliveryAlertsContent />
    </AccountEnv>
  );
}
