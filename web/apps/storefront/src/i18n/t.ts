import en from './en.json';

/* Tiny i18n helper. Messages live in en.json (one flat key space); other locales are added as
   sibling JSON files and selected per request once translations exist. */

export type MessageKey = keyof typeof en;
export type Vars = Record<string, string | number>;

const catalogs: Record<string, Partial<Record<MessageKey, string>>> = { en };

export function translate(locale: string, key: MessageKey, vars?: Vars): string {
  const template = catalogs[locale]?.[key] ?? en[key];
  if (!vars) return template;
  return template.replace(/\{(\w+)\}/g, (m, k: string) => (k in vars ? String(vars[k]) : m));
}

/** English translator (the only shipped catalogue today). */
export function t(key: MessageKey, vars?: Vars): string {
  return translate('en', key, vars);
}
