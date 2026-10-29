import { LEGAL_GROUPS, type LegalDocumentKey, legalHref, type LegalPage } from '@afrotransact/api-client';
import { type Translator } from '@afrotransact/i18n';
import Link from 'next/link';
import { type ReactNode } from 'react';

import { type MessageKey } from '@/i18n/t';

import styles from './Legal.module.css';
import { FocusHeadingOnNavigate, PrintButton } from './LegalClient';
import { type LegalBlockClasses, LegalBlocks, parseLegalMarkdown } from './markdown';

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

const BLOCK_CLASSES: LegalBlockClasses = {
  subheading: styles.subheading,
  quote: styles.quote,
  tableWrap: styles.docTableWrap,
  table: styles.docTable,
  cell: styles.docCell,
  rule: styles.rule
};

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
  const doc = parseLegalMarkdown(page.body, page.outline);
  const archived = page.status === 'ARCHIVED';
  const older = page.versions.filter((v) => v.version !== page.version);
  const showTranslationNote = locale !== 'en';
  return (
    <>
      {page.draftBanner && (
        <p role="note" className={styles.draft}>
          {t('legal.draft')}
        </p>
      )}
      <div className={styles.layout}>
        <nav aria-label={t('legal.docs')} className={styles.docsNav} data-noprint>
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

        <div className={styles.main}>
          <FocusHeadingOnNavigate targetId="legal-title" />
          {showTranslationNote && (
            <p role="note" className={styles.note}>
              {t('legal.translation')}
            </p>
          )}
          {archived && (
            <p role="note" className={styles.note}>
              {t('legal.archivedNote', { version: page.version })} <Link href={legalHref(docKey)}>{t('legal.viewCurrent')}</Link>
            </p>
          )}
          <div className={styles.head}>
            <h1 id="legal-title" tabIndex={-1} className={styles.title}>
              {legalTitle(t, docKey)}
            </h1>
            <div className={styles.meta}>
              <span>{t('legal.version', { version: page.version })}</span>
              <span>
                {t('legal.effective')} <time dateTime={page.effectiveDate}>{formatLegalDate(page.effectiveDate, locale)}</time>
              </span>
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
            </div>
          </div>

          {doc.sections.length > 0 && (
            <nav aria-labelledby="legal-toc" className={styles.toc} data-noprint>
              <h2 id="legal-toc" className={styles.tocTitle}>
                {t('legal.onPage')}
              </h2>
              <ol className={styles.tocList}>
                {doc.sections.map((s) => (
                  <li key={s.id}>
                    <a href={`#${s.id}`}>{s.title}</a>
                  </li>
                ))}
              </ol>
            </nav>
          )}

          <article lang="en" dir="ltr" className={styles.article}>
            <LegalBlocks blocks={doc.intro} className={styles.body} classes={BLOCK_CLASSES} />
            {doc.sections.map((s, i) => (
              <section key={s.id} aria-labelledby={s.id} className={styles.section}>
                <h2 id={s.id} tabIndex={-1} className={styles.sectionTitle}>
                  {i + 1}. {s.title}
                </h2>
                <LegalBlocks blocks={s.blocks} className={styles.body} classes={BLOCK_CLASSES} />
              </section>
            ))}
          </article>

          {!archived && children}
        </div>
      </div>
    </>
  );
}
