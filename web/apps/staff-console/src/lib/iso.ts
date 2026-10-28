import { phoneCountries } from '@afrotransact/ui';

export interface IsoOption {
  value: string;
  label: string;
}

/** Every country (ISO 3166 alpha-2) by name, from the same library the phone fields use. */
export function countryOptions(locale = 'en'): IsoOption[] {
  return phoneCountries(locale)
    .map((c) => ({ value: c.code as string, label: c.name }))
    .sort((a, b) => a.label.localeCompare(b.label, locale));
}

/** Currencies the diaspora trades in come first, then every other ISO 4217 currency the browser knows, by code. */
const COMMON = ['USD', 'GHS', 'NGN', 'KES', 'ZAR', 'XOF', 'XAF', 'ETB', 'JMD', 'TTD', 'EUR', 'GBP', 'CAD'];

export function currencyOptions(locale = 'en'): IsoOption[] {
  let names: Intl.DisplayNames | null = null;
  try {
    names = new Intl.DisplayNames([locale], { type: 'currency' });
  } catch {
    names = null;
  }
  let all: string[] = [];
  try {
    all = Intl.supportedValuesOf('currency');
  } catch {
    all = [];
  }
  const codes = [...COMMON, ...all.filter((c) => !COMMON.includes(c)).sort()];
  return codes.map((code) => ({ value: code, label: `${code} · ${names?.of(code) ?? code}` }));
}
