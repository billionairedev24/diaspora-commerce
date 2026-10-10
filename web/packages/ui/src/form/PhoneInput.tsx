'use client';

import { AsYouType, type CountryCode, getCountries, getCountryCallingCode } from 'libphonenumber-js/mobile';
import { type ChangeEvent, forwardRef, type ReactNode, useMemo, useRef } from 'react';

import { CaretDown } from '../icons';
import { Field, fieldStyles } from './Field';

export interface PhoneCountry {
  code: CountryCode;
  name: string;
  dialCode: string;
  flag: string;
}

const flagOf = (code: string) => code.toUpperCase().replace(/./g, (c) => String.fromCodePoint(127397 + c.charCodeAt(0)));

/** Every country with its flag and dial code; `first` (default US) leads, the rest A–Z. */
export function phoneCountries(locale = 'en', first: CountryCode = 'US'): PhoneCountry[] {
  let names: Intl.DisplayNames | null = null;
  try {
    names = new Intl.DisplayNames([locale], { type: 'region' });
  } catch {
    names = null;
  }
  return getCountries()
    .map((code) => ({ code, name: names?.of(code) ?? code, dialCode: `+${getCountryCallingCode(code)}`, flag: flagOf(code) }))
    .sort((a, b) => (a.code === first ? -1 : b.code === first ? 1 : a.name.localeCompare(b.name, locale)));
}

/** National number formatted for the country as the shopper types: "7132490142" → "(713) 249-0142". */
export function formatNational(value: string, country: CountryCode): string {
  const digits = value.replace(/\D/g, '');
  return digits ? new AsYouType(country).input(digits) : '';
}

export interface PhoneInputProps {
  label: ReactNode;
  country: CountryCode;
  onCountryChange: (country: CountryCode) => void;
  /** National number as displayed (formatted). */
  value: string;
  onChange: (value: string) => void;
  error?: string | null;
  help?: ReactNode;
  helpTone?: 'muted' | 'success';
  countryLabel?: string;
  placeholder?: string;
  locale?: string;
  disabled?: boolean;
  required?: boolean;
  name?: string;
  id?: string;
  onBlur?: () => void;
}

/** Mobile number with a flag + dial-code country picker and format-as-you-type (libphonenumber-js). */
export const PhoneInput = forwardRef<HTMLInputElement, PhoneInputProps>(function PhoneInput(
  { label, country, onCountryChange, value, onChange, error, help, helpTone, countryLabel = 'Country code', placeholder, locale = 'en', disabled, required, name, id, onBlur },
  ref
) {
  const countries = useMemo(() => phoneCountries(locale), [locale]);
  const current = countries.find((c) => c.code === country) ?? countries[0];
  const last = useRef(value);

  const handle = (e: ChangeEvent<HTMLInputElement>) => {
    const next = e.target.value;
    let digits = next.replace(/\D/g, '');
    // Deleting a formatting character (")" or "-") should delete the digit before it, not get re-added.
    if (next.length < last.current.length && digits === last.current.replace(/\D/g, '')) digits = digits.slice(0, -1);
    const formatted = formatNational(digits, country);
    last.current = formatted;
    onChange(formatted);
  };

  return (
    <Field label={label} error={error} help={help} helpTone={helpTone} id={id}>
      {(a11y) => (
        <span className={fieldStyles.phone}>
          <span className={fieldStyles.country} data-invalid={error ? 'true' : undefined}>
            <span className={fieldStyles.flag} aria-hidden>
              {current?.flag}
            </span>
            <span className={fieldStyles.dial} aria-hidden>
              {current?.dialCode}
            </span>
            <CaretDown size={14} aria-hidden />
            <select
              className={fieldStyles.countrySelect}
              aria-label={countryLabel}
              value={country}
              disabled={disabled}
              onChange={(e) => {
                const code = e.target.value as CountryCode;
                onCountryChange(code);
                const formatted = formatNational(value, code);
                last.current = formatted;
                onChange(formatted);
              }}
            >
              {countries.map((c) => (
                <option key={c.code} value={c.code}>
                  {`${c.flag} ${c.name} (${c.dialCode})`}
                </option>
              ))}
            </select>
          </span>
          <input
            ref={ref}
            type="tel"
            inputMode="tel"
            autoComplete="tel-national"
            className={fieldStyles.control}
            value={value}
            onChange={handle}
            onBlur={onBlur}
            placeholder={placeholder ?? (country === 'US' || country === 'CA' ? '(713) 249-0142' : 'Mobile number')}
            disabled={disabled}
            required={required}
            name={name}
            {...a11y}
          />
        </span>
      )}
    </Field>
  );
});
