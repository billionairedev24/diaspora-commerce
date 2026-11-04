'use client';

import { isApiError } from '@afrotransact/api-client';
import { useFormatters } from '@afrotransact/i18n/react';
import { buttonClassName, CaretRight, SealCheck, ShieldCheck, Skeleton } from '@afrotransact/ui';
import { useQuery } from '@tanstack/react-query';
import Link from 'next/link';
import { type ReactNode } from 'react';

import { useT } from '@/i18n/t';
import { browserApi } from '@/lib/api/browser';
import { shopperQueries } from '@/lib/api/queries';

import styles from './Services.module.css';

/** A provider's page (S150): its services and booking policy, in the platform's own layout. */
export function ProviderPageView({ orgId }: { orgId: string }) {
  const fmt = useFormatters();
  const t = useT();
  const q = useQuery(shopperQueries.provider(browserApi(), orgId));
  if (q.isPending) return <Skeleton height={360} />;
  if (q.isError || !q.data) return <p className={styles.error}>{isApiError(q.error) && q.error.status === 404 ? t('prov.notFound') : t('acct.loadError')}</p>;
  const page = q.data;
  const services: ReactNode = (
    <section className={styles.card} aria-labelledby="services-title">
      <h2 id="services-title" className={styles.h2}>
        {t('prov.services')}
      </h2>
      {page.services.map((s) => (
        <Link key={s.id} href={`/services/${encodeURIComponent(s.id)}`} className={styles.serviceRow}>
          <span>
            <strong>{s.name}</strong>
            <br />
            <span className={styles.muted}>
              {t('svc.minutes', { n: s.durationMinutes })} · {t('svc.deposit', { amount: fmt.money(s.depositMinor) })}
            </span>
          </span>
          <strong>{fmt.money(s.priceMinor)}</strong>
          <CaretRight aria-hidden />
        </Link>
      ))}
      <p className={styles.muted}>
        <ShieldCheck aria-hidden /> <strong>{t('svc.policy', { label: page.policy.label })}</strong> {page.policy.text}
      </p>
    </section>
  );
  const first = page.services[0];
  return (
    <div className={styles.page}>
      <Link href="/services">← {t('svc.all')}</Link>
      <header className={styles.hero}>
        <div className={styles.heroBand} aria-hidden />
        <div className={styles.heroBody}>
          <div>
            <span className={styles.muted}>{page.location}</span>
            <h1 className={styles.heroName}>{page.displayName}</h1>
            <span>
              <SealCheck weight="fill" aria-hidden /> {t('prov.verified')}
            </span>
          </div>
          <div className={styles.socials}>
            {first && (
              <Link href={`/services/${encodeURIComponent(first.id)}`} className={buttonClassName({})}>
                {t('prov.book')}
              </Link>
            )}
          </div>
        </div>
      </header>
      {services}
    </div>
  );
}
