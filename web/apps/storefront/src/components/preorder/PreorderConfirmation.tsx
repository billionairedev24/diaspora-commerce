'use client';

import { type PreorderConfirmation as Confirmation, preorderQueries } from '@afrotransact/api-client';
import { useFormatters } from '@afrotransact/i18n/react';
import { CalendarPlus, CheckCircle } from '@afrotransact/ui';
import { useQuery } from '@tanstack/react-query';
import { type ReactNode } from 'react';

import { useT } from '@/i18n/t';
import { browserApi } from '@/lib/api/browser';
import { preorderDates } from '@/lib/preorder/copy';

import styles from './Preorder.module.css';

/** iCalendar (RFC 5545) all-day event for the delivery day. */
export function deliveryIcs(c: Confirmation, title: string, now = new Date()): string {
  const day = c.deliversOn.replace(/-/g, '');
  const next = new Date(`${c.deliversOn}T12:00:00Z`);
  next.setUTCDate(next.getUTCDate() + 1);
  const stamp = now.toISOString().replace(/[-:]/g, '').replace(/\.\d{3}/, '');
  const esc = (s: string) => s.replace(/[\\;,]/g, (m) => `\\${m}`);
  return [
    'BEGIN:VCALENDAR',
    'VERSION:2.0',
    'PRODID:-//AfroTransact//Pre-order//EN',
    'BEGIN:VEVENT',
    `UID:${c.number}@afrotransact.com`,
    `DTSTAMP:${stamp}`,
    `DTSTART;VALUE=DATE:${day}`,
    `DTEND;VALUE=DATE:${next.toISOString().slice(0, 10).replace(/-/g, '')}`,
    `SUMMARY:${esc(title)}`,
    `DESCRIPTION:${esc(`${c.windowName} · ${c.number}`)}`,
    'END:VEVENT',
    'END:VCALENDAR'
  ].join('\r\n');
}

/** "You're in: Lubbock Pre-order #7" with the pre-order number, delivery day and amount charged. */
export function PreorderConfirmationCard({ c, charged }: { c: Confirmation; charged: string }) {
  const t = useT();
  const fmt = useFormatters();
  const d = preorderDates({ opensAt: c.closesAt, closesAt: c.closesAt, deliversOn: c.deliversOn, timeZone: c.timeZone }, fmt.tag);
  const addToCalendar = () => {
    const blob = new Blob([deliveryIcs(c, t('preorder.conf.calendarTitle', { name: c.windowName }))], { type: 'text/calendar' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `${c.number}.ics`;
    a.click();
    URL.revokeObjectURL(url);
  };
  return (
    <section className={styles.conf} role="status" aria-labelledby="preorder-conf-title" data-testid="preorder-confirmation">
      <CheckCircle weight="fill" className={styles.confIcon} aria-hidden />
      <h2 id="preorder-conf-title" className={styles.confTitle}>
        {t('preorder.conf.title', { name: c.windowName })}
      </h2>
      <dl className={styles.facts}>
        <div className={styles.fact}>
          <dt>{t('preorder.conf.number')}</dt>
          <dd>{c.number}</dd>
        </div>
        <div className={styles.fact}>
          <dt>{t('preorder.conf.delivered')}</dt>
          <dd>{d.deliversT}</dd>
        </div>
        <div className={styles.fact}>
          <dt>{t('preorder.conf.charged')}</dt>
          <dd>{charged}</dd>
        </div>
      </dl>
      <p className={styles.confBody}>
        {c.otherItemCount > 0 ? `${t('preorder.conf.others', { n: c.otherItemCount })} ` : ''}
        {t('preorder.conf.body', { closes: d.closesT })}
      </p>
      <div className={styles.confActions}>
        <button type="button" className={styles.outlineButton} onClick={addToCalendar}>
          <CalendarPlus aria-hidden />
          {t('preorder.conf.calendar')}
        </button>
      </div>
    </section>
  );
}

/** After checkout: the pre-order confirmation when the order holds a pre-order package, else `fallback`. */
export function PlacedConfirmation({ orderNumber, fallback }: { orderNumber: string; fallback: ReactNode }) {
  const fmt = useFormatters();
  const { data } = useQuery(preorderQueries.confirmation(browserApi(), orderNumber));
  if (!data?.preorder) return <>{fallback}</>;
  return <PreorderConfirmationCard c={data.preorder} charged={fmt.money(data.totalMinor, data.currency)} />;
}
