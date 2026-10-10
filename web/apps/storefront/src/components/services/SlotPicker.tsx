'use client';

import { type AvailableSlot } from '@afrotransact/api-client';
import { useFormatters } from '@afrotransact/i18n/react';
import { Skeleton } from '@afrotransact/ui';
import { useQuery } from '@tanstack/react-query';
import { useEffect, useState } from 'react';

import { useT } from '@/i18n/t';
import { browserApi } from '@/lib/api/browser';
import { shopperQueries } from '@/lib/api/queries';

import styles from './Services.module.css';

export const slotTime = (tag: string, iso: string, timeZone?: string) => new Date(iso).toLocaleTimeString(tag, { hour: 'numeric', minute: '2-digit', timeZone });
export const slotDay = (tag: string, iso: string, timeZone?: string) => new Date(iso).toLocaleDateString(tag, { weekday: 'short', month: 'short', day: 'numeric', timeZone });

/** Live availability (bookings computes it from hours, overrides and existing appointments): days, then times. */
export function SlotPicker({ serviceId, addOnIds, memberId, value, onChange }: { serviceId: string; addOnIds: string[]; memberId?: string; value: AvailableSlot | null; onChange: (s: AvailableSlot | null) => void }) {
  const fmt = useFormatters();
  const t = useT();
  const api = browserApi();
  const q = { days: 21, addOnIds, memberId };
  const days = useQuery(shopperQueries.serviceDays(api, serviceId, q));
  const [date, setDate] = useState<string | null>(null);
  const firstOpen = days.data?.find((d) => d.slots > 0)?.date ?? null;
  const active = date ?? firstOpen;
  const slots = useQuery(shopperQueries.serviceSlots(api, serviceId, { date: active ?? undefined, addOnIds, memberId }));

  useEffect(() => {
    if (value && slots.data && !slots.data.some((s) => s.start === value.start)) onChange(null);
  }, [slots.data, value, onChange]);

  return (
    <div className={styles.field}>
      <strong>{t('bk.chooseDay')}</strong>
      {days.isPending && <Skeleton height={56} />}
      {days.isError && <p className={styles.error}>{t('acct.loadError')}</p>}
      <div className={styles.days} role="group" aria-label={t('bk.days')}>
        {days.data?.map((d) => {
          const dt = new Date(`${d.date}T12:00:00Z`);
          return (
            <button key={d.date} type="button" className={styles.day} disabled={d.slots === 0} aria-pressed={d.date === active} onClick={() => { setDate(d.date); onChange(null); }}
              aria-label={t('bk.dayAria', { day: dt.toLocaleDateString(fmt.tag, { weekday: 'long', month: 'long', day: 'numeric', timeZone: 'UTC' }), n: d.slots })}>
              <span>{dt.toLocaleDateString(fmt.tag, { weekday: 'short', timeZone: 'UTC' })}</span>
              <strong>{dt.getUTCDate()}</strong>
              <span className={styles.muted}>{d.slots ? t('bk.slotsN', { n: d.slots }) : t('bk.full')}</span>
            </button>
          );
        })}
      </div>
      {active && (
        <>
          <strong>{t('bk.chooseTime')}</strong>
          {slots.isPending && <Skeleton height={40} />}
          {slots.data?.length === 0 && <p className={styles.muted}>{t('bk.noSlots')}</p>}
          <div className={styles.slots} role="group" aria-label={t('bk.times')}>
            {slots.data?.map((s) => (
              <button key={s.start} type="button" className={styles.slot} aria-pressed={value?.start === s.start} onClick={() => onChange(s)}>
                {slotTime(fmt.tag, s.start)}
              </button>
            ))}
          </div>
        </>
      )}
    </div>
  );
}
