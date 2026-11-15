'use client';

import { type ReactNode, useMemo } from 'react';

import { cx } from '../cx';
import { Clock, MapPin } from '../icons';
import styles from './Storefront.module.css';
import { type StorefrontLabels, type StorefrontProviderModel, type StorefrontServiceModel } from './types';

/* The provider-only sections of the shared storefront: services, the appointment booker slot, the weekly schedule with the service
   area, and the team. All of it is real, public booking data; nothing is invented. */

const money = (minor: number, currency: string, locale: string) => new Intl.NumberFormat(locale, { style: 'currency', currency }).format(minor / 100);

/** "09:00" in the provider's wall clock as the viewer's locale writes a time (no zone shift: it is a local time of the provider). */
const clock = (hhmm: string, locale: string) => {
  const [h = '0', m = '0'] = hhmm.split(':');
  return new Date(Date.UTC(2024, 0, 1, Number(h), Number(m))).toLocaleTimeString(locale, { hour: 'numeric', minute: '2-digit', timeZone: 'UTC' });
};

const weekday = (day: string, locale: string) => {
  const i = ['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY', 'SUNDAY'].indexOf(day);
  return new Date(Date.UTC(2024, 0, 1 + Math.max(0, i))).toLocaleDateString(locale, { weekday: 'long', timeZone: 'UTC' });
};

const zoneName = (timeZone: string, locale: string) => {
  try {
    return new Intl.DateTimeFormat(locale, { timeZone, timeZoneName: 'long' }).formatToParts(new Date()).find((p) => p.type === 'timeZoneName')?.value ?? timeZone;
  } catch {
    return timeZone;
  }
};

const modeLabel = (s: StorefrontServiceModel, labels: StorefrontLabels) => s.modes.map((m) => (m === 'AT_HOME' ? labels.modeAtHome : labels.modeInPerson)).join(' · ');

export function ServicesSection({ provider, labels, locale, onBook }: { provider: StorefrontProviderModel; labels: StorefrontLabels; locale: string; onBook: (id: string) => void }) {
  if (provider.services.length === 0) return null;
  return (
    <section className={styles.section} aria-labelledby="sf-services">
      <h2 id="sf-services" className={styles.h2}>
        {labels.servicesTitle}
      </h2>
      <ul className={styles.serviceList}>
        {provider.services.map((s) => (
          <li key={s.id} className={styles.serviceRow}>
            {s.imageUrl ? <img src={s.imageUrl} alt="" className={styles.serviceImg} loading="lazy" decoding="async" /> : null}
            <div className={styles.serviceBody}>
              <h3 className={styles.serviceName}>{s.name}</h3>
              <p className={styles.serviceMeta}>
                {labels.minutes(s.durationMinutes)} · {modeLabel(s, labels)}
                {s.depositMinor > 0 ? ` · ${labels.deposit(money(s.depositMinor, s.currency, locale))}` : ''}
              </p>
              {s.description ? <p className={styles.serviceText}>{s.description}</p> : null}
            </div>
            <div className={styles.serviceAside}>
              <strong className={styles.servicePrice}>{money(s.priceMinor, s.currency, locale)}</strong>
              <button type="button" className={styles.secondary} onClick={() => onBook(s.id)}>
                {labels.bookService}
                <span className="at-visually-hidden"> {s.name}</span>
              </button>
            </div>
          </li>
        ))}
      </ul>
    </section>
  );
}

/** The booking section: a service picker and the app's booking panel for it (real availability). A preview shows an inert description. */
export function BookSection({ provider, labels, selectedId, onSelect, booker, preview }: {
  provider: StorefrontProviderModel;
  labels: StorefrontLabels;
  selectedId: string | null;
  onSelect: (id: string) => void;
  booker?: (ctx: { provider: StorefrontProviderModel; selectedId: string | null; onSelect: (id: string) => void }) => ReactNode;
  preview: boolean;
}) {
  if (provider.services.length === 0) return null;
  return (
    <section id="sf-book" className={styles.section} aria-labelledby="sf-book-title" tabIndex={-1}>
      <h2 id="sf-book-title" className={styles.h2}>
        {labels.bookTitle}
      </h2>
      {preview || !booker ? (
        <p className={styles.note}>{labels.previewBooker}</p>
      ) : (
        <>
          {provider.policyLabel ? (
            <p className={styles.note}>
              {labels.cancellation(provider.policyLabel)}
              {provider.policyText ? `. ${provider.policyText}` : ''}
            </p>
          ) : null}
          {booker({ provider, selectedId, onSelect })}
        </>
      )}
    </section>
  );
}

export function ScheduleSection({ provider, labels, locale }: { provider: StorefrontProviderModel; labels: StorefrontLabels; locale: string }) {
  const area = provider.area;
  const hasHours = provider.hours.some((d) => d.ranges.length > 0);
  const zone = useMemo(() => zoneName(provider.timeZone, locale), [provider.timeZone, locale]);
  if (!hasHours && !area) return null;
  const places = area ? [...area.servedCities, ...area.extraAreas] : [];
  return (
    <section id="sf-schedule" className={styles.section} aria-labelledby="sf-schedule-title">
      <div className={styles.scheduleCols}>
        {hasHours ? (
          <div>
            <h2 id="sf-schedule-title" className={cx(styles.h2, styles.h2Inline)}>
              <Clock size={22} aria-hidden /> {labels.scheduleTitle}
            </h2>
            <dl className={styles.hoursList}>
              {provider.hours.map((d) => (
                <div key={d.day} className={styles.hoursRow}>
                  <dt>{weekday(d.day, locale)}</dt>
                  <dd>{d.ranges.length === 0 ? labels.closed : d.ranges.map((r) => `${clock(r.start, locale)} – ${clock(r.end, locale)}`).join(', ')}</dd>
                </div>
              ))}
            </dl>
            <p className={styles.note}>{labels.timesIn(zone)}</p>
          </div>
        ) : null}
        {area ? (
          <div>
            <h2 id={hasHours ? undefined : 'sf-schedule-title'} className={cx(styles.h2, styles.h2Inline)}>
              <MapPin size={22} aria-hidden /> {labels.areaTitle}
            </h2>
            <p className={styles.prose}>{labels.basedIn(`${area.baseCity}, ${area.baseState}`)}</p>
            <p className={styles.prose}>{labels.travelsUpTo(area.radiusMiles.toLocaleString(locale))}</p>
            {places.length > 0 ? <p className={styles.prose}>{labels.alsoServes(places.join(', '))}</p> : null}
          </div>
        ) : null}
      </div>
    </section>
  );
}

export function TeamSection({ provider, labels }: { provider: StorefrontProviderModel; labels: StorefrontLabels }) {
  if (provider.team.length === 0) return null;
  return (
    <section className={styles.section} aria-labelledby="sf-team">
      <h2 id="sf-team" className={styles.h2}>
        {labels.teamTitle}
      </h2>
      <ul className={styles.teamList}>
        {provider.team.map((m) => (
          <li key={m.id} className={styles.teamMember}>
            {m.displayName}
          </li>
        ))}
      </ul>
    </section>
  );
}
