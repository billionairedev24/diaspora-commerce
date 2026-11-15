'use client';

import { isApiError } from '@afrotransact/api-client';
import { useFormatters } from '@afrotransact/i18n/react';
import { ShieldCheck, Skeleton } from '@afrotransact/ui';
import { useQuery } from '@tanstack/react-query';
import Link from 'next/link';

import { useT } from '@/i18n/t';
import { browserApi } from '@/lib/api/browser';
import { shopperQueries } from '@/lib/api/queries';

import { BookingPanel } from './BookingPanel';
import styles from './Services.module.css';

/** Service detail (S150): what the service is, who provides it and the cancellation policy, with the booking panel beside it. */
export function ServiceDetailView({ serviceId }: { serviceId: string }) {
  const fmt = useFormatters();
  const t = useT();
  const svc = useQuery(shopperQueries.service(browserApi(), serviceId));
  const s = svc.data;

  if (svc.isPending) return <Skeleton height={360} />;
  if (svc.isError || !s) return <p className={styles.error}>{isApiError(svc.error) && svc.error.status === 404 ? t('svc.notFound') : t('acct.loadError')}</p>;

  return (
    <div className={styles.page}>
      <Link href="/services">← {t('svc.all')}</Link>
      <div className={styles.layout}>
        <section className={styles.card} aria-labelledby="svc-title">
          <span className={styles.status}>{s.category}</span>
          <h1 id="svc-title" className={styles.h1}>
            {s.name}
          </h1>
          <Link href={`/services/providers/${encodeURIComponent(s.orgId)}`}>{t('svc.by', { provider: s.providerName })}</Link>
          <p className={styles.muted}>{s.description}</p>
          <dl className={styles.dl}>
            <dt>{t('svc.duration')}</dt>
            <dd>{t('svc.minutes', { n: s.durationMinutes })}</dd>
            <dt>{t('svc.price')}</dt>
            <dd>{fmt.money(s.priceMinor)}</dd>
            <dt>{t('svc.depositNow')}</dt>
            <dd>{fmt.money(s.depositMinor)}</dd>
          </dl>
          <div className={styles.policy}>
            <ShieldCheck aria-hidden />
            <span>
              <strong>{t('svc.policy', { label: s.policy.label })}</strong> {s.policy.text}
            </span>
          </div>
        </section>

        <BookingPanel service={s} />
      </div>
    </div>
  );
}
