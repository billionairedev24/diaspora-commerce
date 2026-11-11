'use client';

import { isApiError } from '@afrotransact/api-client';
import { useFormatters } from '@afrotransact/i18n/react';
import { buttonClassName, CalendarCheck, Skeleton } from '@afrotransact/ui';
import { useQuery } from '@tanstack/react-query';
import Link from 'next/link';
import { useRef, useState } from 'react';

import { LocationPicker } from '@/components/shell/LocationPicker';
import { type MessageKey, useT } from '@/i18n/t';
import { browserApi } from '@/lib/api/browser';
import { accountQueries, shopperQueries, siteQueries } from '@/lib/api/queries';

import { categoryKey } from './format';
import { ServiceEmpty } from './ServiceEmpty';
import { ServiceRow } from './ServiceRow';
import styles from './Services.module.css';
import { ServiceSearchBox } from './ServiceSearchBox';

const SORTS = ['NEAREST', 'SOONEST', 'PRICE', 'RATING'] as const;
type Sort = (typeof SORTS)[number];

/**
 * Services near you (S150): in-person services that serve the deliver-to place. The place comes from the navbar location
 * ("Services near Georgetown", with a small Change link that opens the same location popover): there is no ZIP box. One
 * search box with suggestions, category chips with counts for this place, sorting, flat rows, and designed empty states.
 */
export function ServicesView({ initialCategory = '', initialText = '' }: { initialCategory?: string; initialText?: string }) {
  const t = useT();
  const fmt = useFormatters();
  const api = browserApi();
  const loc = useQuery(siteQueries.deliveryLocation(api));
  const me = useQuery(accountQueries.me(api));
  const [text, setText] = useState(initialText);
  const [category, setCategory] = useState(initialCategory);
  const [sort, setSort] = useState<Sort>('NEAREST');
  const [pickerOpen, setPickerOpen] = useState(false);
  const changeRef = useRef<HTMLButtonElement>(null);
  const results = useQuery(shopperQueries.serviceSearch(api, { ...(text ? { q: text } : {}), ...(category ? { category } : {}), sort }));

  const data = results.data;
  const city = data?.city || loc.data?.city || '';
  const state = data?.state || loc.data?.state || '';
  const accountEmail = me.data?.authenticated ? me.data.user?.email : undefined;
  const total = data?.categories.reduce((n, c) => n + c.count, 0) ?? 0;

  return (
    <div className={styles.page}>
      <div className={styles.head}>
        <div>
          <h1 className={styles.h1}>
            {city ? t('svc.near', { city }) : t('svc.title')}
            <button ref={changeRef} type="button" className={styles.change} onClick={() => setPickerOpen(true)} aria-haspopup="dialog" aria-expanded={pickerOpen} aria-label={city ? t('svc.changeAria', { city }) : t('svc.setLocation')}>
              {city ? t('svc.change') : t('svc.setLocation')}
            </button>
          </h1>
          {data?.zone && <p className={styles.zone}>{t('svc.zoneLine', { zone: data.zone })}</p>}
        </div>
        {me.data?.authenticated && (
          <Link href="/account/bookings" className={buttonClassName({ variant: 'neutral', size: 'sm' })}>
            <CalendarCheck aria-hidden /> {t('svc.yourAppointments')}
          </Link>
        )}
      </div>
      <LocationPicker open={pickerOpen} onOpenChange={setPickerOpen} anchorRef={changeRef} />

      <div className={styles.controls}>
        <ServiceSearchBox value={text} onSearch={setText} />
        <label className={styles.sort}>
          <span>{t('svc.sort')}</span>
          <select value={sort} onChange={(e) => setSort(e.target.value as Sort)} aria-label={t('svc.sort')}>
            {SORTS.map((s) => (
              <option key={s} value={s}>
                {t(`svc.sort.${s}` as MessageKey)}
              </option>
            ))}
          </select>
        </label>
      </div>

      {data && data.categories.length > 0 && (
        <div className={styles.chips} role="group" aria-label={t('svc.kinds')}>
          <button type="button" className={styles.chip} aria-pressed={!category} onClick={() => setCategory('')}>
            {t('svc.all')} ({fmt.number(total)})
          </button>
          {data.categories.map((c) => {
            const key = categoryKey(c.category);
            return (
              <button key={c.category} type="button" className={styles.chip} aria-pressed={category === c.category} onClick={() => setCategory(c.category)}>
                {key ? t(key) : c.category} ({fmt.number(c.count)})
              </button>
            );
          })}
        </div>
      )}

      {results.isPending && (
        <div className={styles.skeletons} aria-busy="true">
          <Skeleton height={120} />
          <Skeleton height={120} />
        </div>
      )}
      {results.isError && (
        <p className={styles.error} role="alert">
          {isApiError(results.error) ? results.error.message : t('acct.loadError')}
        </p>
      )}
      {data && data.services.length > 0 && (
        <ul className={styles.list} aria-label={t('svc.resultsAria', { city })}>
          {data.services.map((s) => (
            <ServiceRow key={s.serviceId} service={s} visitorCity={city} visitorState={state} />
          ))}
        </ul>
      )}
      {data && data.services.length === 0 && (
        <ServiceEmpty search={data} city={city} state={state} category={category} text={text} accountEmail={accountEmail} onCategory={setCategory} onClearText={() => setText('')} />
      )}
    </div>
  );
}
