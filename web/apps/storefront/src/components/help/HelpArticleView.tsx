import { HELP_HUB, type HelpArticle } from '@afrotransact/api-client';
import { HelpPageContent } from '@afrotransact/content';
import Link from 'next/link';
import { type ReactNode } from 'react';

import { type MessageKey, type Translator } from '@/i18n/t';

import styles from './Help.module.css';

/** A help page as in the Shop design's info view: the title, then each section's bold heading over muted text. Pages
    other than the hub carry a "Help center ›" breadcrumb. `children` follows the sections (the hub's article list). */
export function HelpArticleView({ article, t, children }: { article: HelpArticle; t: Translator<MessageKey>; children?: ReactNode }) {
  const hub = article.slug === HELP_HUB;
  return (
    <HelpPageContent
      title={article.title}
      sections={article.sections}
      crumb={
        hub ? undefined : (
          <nav className={styles.crumb} aria-label={t('help.crumbAria')}>
            <Link href="/help">{t('help.hubCrumb')}</Link>
            <span aria-hidden>›</span>
            <span aria-current="page">{article.title}</span>
          </nav>
        )
      }
    >
      {children}
    </HelpPageContent>
  );
}
