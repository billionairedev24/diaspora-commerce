import { type CatalogFile, createTranslator, type Locale, type Messages, messagesOf, type Translator } from '@afrotransact/i18n';
import { useI18n } from '@afrotransact/i18n/react';
import { useMemo } from 'react';

import ar from './ar.json';
import en from './en.json';
import es from './es.json';
import fr from './fr.json';
import yo from './yo.json';

/* The account screens carry their own catalogue (shared by the three web apps) in the @afrotransact/i18n pattern:
   en.json is the source, the other locales are machine translations marked for review. */

export type AccountMessageKey = keyof typeof en;
export type AccountT = Translator<AccountMessageKey>;

export const ACCOUNT_CATALOGS: Record<Locale, CatalogFile> = { en, fr, es, ar, yo };

const cache = new Map<Locale, Messages>();

function messagesFor(locale: Locale): Messages | undefined {
  if (locale === 'en') return undefined;
  let m = cache.get(locale);
  if (!m) {
    m = messagesOf(ACCOUNT_CATALOGS[locale]);
    cache.set(locale, m);
  }
  return m;
}

/** Translator for a locale (tests and non-React code). */
export function accountTranslator(locale: Locale): AccountT {
  return createTranslator(en as Record<AccountMessageKey, string>, locale, messagesFor(locale));
}

/** The active locale's translator for the account screens. */
export function useAccountT(): AccountT {
  const { locale } = useI18n();
  return useMemo(() => accountTranslator(locale), [locale]);
}
