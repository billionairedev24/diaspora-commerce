/*
 * Customer-app i18n (S193): the device language picks the locale (en, fr, es, ar, yo) unless the shopper chose one in
 * Account → Language; the choice is kept on the device. Arabic switches the layout to right-to-left (React Native
 * applies a direction change after the app restarts). Screens call `const tr = useT()` (`t` is the theme here).
 */
import { createTranslator, dirOf, type Locale, negotiateLocale, type Translator } from '@afrotransact/i18n';
import { I18nProvider, useI18n } from '@afrotransact/i18n/react';
import { createContext, type ReactNode, useCallback, useContext, useEffect, useMemo, useState } from 'react';
import { I18nManager, Platform } from 'react-native';

import { secureStorage } from '@/auth/secureStorage';

import { EN_MESSAGES, type MessageKey, messagesFor } from './catalogs';

export type { MessageKey, Translator };

const STORAGE_KEY = 'at_locale';

/** The device's preferred supported language (Intl reports the OS locale on Hermes and the web). */
export function deviceLocale(): Locale {
  try {
    return negotiateLocale(undefined, [Intl.DateTimeFormat().resolvedOptions().locale]);
  } catch {
    return 'en';
  }
}

function applyDirection(locale: Locale): void {
  const rtl = dirOf(locale) === 'rtl';
  if (Platform.OS === 'web') {
    if (typeof document !== 'undefined') {
      document.documentElement.lang = locale;
      document.documentElement.dir = rtl ? 'rtl' : 'ltr';
    }
    return;
  }
  I18nManager.allowRTL(true);
  if (I18nManager.isRTL !== rtl) I18nManager.forceRTL(rtl);
}

const SetLocale = createContext<(l: Locale) => void>(() => undefined);

export function AppI18nProvider({ children }: { children: ReactNode }) {
  const [locale, setLocale] = useState<Locale>(deviceLocale);
  useEffect(() => {
    void secureStorage
      .get(STORAGE_KEY)
      .then((saved) => {
        if (saved) setLocale(negotiateLocale(saved, null));
      })
      .catch(() => undefined);
  }, []);
  useEffect(() => applyDirection(locale), [locale]);
  const choose = useCallback((l: Locale) => {
    setLocale(l);
    void secureStorage.set(STORAGE_KEY, l).catch(() => undefined);
  }, []);
  const messages = useMemo(() => messagesFor(locale), [locale]);
  return (
    <SetLocale.Provider value={choose}>
      <I18nProvider locale={locale} messages={messages}>
        {children}
      </I18nProvider>
    </SetLocale.Provider>
  );
}

export function useT(): Translator<MessageKey> {
  const { locale, messages } = useI18n();
  return useMemo(() => createTranslator(EN_MESSAGES, locale, messages), [locale, messages]);
}

/** Changes the app language (Account → Language). */
export function useSetLocale(): (l: Locale) => void {
  return useContext(SetLocale);
}

export { useFormatters, useLocale } from '@afrotransact/i18n/react';
