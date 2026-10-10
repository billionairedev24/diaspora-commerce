import { type CatalogFile, type Locale, type Messages, messagesOf } from '@afrotransact/i18n';

import ar from './ar.json';
import en from './en.json';
import es from './es.json';
import fr from './fr.json';
import yo from './yo.json';

/** Every catalogue file (server side and tests only: the browser receives just the active one). */
export const CATALOG_FILES: Record<Locale, CatalogFile> = { en, fr, es, ar, yo };

const cache = new Map<Locale, Messages>();

/** Messages for a locale; undefined for English (the client bundles en.json as the fallback). */
export function messagesFor(locale: Locale): Messages | undefined {
  if (locale === 'en') return undefined;
  let m = cache.get(locale);
  if (!m) {
    m = messagesOf(CATALOG_FILES[locale]);
    cache.set(locale, m);
  }
  return m;
}
