'use client';

import { Skeleton, type StorefrontProviderModel } from '@afrotransact/ui';
import { useQuery } from '@tanstack/react-query';
import { useEffect } from 'react';

import { BookingPanel } from '@/components/services/BookingPanel';
import { useT } from '@/i18n/t';
import { browserApi } from '@/lib/api/browser';
import { shopperQueries } from '@/lib/api/queries';

import styles from './ProviderBooker.module.css';

/**
 * The appointment booker of a provider's page: pick one of the provider's services, then the same booking panel as the service page
 * (real free days and times from bookings, add-ons, in person or at home, the deposit now, the cancellation policy). Nothing is faked here.
 */
export function ProviderBooker({ provider, selectedId, onSelect }: { provider: StorefrontProviderModel; selectedId: string | null; onSelect: (id: string) => void }) {
  const t = useT();
  const services = provider.services;
  const only = services.length === 1 ? services[0]?.id : undefined;
  const active = selectedId && services.some((s) => s.id === selectedId) ? selectedId : (only ?? null);
  useEffect(() => {
    if (only && selectedId !== only) onSelect(only);
  }, [only, selectedId, onSelect]);
  const detail = useQuery({ ...shopperQueries.service(browserApi(), active ?? ''), enabled: active !== null });
  return (
    <div className={styles.booker}>
      {services.length > 1 ? (
        <fieldset className={styles.choices}>
          <legend className={styles.legend}>{t('store.chooseService')}</legend>
          {services.map((s) => (
            <label key={s.id} className={styles.choice}>
              <input type="radio" name="sf-service" checked={active === s.id} onChange={() => onSelect(s.id)} />
              <span>{s.name}</span>
            </label>
          ))}
        </fieldset>
      ) : null}
      {active === null ? null : detail.isPending ? <Skeleton height={240} /> : detail.data ? <div key={active} className={styles.panel}><BookingPanel service={detail.data} /></div> : <p role="alert">{t('acct.loadError')}</p>}
    </div>
  );
}
