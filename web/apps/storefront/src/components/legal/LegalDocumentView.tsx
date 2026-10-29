import { LEGAL_GROUPS, type LegalDocumentKey, legalHref, type LegalPage } from '@afrotransact/api-client';
import { LegalDocumentContent, LegalDraftBanner, LegalNote } from '@afrotransact/content';
import { type Translator } from '@afrotransact/i18n';
import { LogoMark, wordmarkClassName } from '@afrotransact/ui';
import Link from 'next/link';
import { type ReactNode } from 'react';

import { type MessageKey } from '@/i18n/t';

import styles from './Legal.module.css';
import { FocusHeadingOnNavigate, PrintButton } from './LegalClient';

/** Localised document title (the binding English title comes from content-service). */
export const legalTitle = (t: Translator<MessageKey>, key: LegalDocumentKey): string => t(`legal.doc.${key}` as MessageKey);

/** "1 September 2026" in the reader's language (dates are calendar dates: noon UTC avoids a day shift). */
export function formatLegalDate(iso: string, locale: string): string {
  try {
    return new Intl.DateTimeFormat(locale, { dateStyle: 'long', timeZone: 'UTC' }).format(new Date(`${iso}T12:00:00Z`));
  } catch {
    return iso;
  }
}

export interface LegalDocumentViewProps {
  docKey: LegalDocumentKey;
  page: LegalPage;
  locale: string;
  t: Translator<MessageKey>;
  /** Interactive part of the document (cookie preferences, privacy choices). */
  children?: ReactNode;
}

/**
 * Legal & privacy center page (design/AfroTransact Legal.dc.html): documents nav, counsel-review banner, translation
 * note, title, version and effective date, previous versions, print, "On this page" and numbered sections. The body
 * is English (binding) and marked lang="en" whatever the page language.
 */
export function LegalDocumentView({ docKey, page, locale, t, children }: LegalDocumentViewProps) {
  const archived = page.status === 'ARCHIVED';
  const older = page.versions.filter((v) => v.version !== page.version);
  const showTranslationNote = locale !== 'en';
  return (
    <>
      {page.draftBanner && <LegalDraftBanner>{t('legal.draft')}</LegalDraftBanner>}
      <div className={styles.layout}>
        <nav aria-label={t('legal.docs')} className={styles.docsNav} data-noprint>
          <div className={styles.brandBlock}>
            <Link href="/" className={styles.brandLink} aria-label={t('header.home')}>
              <LogoMark size={28} />
              <span className={wordmarkClassName}>afrotransact</span>
            </Link>
            <Link href="/" className={styles.backLink}>
              ← {t('common.backToShop')}
            </Link>
          </div>
          <Link href="/legal" className={styles.centerLink}>
            {t('legal.allDocs')}
          </Link>
          {LEGAL_GROUPS.map((g) => (
            <section key={g.id} aria-labelledby={`legal-group-${g.id}`} className={styles.docsGroup}>
              <h2 id={`legal-group-${g.id}`} className={styles.eyebrow}>
                {t(`legal.group.${g.id}` as MessageKey)}
              </h2>
              <ul className={styles.docsList}>
                {g.documents.map((k) => (
                  <li key={k}>
                    <Link href={legalHref(k)} className={styles.docLink} aria-current={k === docKey ? 'page' : undefined}>
                      {legalTitle(t, k)}
                    </Link>
                  </li>
                ))}
              </ul>
            </section>
          ))}
        </nav>

        <LegalDocumentContent
          className={styles.main}
          title={legalTitle(t, docKey)}
          body={page.body}
          outline={page.outline}
          versionLabel={t('legal.version', { version: page.version })}
          effectiveLabel={t('legal.effective')}
          effectiveDate={page.effectiveDate}
          effectiveText={formatLegalDate(page.effectiveDate, locale)}
          tocLabel={t('legal.onPage')}
          before={<FocusHeadingOnNavigate targetId="legal-title" />}
          notes={
            <>
              {showTranslationNote && <LegalNote>{t('legal.translation')}</LegalNote>}
              {archived && (
                <LegalNote>
                  {t('legal.archivedNote', { version: page.version })} <Link href={legalHref(docKey)}>{t('legal.viewCurrent')}</Link>
                </LegalNote>
              )}
            </>
          }
          meta={
            <>
              {older.length > 0 && (
                <details className={styles.prev} data-noprint>
                  <summary>{t('legal.prev')}</summary>
                  <ul>
                    {older.map((v) => (
                      <li key={v.version}>
                        <Link href={v.status === 'CURRENT' ? legalHref(docKey) : `${legalHref(docKey)}?version=${encodeURIComponent(v.version)}`}>
                          {t('legal.version', { version: v.version })}
                        </Link>{' '}
                        · <time dateTime={v.effectiveDate}>{formatLegalDate(v.effectiveDate, locale)}</time>
                      </li>
                    ))}
                  </ul>
                </details>
              )}
              <PrintButton label={t('legal.print')} />
            </>
          }
        >
          {!archived && children}
        </LegalDocumentContent>
      </div>
    </>
  );
}
