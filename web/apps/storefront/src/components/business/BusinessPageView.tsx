import { type BusinessPage } from '@afrotransact/api-client';
import Link from 'next/link';
import { type CSSProperties } from 'react';

import { getT } from '@/i18n/server';
import { textOnBrand } from '@/lib/brand';

import styles from './BusinessPage.module.css';

const money = (minor: number, currency: string) =>
  new Intl.NumberFormat('en-US', { style: 'currency', currency }).format(minor / 100);

/**
 * The plain public page of a business. Only its logo and one brand colour make it theirs: the colour fills the header
 * and the text on it is whichever of light or dark contrasts more. Prices and booking stay platform-styled.
 */
export async function BusinessPageView({ page }: { page: BusinessPage }) {
  const t = await getT();
  const brand = page.brandColor;
  const headerStyle = brand
    ? ({ '--biz-brand': brand, '--biz-on-brand': textOnBrand(brand) === 'light' ? 'var(--at-color-surface)' : 'var(--at-color-text)' } as CSSProperties)
    : undefined;
  return (
    <article className={styles.page}>
      <header className={brand ? styles.brandHeader : styles.plainHeader} style={headerStyle}>
        {page.logoUrl ? <img src={page.logoUrl} alt="" className={styles.logo} width={72} height={72} /> : null}
        <h1 className={styles.name}>{page.name}</h1>
      </header>
      {page.description ? (
        <section aria-labelledby="biz-about" className={styles.section}>
          <h2 id="biz-about" className={styles.h2}>{t('biz.about')}</h2>
          <p className={styles.text}>{page.description}</p>
        </section>
      ) : null}
      {page.services.length ? (
        <section aria-labelledby="biz-services" className={styles.section}>
          <h2 id="biz-services" className={styles.h2}>{t('biz.services')}</h2>
          <ul className={styles.list}>
            {page.services.map((s) => (
              <li key={s.serviceId} className={styles.row}>
                <Link href={`/services/${encodeURIComponent(s.serviceId)}`} className={styles.serviceLink}>{s.name}</Link>
                <span className={styles.meta}>{t('svc.minutes', { n: s.durationMinutes })}</span>
                <strong>{money(s.priceMinor, s.currency)}</strong>
              </li>
            ))}
          </ul>
        </section>
      ) : null}
      {page.products.length ? (
        <section aria-labelledby="biz-products" className={styles.section}>
          <h2 id="biz-products" className={styles.h2}>{t('biz.products')}</h2>
          <ul className={styles.list}>
            {page.products.map((p) => (
              <li key={p.atin} className={styles.row}>
                <Link href={`/p/${encodeURIComponent(p.atin)}`} className={styles.serviceLink}>{p.title}</Link>
                <strong>{money(p.priceMinor, p.currency)}</strong>
              </li>
            ))}
          </ul>
        </section>
      ) : null}
      <section aria-labelledby="biz-contact" className={styles.section}>
        <h2 id="biz-contact" className={styles.h2}>{t('biz.contact')}</h2>
        <p className={styles.text}>{t('biz.contactHelp')}</p>
        <p className={styles.links}>
          <Link href="/help">{t('biz.help')}</Link> · <Link href="/legal/conditions-of-use">{t('biz.terms')}</Link> · <Link href="/legal/returns">{t('biz.returns')}</Link>
        </p>
      </section>
    </article>
  );
}
