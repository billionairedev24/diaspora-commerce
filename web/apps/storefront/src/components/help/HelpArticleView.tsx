import { HELP_HUB, type HelpArticle, type HelpSection } from '@afrotransact/api-client';
import Link from 'next/link';
import { type ReactNode } from 'react';

import { LegalBlocks, parseLegalMarkdown } from '@/components/legal/markdown';
import { type MessageKey, type Translator } from '@/i18n/t';

import styles from './Help.module.css';

function SectionView({ section }: { section: HelpSection }) {
  return (
    <section className={styles.section}>
      <h2 className={styles.heading}>{section.heading}</h2>
      <LegalBlocks blocks={parseLegalMarkdown(section.body).intro} className={styles.body} />
    </section>
  );
}

/** A help page as in the Shop design's info view: the title, then each section's bold heading over muted text. Pages
    other than the hub carry a "Help center ›" breadcrumb. `children` follows the sections (the hub's article list). */
export function HelpArticleView({ article, t, children }: { article: HelpArticle; t: Translator<MessageKey>; children?: ReactNode }) {
  const hub = article.slug === HELP_HUB;
  return (
    <article className={styles.page} aria-labelledby="help-title">
      {!hub && (
        <nav className={styles.crumb} aria-label={t('help.crumbAria')}>
          <Link href="/help">{t('help.hubCrumb')}</Link>
          <span aria-hidden>›</span>
          <span aria-current="page">{article.title}</span>
        </nav>
      )}
      <h1 id="help-title" className={styles.title}>
        {article.title}
      </h1>
      {article.sections.map((s, i) => (
        <SectionView key={`${i}-${s.heading}`} section={s} />
      ))}
      {children}
    </article>
  );
}
