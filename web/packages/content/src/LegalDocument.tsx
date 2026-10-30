import { type ReactNode } from 'react';

import styles from './content.module.css';
import { type LegalBlockClasses, LegalBlocks, type ParsedLegalDocument, parseLegalMarkdown } from './markdown';

const BLOCK_CLASSES: LegalBlockClasses = {
  subheading: styles.subheading,
  quote: styles.quote,
  tableWrap: styles.docTableWrap,
  table: styles.docTable,
  cell: styles.docCell,
  rule: styles.rule,
  marker: styles.marker
};

/** "Draft for legal counsel review, not yet in force": full width above the page. */
export function LegalDraftBanner({ children }: { children: ReactNode }) {
  return (
    <p role="note" className={styles.draft}>
      {children}
    </p>
  );
}

/** A note above the document (translation, archived version). */
export function LegalNote({ children }: { children: ReactNode }) {
  return (
    <p role="note" className={styles.note}>
      {children}
    </p>
  );
}

export interface LegalDocumentContentProps {
  /** Page title (the document's name in the reader's language). */
  title: string;
  /** Markdown with `{tokens}` already filled. */
  body: string;
  /** "On this page" entries with their anchors; derived from the Markdown when absent. */
  outline?: ReadonlyArray<{ id: string; title: string }>;
  /** Already formatted for the reader: "Version 3.2", "Effective", "1 September 2026". */
  versionLabel: string;
  effectiveLabel: string;
  effectiveDate: string;
  effectiveText: string;
  tocLabel: string;
  /** Notes shown above the title (a translation note, an archived-version note). */
  notes?: ReactNode;
  /** First thing in the content (moves focus to the title after navigation on the storefront). */
  before?: ReactNode;
  /** After the effective date in the meta row (earlier versions, print). */
  meta?: ReactNode;
  /** The interactive part of the document (cookie preferences, privacy choices). */
  children?: ReactNode;
  /** Highlight `[[LEGAL: …]]` markers (the staff preview). */
  highlightMarkers?: boolean;
  className?: string;
  titleId?: string;
}

/**
 * A legal document as the storefront shows it (design/AfroTransact Legal.dc.html): notes, title, version and effective
 * date, "On this page" and the numbered sections. The body is the English text (binding) and marked lang="en"
 * whatever the page language; a translation passes its own `lang` through `bodyLang`.
 */
export function LegalDocumentContent(props: LegalDocumentContentProps & { bodyLang?: string; bodyDir?: 'ltr' | 'rtl' }) {
  const { title, body, outline, versionLabel, effectiveLabel, effectiveDate, effectiveText, tocLabel, notes, before, meta, children, highlightMarkers, className, titleId = 'legal-title' } = props;
  const doc: ParsedLegalDocument = parseLegalMarkdown(body, outline);
  const classes: LegalBlockClasses = { ...BLOCK_CLASSES, highlightMarkers };
  return (
    <div className={[styles.content, className].filter(Boolean).join(' ')}>
      {before}
      {notes}
      <div className={styles.head}>
        <h1 id={titleId} tabIndex={-1} className={styles.title}>
          {title}
        </h1>
        <div className={styles.meta}>
          <span>{versionLabel}</span>
          <span>
            {effectiveLabel} <time dateTime={effectiveDate}>{effectiveText}</time>
          </span>
          {meta}
        </div>
      </div>

      {doc.sections.length > 0 && (
        <nav aria-labelledby={`${titleId}-toc`} className={styles.toc} data-noprint>
          <h2 id={`${titleId}-toc`} className={styles.tocTitle}>
            {tocLabel}
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

      <article lang={props.bodyLang ?? 'en'} dir={props.bodyDir ?? 'ltr'} className={styles.article}>
        <LegalBlocks blocks={doc.intro} className={styles.body} classes={classes} />
        {doc.sections.map((s, i) => (
          <section key={s.id} aria-labelledby={s.id} className={styles.section}>
            <h2 id={s.id} tabIndex={-1} className={styles.sectionTitle}>
              {i + 1}. {s.title}
            </h2>
            <LegalBlocks blocks={s.blocks} className={styles.body} classes={classes} />
          </section>
        ))}
      </article>

      {children}
    </div>
  );
}

export interface HelpSectionContent {
  heading: string;
  body: string;
}

export interface HelpPageContentProps {
  title: string;
  sections: ReadonlyArray<HelpSectionContent>;
  /** The "Help center ›" breadcrumb (omitted on the hub). */
  crumb?: ReactNode;
  /** Follows the sections (the hub's article list). */
  children?: ReactNode;
  highlightMarkers?: boolean;
  titleId?: string;
}

/** A help page as in the Shop design's info view: the title, then each section's bold heading over muted text. */
export function HelpPageContent({ title, sections, crumb, children, highlightMarkers, titleId = 'help-title' }: HelpPageContentProps) {
  const classes: LegalBlockClasses = { marker: styles.marker, highlightMarkers };
  return (
    <article className={styles.helpPage} aria-labelledby={titleId}>
      {crumb}
      <h1 id={titleId} className={styles.helpTitle}>
        {title}
      </h1>
      {sections.map((s, i) => (
        <section key={`${i}-${s.heading}`} className={styles.helpSection}>
          <h2 className={styles.helpHeading}>{s.heading}</h2>
          <LegalBlocks blocks={parseLegalMarkdown(s.body).intro} className={styles.helpBody} classes={classes} />
        </section>
      ))}
      {children}
    </article>
  );
}
