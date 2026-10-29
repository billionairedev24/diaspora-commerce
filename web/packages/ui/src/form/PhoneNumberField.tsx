'use client';

import { type CountryCode, getCountryCallingCode, parsePhoneNumberFromString } from 'libphonenumber-js/mobile';
import { forwardRef, useEffect, useRef, useState } from 'react';

import { formatNational, PhoneInput, type PhoneInputProps } from './PhoneInput';

/** Country and national digits of a stored number: "+2348031234567" → NG, "8031234567". Blank → the fallback country. */
export function splitE164(value: string | null | undefined, fallback: CountryCode = 'US'): { country: CountryCode; national: string } {
  const v = (value ?? '').trim();
  if (!v) return { country: fallback, national: '' };
  const intl = v.startsWith('+');
  const parsed = parsePhoneNumberFromString(intl ? v : v.replace(/\D/g, ''), intl ? undefined : fallback);
  if (parsed) return { country: parsed.country ?? fallback, national: String(parsed.nationalNumber) };
  return { country: fallback, national: v.replace(/\D/g, '') };
}

/**
 * E.164 form of national digits typed for a country ("0803 123 4567", NG → "+2348031234567"); '' when nothing is
 * typed. Incomplete numbers stay E.164-shaped (+1713) so forms validate them with one rule.
 */
export function toE164Number(national: string, country: CountryCode): string {
  const digits = national.replace(/\D/g, '');
  if (!digits) return '';
  const parsed = parsePhoneNumberFromString(digits, country);
  return parsed?.number ?? `+${getCountryCallingCode(country)}${digits.replace(/^0+/, '')}`;
}

export interface PhoneNumberFieldProps extends Omit<PhoneInputProps, 'country' | 'onCountryChange' | 'value' | 'onChange'> {
  /** The stored number in E.164 (+17132490142), or ''. Legacy national values are read with `defaultCountry`. */
  value: string;
  /** The number in E.164 as the person types ('' when empty). */
  onChange: (e164: string) => void;
  /** Country picked when the value is blank: the market's or the profile's (default US). */
  defaultCountry?: CountryCode;
}

/**
 * The standard phone field: flag + calling-code country picker, format-as-you-type, and an E.164 value in and out.
 * Every phone input of the web apps uses it (ESLint forbids raw tel inputs).
 */
export const PhoneNumberField = forwardRef<HTMLInputElement, PhoneNumberFieldProps>(function PhoneNumberField(
  { value, onChange, defaultCountry = 'US', ...rest },
  ref
) {
  const [country, setCountry] = useState<CountryCode>(() => splitE164(value, defaultCountry).country);
  const [display, setDisplay] = useState(() => {
    const s = splitE164(value, defaultCountry);
    return formatNational(s.national, s.country);
  });
  /** The last value this field emitted: a different incoming value (a reset, a prefill) is shown as-is. */
  const emitted = useRef(value);
  /** The picked country, current within one event (PhoneInput reformats right after a country change). */
  const countryRef = useRef(country);

  useEffect(() => {
    if (value === emitted.current) return;
    emitted.current = value;
    const s = splitE164(value, defaultCountry);
    countryRef.current = s.country;
    setCountry(s.country);
    setDisplay(formatNational(s.national, s.country));
  }, [value, defaultCountry]);

  const emit = (national: string, c: CountryCode) => {
    const e164 = toE164Number(national, c);
    emitted.current = e164;
    onChange(e164);
  };

  return (
    <PhoneInput
      ref={ref}
      {...rest}
      country={country}
      onCountryChange={(c) => {
        countryRef.current = c;
        setCountry(c);
        emit(display, c);
      }}
      value={display}
      onChange={(next) => {
        setDisplay(next);
        emit(next, countryRef.current);
      }}
    />
  );
});
