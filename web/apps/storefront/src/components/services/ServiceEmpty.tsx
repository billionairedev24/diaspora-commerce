'use client';

import { isApiError, isPlainEmail, type ServiceRadius, type ServiceSearch } from '@afrotransact/api-client';
import { useFormatters } from '@afrotransact/i18n/react';
import { Button, buttonClassName, TextField } from '@afrotransact/ui';
import { useMutation } from '@tanstack/react-query';
import Link from 'next/link';
import { type FormEvent, useState } from 'react';

import { useT } from '@/i18n/t';
import { browserApi } from '@/lib/api/browser';
import { widerRadius } from '@/lib/services/radius';

import { categoryKey } from './format';
import styles from './Services.module.css';

interface Props {
  search: ServiceSearch;
  city: string;
  state: string;
  category: string;
  text: string;
  accountEmail?: string;
  onCategory: (category: string) => void;
  onClearText: () => void;
  onRadius: (radius: ServiceRadius) => void;
}

/**
 * Nothing serves the visitor's place for this search within the distance they chose. Says so plainly, with the miles
 * searched, offers "Search wider" (the next distance step), shows what IS available there (categories with their counts),
 * "Tell me when one joins" (works signed out) and "Offer your services" for providers.
 */
export function ServiceEmpty({ search, city, category, text, accountEmail, onCategory, onClearText, onRadius }: Props) {
  const t = useT();
  const fmt = useFormatters();
  const catKey = category ? categoryKey(category) : null;
  const catName = category ? (catKey ? t(catKey) : category).toLowerCase() : '';
  const searched = search.coverage !== 'ANY' && search.radiusMiles > 0 ? fmt.number(search.radiusMiles) : null;
  const title = text
    ? searched
      ? t('svc.empty.titleTextRadius', { q: text, city, miles: searched })
      : t('svc.empty.titleText', { q: text, city })
    : category
      ? searched
        ? t('svc.empty.titleCategoryRadius', { category: catName, city, miles: searched })
        : t('svc.empty.titleCategory', { category: catName, city })
      : searched
        ? t('svc.empty.titleAllRadius', { city, miles: searched })
        : t('svc.empty.titleAll', { city });
  const wider = widerRadius(search.radiusMiles);
  const elsewhere = search.categories.filter((c) => c.category !== category);

  return (
    <section className={styles.empty} aria-labelledby="svc-empty-title" data-testid="service-empty">
      <h2 id="svc-empty-title" className={styles.emptyTitle}>
        {title}
      </h2>
      <p className={styles.muted}>{searched ? t('svc.empty.bodyRadius', { miles: searched, city }) : t('svc.empty.body')}</p>
      {wider !== null && (
        <button type="button" className={styles.linkButton} onClick={() => onRadius(wider)} data-testid="service-search-wider">
          {t('svc.empty.wider', { distance: wider === 'any' ? t('svc.radius.any').toLowerCase() : t('svc.radius.miles', { n: wider }) })}
        </button>
      )}
      {text && (
        <button type="button" className={styles.linkButton} onClick={onClearText}>
          {t('svc.empty.clearSearch')}
        </button>
      )}

      {elsewhere.length > 0 && (
        <div className={styles.emptyBlock}>
          <h3 className={styles.h3}>{t('svc.empty.available', { city })}</h3>
          <div className={styles.chips}>
            {elsewhere.map((c) => {
              const key = categoryKey(c.category);
              return (
                <button key={c.category} type="button" className={styles.chip} onClick={() => onCategory(c.category)}>
                  {key ? t(key) : c.category} ({fmt.number(c.count)})
                </button>
              );
            })}
          </div>
        </div>
      )}

      <div className={styles.emptyActions}>
        <AlertForm city={city} category={category} categoryName={catName} accountEmail={accountEmail} />
        <div className={styles.offer}>
          <h3 className={styles.h3}>{t('svc.offer.title')}</h3>
          <p className={styles.muted}>{t('svc.offer.body')}</p>
          <Link href="/provide" className={buttonClassName({ variant: 'neutral', size: 'sm' })}>
            {t('svc.offer.cta')}
          </Link>
        </div>
      </div>
    </section>
  );
}

/** "Tell me when one joins": one email for the deliver-to place (and the chosen category), no account needed. */
function AlertForm({ city, category, categoryName, accountEmail }: { city: string; category: string; categoryName: string; accountEmail?: string }) {
  const t = useT();
  const [email, setEmail] = useState(accountEmail ?? '');
  const [error, setError] = useState<string | null>(null);
  const [sentTo, setSentTo] = useState<string | null>(null);
  const alert = useMutation({ mutationFn: (e: string) => browserApi().alertServices({ email: e, ...(category ? { category } : {}) }) });

  const submit = (e: FormEvent) => {
    e.preventDefault();
    const address = email.trim();
    if (!address) return setError(t('svc.alert.emailRequired'));
    if (!isPlainEmail(address)) return setError(t('svc.alert.emailInvalid'));
    setError(null);
    alert.mutate(address, {
      onSuccess: () => setSentTo(address.toLowerCase()),
      onError: (err) => {
        if (isApiError(err) && err.status === 422 && err.fieldErrors.email) setError(t('svc.alert.emailInvalid'));
        else if (isApiError(err) && err.status === 429) setError(t('svc.alert.tooMany'));
        else setError(t('svc.alert.failed'));
      }
    });
  };

  return (
    <div className={styles.alert}>
      <h3 className={styles.h3}>{t('svc.alert.title')}</h3>
      {sentTo ? (
        <p className={styles.success} role="status">
          {t('svc.alert.done', { email: sentTo, city })}
        </p>
      ) : (
        <form onSubmit={submit} noValidate className={styles.alertForm}>
          <p className={styles.muted}>{category ? t('svc.alert.bodyCategory', { category: categoryName, city }) : t('svc.alert.body', { city })}</p>
          <TextField label={t('svc.alert.email')} type="email" value={email} onChange={(e) => setEmail(e.target.value)} error={error} autoComplete="email" maxLength={254} />
          <Button type="submit" variant="primary" size="sm" loading={alert.isPending}>
            {t('svc.alert.submit')}
          </Button>
          <p className={styles.muted}>{t('svc.alert.privacy')}</p>
        </form>
      )}
    </div>
  );
}
