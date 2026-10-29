import { LEGAL_GROUPS, type LegalDocumentKey, legalHref, type LegalSummary } from '@afrotransact/api-client';
import { type Translator } from '@afrotransact/i18n';
import Link from 'next/link';

import { type MessageKey } from '@/i18n/t';

import styles from './Legal.module.css';
import { formatLegalDate, legalTitle } from './LegalDocumentView';

export interface LegalCenterProps {
  /** The current version of every published document; empty when the index could not be loaded (the links still work). */
  documents: ReadonlyArray<LegalSummary>;
  locale: string;
  t: Translator<MessageKey>;
}

/**
 * Legal & privacy center index (/legal): every document, grouped Customers, Payments and money, Sellers and providers,
 * Privacy and cookies, Policies. The groups and titles are fixed in the app, so the page lists and links every document
 * even while the version details are unavailable.
 */
export function LegalCenter({ documents, locale, t }: LegalCenterProps) {
  const byKey = new Map<LegalDocumentKey, LegalSummary>(documents.map((d) => [d.key, d]));
  return (
    <div className={styles.center}>
      <div className={styles.head}>
        <h1 id="legal-title" className={styles.title}>
          {t('legal.center')}
        </h1>
        <p className={styles.lede}>{t('legal.centerIntro')}</p>
      </div>
      {LEGAL_GROUPS.map((g) => (
        <section key={g.id} aria-labelledby={`legal-group-${g.id}`} className={styles.centerGroup}>
          <h2 id={`legal-group-${g.id}`} className={styles.sectionTitle}>
            {t(`legal.group.${g.id}` as MessageKey)}
          </h2>
          <ul className={styles.centerList}>
            {g.documents.map((k) => {
              const d = byKey.get(k);
              return (
                <li key={k} className={styles.centerItem}>
                  <Link href={legalHref(k)} className={styles.centerDoc}>
                    {legalTitle(t, k)}
                  </Link>
                  {d && (
                    <span className={styles.centerMeta}>
                      {t('legal.version', { version: d.version })} · {t('legal.effective')}{' '}
                      <time dateTime={d.effectiveDate}>{formatLegalDate(d.effectiveDate, locale)}</time>
                    </span>
                  )}
                </li>
              );
            })}
          </ul>
        </section>
      ))}
    </div>
  );
}
