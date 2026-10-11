'use client';

import { createContext, type ReactNode, useContext, useMemo } from 'react';

import { createFormatters, DEFAULT_LOCALE, type Direction, dirOf, type FormatPreferences, type Formatters, isLocale, type Locale, localeCookie, LOCALES, type Messages } from './index';

export interface I18nValue {
  locale: Locale;
  dir: Direction;
  /** Messages of the active locale (undefined for English: the app bundles en.json as the fallback). */
  messages: Messages | undefined;
  fmt: Formatters;
}

const I18nContext = createContext<I18nValue>({ locale: DEFAULT_LOCALE, dir: 'ltr', messages: undefined, fmt: createFormatters(DEFAULT_LOCALE) });

/**
 * Provides the active locale, its messages and Intl formatters to client components. Web: the root layout reads the
 * locale cookie and passes the matching catalogue. Mobile: the root layout passes the device/in-app choice.
 */
export function I18nProvider({ locale, messages, children }: { locale: Locale; messages?: Messages; children: ReactNode }) {
  const value = useMemo<I18nValue>(() => ({ locale, dir: dirOf(locale), messages, fmt: createFormatters(locale) }), [locale, messages]);
  return <I18nContext.Provider value={value}>{children}</I18nContext.Provider>;
}

/**
 * Applies the signed-in person's display preferences (time zone, date order, number style) to every formatter below
 * it. Back offices wrap their shell with it once the preferences have loaded.
 */
export function FormatPreferencesProvider({ prefs, children }: { prefs: FormatPreferences | undefined; children: ReactNode }) {
  const parent = useContext(I18nContext);
  const tz = prefs?.timeZone;
  const date = prefs?.dateFormat;
  const num = prefs?.numberFormat;
  const value = useMemo<I18nValue>(
    () => ({ ...parent, fmt: createFormatters(parent.locale, { timeZone: tz, dateFormat: date, numberFormat: num }) }),
    [parent, tz, date, num]
  );
  return <I18nContext.Provider value={value}>{children}</I18nContext.Provider>;
}

export function useI18n(): I18nValue {
  return useContext(I18nContext);
}

export function useLocale(): Locale {
  return useContext(I18nContext).locale;
}

/** Intl formatters (currency, numbers, dates) for the active locale. */
export function useFormatters(): Formatters {
  return useContext(I18nContext).fmt;
}

/**
 * Language picker for the web apps: stores the choice in the at_locale cookie and calls onChanged (the app refreshes
 * its server components, e.g. router.refresh()). Each option is labelled in its own language and script direction.
 */
export function LocaleSelect({ label, className, onChanged }: { label: string; className?: string; onChanged: (locale: Locale) => void }) {
  const { locale } = useI18n();
  return (
    <select
      aria-label={label}
      className={className}
      value={locale}
      onChange={(e) => {
        const next = e.target.value;
        if (!isLocale(next)) return;
        document.cookie = localeCookie(next);
        onChanged(next);
      }}
    >
      {LOCALES.map((l) => (
        <option key={l.code} value={l.code} lang={l.code} dir={l.dir}>
          {l.name}
        </option>
      ))}
    </select>
  );
}
