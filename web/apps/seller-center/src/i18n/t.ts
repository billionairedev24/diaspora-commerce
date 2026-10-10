import { createTranslator, type Locale, type Messages, type Translator, type Vars } from '@afrotransact/i18n';
import { useI18n } from '@afrotransact/i18n/react';
import { useMemo } from 'react';

import en from './en.json';

/*
 * Seller Center & Provider Hub messages (the shared @afrotransact/i18n pattern): flat dotted keys in en.json, one sibling JSON file per
 * locale (fr, es, ar, yo). Client components call `const t = useT()`; server components `const t = await getT()`
 * (./server). Catalogues other than English are sent to the browser only for the active locale.
 */

export type MessageKey = keyof typeof en;
export type { Translator, Vars };

export const EN_MESSAGES: Record<MessageKey, string> = en;

/** Translates with an explicit locale and catalogue (tests, non-React code). */
export function translate(locale: Locale, key: MessageKey, vars?: Vars, messages?: Messages): string {
  return createTranslator(EN_MESSAGES, locale, messages)(key, vars);
}

/** English translator, for tests and code that runs outside React (never for UI text). */
export const tEn: Translator<MessageKey> = createTranslator(EN_MESSAGES, 'en');

/** The active locale's translator (client components). */
export function useT(): Translator<MessageKey> {
  const { locale, messages } = useI18n();
  return useMemo(() => createTranslator(EN_MESSAGES, locale, messages), [locale, messages]);
}
