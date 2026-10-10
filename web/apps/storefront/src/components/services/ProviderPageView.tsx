'use client';

import { formatMoney, isApiError, type ProviderPage } from '@afrotransact/api-client';
import { buttonClassName, CaretRight, SealCheck, ShieldCheck, Skeleton } from '@afrotransact/ui';
import { useQuery } from '@tanstack/react-query';
import Link from 'next/link';
import { type CSSProperties, type ReactNode } from 'react';

import { t } from '@/i18n/t';
import { browserApi } from '@/lib/api/browser';
import { shopperQueries } from '@/lib/api/queries';

import styles from './Services.module.css';

type Design = NonNullable<ProviderPage['design']>;

/** Only vetted theme tokens (custom properties) from the storefront service reach the page. */
const themeStyle = (d: Design | null): CSSProperties => {
  const out: Record<string, string> = {};
  Object.entries(d?.tokens ?? {}).forEach(([k, v]) => {
    if (/^--at-[a-z0-9-]+$/.test(k) && !/[;{}<>]/.test(v)) out[k] = v;
  });
  const brand = out['--at-brand-500'];
  if (brand) out['--provider-brand'] = brand;
  return out as CSSProperties;
};

function Gallery({ d }: { d: Design }) {
  if (!d.gallery.length) return null;
  return (
    <section className={styles.card} aria-labelledby="gallery-title">
      <h2 id="gallery-title" className={styles.h2}>
        {t('prov.gallery')}
      </h2>
      <div className={styles.gallery}>
        {d.gallery.map((g, i) => (
          <figure key={`${g.url}-${i}`} className={styles.figure}>
            {g.url ? <img src={g.url} alt={g.caption} loading="lazy" /> : <span className={styles.ph} aria-hidden />}
            {g.caption && <figcaption>{g.caption}</figcaption>}
          </figure>
        ))}
      </div>
    </section>
  );
}

function Featured({ d, page }: { d: Design; page: ProviderPage }) {
  if (!d.featured.length) return null;
  return (
    <section className={styles.card} aria-labelledby="featured-title">
      <h2 id="featured-title" className={styles.h2}>
        {t('prov.featured')}
      </h2>
      {d.featured.map((f) => {
        const svc = page.services.find((s) => s.id === f.serviceId);
        return (
          <article key={f.id} className={styles.field}>
            <strong>{f.headline}</strong>
            <p className={styles.muted}>{f.body}</p>
            {svc && (
              <Link href={`/services/${encodeURIComponent(svc.id)}`} className={buttonClassName({ size: 'sm' }, d.buttonShape === 'SQUARE' ? styles.square : undefined)}>
                {t('prov.bookThis', { price: formatMoney(svc.priceMinor) })}
              </Link>
            )}
          </article>
        );
      })}
    </section>
  );
}

/** A provider's page (S150): the provider's chosen layout and header, approved links only, gallery and featured work. */
export function ProviderPageView({ orgId }: { orgId: string }) {
  const q = useQuery(shopperQueries.provider(browserApi(), orgId));
  if (q.isPending) return <Skeleton height={360} />;
  if (q.isError || !q.data) return <p className={styles.error}>{isApiError(q.error) && q.error.status === 404 ? t('prov.notFound') : t('acct.loadError')}</p>;
  const page = q.data;
  const d = page.design;
  const square = d?.buttonShape === 'SQUARE' ? styles.square : undefined;
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
              {t('svc.minutes', { n: s.durationMinutes })} · {t('svc.deposit', { amount: formatMoney(s.depositMinor) })}
            </span>
          </span>
          <strong>{formatMoney(s.priceMinor)}</strong>
          <CaretRight aria-hidden />
        </Link>
      ))}
      <p className={styles.muted}>
        <ShieldCheck aria-hidden /> <strong>{t('svc.policy', { label: page.policy.label })}</strong> {page.policy.text}
      </p>
    </section>
  );
  const sections = !d ? [services] : d.layout === 'GALLERY_FIRST' ? [<Gallery key="g" d={d} />, services, <Featured key="f" d={d} page={page} />] : d.layout === 'STORY' ? [<Featured key="f" d={d} page={page} />, services, <Gallery key="g" d={d} />] : [services, <Gallery key="g" d={d} />, <Featured key="f" d={d} page={page} />];
  const first = page.services[0];
  return (
    <div className={styles.page} style={themeStyle(d)} data-layout={d?.layout ?? 'PLAIN'}>
      <Link href="/services">← {t('svc.all')}</Link>
      <header className={styles.hero}>
        {d?.header === 'COVER' ? (d.coverUrl ? <img src={d.coverUrl} alt="" className={styles.heroCover} /> : <div className={styles.heroCover} aria-hidden />) : <div className={styles.heroBand} aria-hidden />}
        <div className={styles.heroBody}>
          <div>
            <span className={styles.muted}>{page.location}</span>
            <h1 className={styles.heroName}>{page.displayName}</h1>
            {d?.tagline && <p>{d.tagline}</p>}
            <span>
              <SealCheck weight="fill" aria-hidden /> {t('prov.verified')}
            </span>
          </div>
          <div className={styles.socials}>
            {d?.social.map((l) => (
              <a key={l.network} href={l.url} target="_blank" rel="noopener noreferrer nofollow" className={styles.social}>
                {l.network}
              </a>
            ))}
            {first && (
              <Link href={`/services/${encodeURIComponent(first.id)}`} className={buttonClassName({}, square)}>
                {t('prov.book')}
              </Link>
            )}
          </div>
        </div>
      </header>
      {sections}
    </div>
  );
}
