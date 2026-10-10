import {
  AsYouType,
  type CountryCode,
  getCountries,
  getCountryCallingCode,
  isValidPhoneNumber,
  parsePhoneNumberFromString
} from 'libphonenumber-js/mobile';

/* Phone helpers built on libphonenumber-js "mobile" metadata: a number is only valid if it can be a
   mobile number for the selected country (US/CA numbers are fixed-line-or-mobile and pass). */

export type { CountryCode };

export interface CountryOption {
  code: CountryCode;
  name: string;
  dialCode: string;
  flag: string;
}

/** Regional-indicator emoji flag for an ISO 3166-1 alpha-2 code. */
export function flagOf(code: string): string {
  return code.toUpperCase().replace(/./g, (c) => String.fromCodePoint(127397 + c.charCodeAt(0)));
}

export function isCountryCode(value: string): value is CountryCode {
  return (getCountries() as string[]).includes(value);
}

/** Every country libphonenumber knows, US first then A–Z by localized name. */
export function countryOptions(locale = 'en', first: CountryCode = 'US'): CountryOption[] {
  let names: Intl.DisplayNames | null = null;
  try {
    names = new Intl.DisplayNames([locale], { type: 'region' });
  } catch {
    names = null;
  }
  const all = getCountries().map((code) => ({
    code,
    name: names?.of(code) ?? code,
    dialCode: '+' + getCountryCallingCode(code),
    flag: flagOf(code)
  }));
  all.sort((a, b) => (a.code === first ? -1 : b.code === first ? 1 : a.name.localeCompare(b.name, locale)));
  return all;
}

/** Formats national digits as the shopper types, e.g. "7132490142" → "(713) 249-0142" for US. */
export function formatPhoneAsYouType(value: string, country: CountryCode): string {
  const digits = value.replace(/[^\d]/g, '');
  if (!digits) return '';
  return new AsYouType(country).input(digits);
}

export function isValidMobile(value: string, country: CountryCode): boolean {
  const digits = value.replace(/[^\d+]/g, '');
  if (!digits) return false;
  try {
    return isValidPhoneNumber(digits, country);
  } catch {
    return false;
  }
}

/** E.164 form for storage (e.g. +17132490142), or null when invalid. */
export function toE164(value: string, country: CountryCode): string | null {
  const parsed = parsePhoneNumberFromString(value.replace(/[^\d+]/g, ''), country);
  return parsed && parsed.isValid() ? parsed.number : null;
}
