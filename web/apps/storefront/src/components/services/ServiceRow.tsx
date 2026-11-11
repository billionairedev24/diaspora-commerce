'use client';

import { type ServiceListing } from '@afrotransact/api-client';
import { useFormatters } from '@afrotransact/i18n/react';
import { buttonClassName, Stars } from '@afrotransact/ui';
import Link from 'next/link';
import { useState } from 'react';

import { useT } from '@/i18n/t';
import { type MessageKey } from '@/i18n/t';

import { categoryKey, formatNext, monogram } from './format';
import styles from './Services.module.css';

const SERVES_SHOWN = 3;

/**
 * One result: a flat row with a real photo (a neutral monogram tile when the provider has none, never a grey
 * placeholder), service and provider, rating only when there are reviews, where they are and how far, the cities they
 * serve, price and deposit, the next opening in the visitor's time zone, how it is delivered and a Book button.
 */
export function ServiceRow({ service: s, visitorState }: { service: ServiceListing; visitorState: string }) {
  const t = useT();
  const fmt = useFormatters();
  const [broken, setBroken] = useState(false);
  const href = `/services/${encodeURIComponent(s.serviceId)}`;
  const area = s.area;
  const serves = area?.serves ?? [];
  const shown = serves.slice(0, SERVES_SHOWN);
  const catKey = categoryKey(s.category);
  const where = area ? (area.baseState && visitorState && area.baseState !== visitorState ? `${area.baseCity}, ${area.baseState}` : area.baseCity) : s.location;

  return (
    <li className={styles.row} data-testid="service-listing">
      <Link href={href} className={styles.photoLink} aria-hidden tabIndex={-1}>
        {s.imageUrl && !broken ? (
          <img className={styles.photo} src={s.imageUrl} alt="" loading="lazy" onError={() => setBroken(true)} />
        ) : (
          <span className={styles.monogram} data-testid="service-monogram">
            {monogram(s.providerName)}
          </span>
        )}
      </Link>
      <div className={styles.rowInfo}>
        <span className={styles.kind}>{catKey ? t(catKey) : s.category}</span>
        <Link href={href} className={styles.name}>
          {s.name}
        </Link>
        <span className={styles.provider}>
          {s.providerName}
          <Stars rating={s.rating} reviewCount={s.ratingCount} showValue size={12} locale={fmt.tag} reviewsLabel={(c) => `(${c})`} />
        </span>
        <span className={styles.where}>
          {where}
          {area ? ` · ${area.distanceMiles < 1 ? t('svc.lessThanMile') : t('svc.miles', { n: fmt.number(Math.round(area.distanceMiles)) })}` : ''}
          {area?.zone ? ` · ${area.zone}` : ''}
        </span>
        {shown.length > 0 && (
          <span className={styles.serves}>
            {t('svc.serves', { cities: fmt.list(shown) })}
            {serves.length > shown.length ? ` +${serves.length - shown.length}` : ''}
          </span>
        )}
        <span className={styles.badges}>
          <span className={styles.badge}>{t(`svc.delivery.${s.delivery}` as MessageKey)}</span>
        </span>
      </div>
      <div className={styles.rowSide}>
        <span className={styles.priceFrom}>
          {t('svc.from')}{' '}
          <strong>{fmt.money(s.priceFromMinor, s.currency)}</strong>
        </span>
        {s.depositMinor > 0 && <span className={styles.muted}>{t('svc.deposit', { amount: fmt.money(s.depositMinor, s.currency) })}</span>}
        <span className={styles.next}>{s.nextAvailable ? t('svc.nextAt', { when: formatNext(fmt, s.nextAvailable) }) : t('svc.noOpenings')}</span>
        <Link href={href} className={buttonClassName({ variant: 'primary', size: 'sm' })}>
          {t('svc.book')}
        </Link>
      </div>
    </li>
  );
}
