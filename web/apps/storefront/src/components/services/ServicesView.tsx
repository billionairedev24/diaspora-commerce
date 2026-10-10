'use client';

import { formatMoney, isApiError, ZipSchema } from '@afrotransact/api-client';
import { Button, buttonClassName, CalendarCheck, Skeleton, Stars, TextField } from '@afrotransact/ui';
import { useQuery } from '@tanstack/react-query';
import Link from 'next/link';
import { type FormEvent, useState } from 'react';

import { t } from '@/i18n/t';
import { browserApi } from '@/lib/api/browser';
import { accountQueries, shopperQueries, siteQueries } from '@/lib/api/queries';

import styles from './Services.module.css';

const KINDS = ['Hair & beauty', 'Catering', 'Tailoring', 'Tax & legal'] as const;

/** Services near you (S150): search by ZIP (deliver-to ZIP by default), category chips, provider listings. */
export function ServicesView() {
  const api = browserApi();
  const loc = useQuery(siteQueries.deliveryLocation(api));
  const me = useQuery(accountQueries.me(api));
  const [q, setQ] = useState('');
  const [zipInput, setZipInput] = useState('');
  const [query, setQuery] = useState<{ q?: string; category?: string; zip?: string }>({});
  const [zipError, setZipError] = useState<string | null>(null);
  const results = useQuery(shopperQueries.serviceSearch(api, query));

  const submit = (e: FormEvent) => {
    e.preventDefault();
    const zip = zipInput.trim();
    if (zip && !ZipSchema.safeParse(zip).success) return setZipError(t('location.invalidZip'));
    setZipError(null);
    setQuery((x) => ({ ...x, q: q.trim() || undefined, zip: zip || undefined }));
  };

  const city = results.data?.city || loc.data?.city || '';
  return (
    <div className={styles.page}>
      <div className={styles.search}>
        <h1 className={styles.h1}>{city ? t('svc.near', { city }) : t('svc.title')}</h1>
        {me.data?.authenticated && (
          <Link href="/account/bookings" className={buttonClassName({ variant: 'neutral', size: 'sm' })}>
            <CalendarCheck aria-hidden /> {t('svc.yourAppointments')}
          </Link>
        )}
      </div>
      <form className={styles.search} onSubmit={submit} role="search" aria-label={t('svc.searchAria')}>
        <TextField label={t('svc.what')} value={q} onChange={(e) => setQ(e.target.value)} placeholder={t('svc.whatPlaceholder')} />
        <TextField label={t('svc.zip')} value={zipInput} onChange={(e) => setZipInput(e.target.value)} placeholder={loc.data?.zip ?? '77042'} inputMode="numeric" maxLength={5} error={zipError} />
        <Button type="submit">{t('svc.search')}</Button>
      </form>
      <div className={styles.chips} role="group" aria-label={t('svc.kinds')}>
        <button type="button" className={styles.chip} aria-pressed={!query.category} onClick={() => setQuery((x) => ({ ...x, category: undefined }))}>
          {t('svc.all')}
        </button>
        {KINDS.map((k) => (
          <button key={k} type="button" className={styles.chip} aria-pressed={query.category === k} onClick={() => setQuery((x) => ({ ...x, category: k }))}>
            {k}
          </button>
        ))}
      </div>
      {results.isPending && <Skeleton height={120} />}
      {results.isError && <p className={styles.error} role="alert">{isApiError(results.error) ? results.error.message : t('acct.loadError')}</p>}
      {results.data && results.data.services.length === 0 && <p className={styles.muted}>{t('svc.none')}</p>}
      <ul className={styles.list}>
        {results.data?.services.map((s) => (
          <li key={s.serviceId}>
            <Link href={`/services/${encodeURIComponent(s.serviceId)}`} className={styles.listing} data-testid="service-listing">
              <span className={styles.cover} aria-hidden />
              <span className={styles.info}>
                <span className={styles.kind}>{s.category}</span>
                <span className={styles.name}>{s.name}</span>
                <span>{s.providerName}</span>
                <Stars rating={s.rating} reviewCount={s.ratingCount} size={12} />
                <span className={styles.muted}>
                  {s.location}
                  {s.nextAvailable ? ` · ${t('svc.next', { when: s.nextAvailable })}` : ''}
                </span>
              </span>
              <span className={styles.price}>
                {t('svc.from')}
                <strong>{formatMoney(s.priceFromMinor)}</strong>
                {t('svc.deposit', { amount: formatMoney(s.depositMinor) })}
              </span>
            </Link>
          </li>
        ))}
      </ul>
    </div>
  );
}
