'use client';

import { deliveryRunQueries } from '@afrotransact/api-client';
import { EmptyState, FlatPage, Message, Section, textLinkClassName } from '@afrotransact/ui';
import { useQuery } from '@tanstack/react-query';
import Link from 'next/link';

import { AccountEnv } from '@/components/account/AccountEnv';
import { AccountHeader } from '@/components/account/AccountHeader';
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
    <FlatPage>
      <AccountHeader title={t('runs.alerts.title')} lead={t('runs.alerts.intro')} />
      <p className={styles.policy}>
        {t('runs.alerts.phone')} <Link href="/account/profile">{t('runs.alerts.phoneLink')}</Link>
      </p>
      {error ? <Message tone="error">{error}</Message> : null}
      {locations.isPending || mine.isPending ? <Message tone="muted">{t('runs.loading')}</Message> : null}
      {locations.isError || mine.isError ? <Message tone="error">{t('runs.error.load')}</Message> : null}
      {locations.data && locations.data.items.length === 0 ? <EmptyState>{t('runs.alerts.none')}</EmptyState> : null}
      {locations.data?.items.map((l) => (
        <Section
          key={l.id}
          title={l.name}
          id={`alerts-${l.slug}`}
          testId={`alerts-${l.slug}`}
          action={
            <Link href={`/delivery-runs/${l.slug}`} className={textLinkClassName}>
              {t('runs.alerts.viewPage')}
            </Link>
          }
        >
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
        </Section>
      ))}
      <Message tone="muted">{t('runs.alerts.footer')}</Message>
    </FlatPage>
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
