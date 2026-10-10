import { type CatalogFile, type Locale, type Messages, messagesOf } from '@afrotransact/i18n';

import ar from './ar.json';
import en from './en.json';
import es from './es.json';
import fr from './fr.json';
import yo from './yo.json';

/** Customer-app catalogues (the shared @afrotransact/i18n pattern: flat keys, {var} placeholders, $meta review flags). */
export const CATALOG_FILES: Record<Locale, CatalogFile> = { en, fr, es, ar, yo };

export type MessageKey = keyof typeof en;
export const EN_MESSAGES: Record<MessageKey, string> = en;

export function messagesFor(locale: Locale): Messages | undefined {
  return locale === 'en' ? undefined : messagesOf(CATALOG_FILES[locale]);
}
