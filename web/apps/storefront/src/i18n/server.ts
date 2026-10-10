import 'server-only';

import { createTranslator, type Locale, LOCALE_COOKIE, negotiateLocale, type Translator } from '@afrotransact/i18n';
import { cookies, headers } from 'next/headers';
import { cache } from 'react';

import { messagesFor } from './catalogs';
import { EN_MESSAGES, type MessageKey } from './t';

/** The request's locale: the at_locale cookie, else Accept-Language, else English. */
export const getLocale = cache(async (): Promise<Locale> => {
  const [c, h] = await Promise.all([cookies(), headers()]);
  return negotiateLocale(c.get(LOCALE_COOKIE)?.value, h.get('accept-language'));
});

/** The request's translator (server components, metadata). */
export async function getT(): Promise<Translator<MessageKey>> {
  const locale = await getLocale();
  return createTranslator(EN_MESSAGES, locale, messagesFor(locale));
}
