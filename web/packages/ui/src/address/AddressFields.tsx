'use client';

import { type ReactNode } from 'react';

import { Select, type SelectOption, TextField } from '../form/TextField';
import { AddressAutocomplete } from './AddressAutocomplete';
import styles from './AddressFields.module.css';
import { type AddressSuggestionItem, type SearchAddresses } from './types';

/** The structured address every form stores (the APIs' shape; `zip` is the postal code). */
export interface AddressValue {
  line1: string;
  line2: string;
  city: string;
  state: string;
  zip: string;
  /** ISO 3166-1 alpha-2; filled from the picked suggestion when the form shows no country picker. */
  country?: string;
  lat?: number | null;
  lon?: number | null;
}

export type AddressFieldKey = 'line1' | 'line2' | 'city' | 'state' | 'zip' | 'country';

export interface AddressFieldLabels {
  line1: ReactNode;
  line2: ReactNode;
  city: ReactNode;
  state: ReactNode;
  zip: ReactNode;
  country: ReactNode;
  line1Placeholder: string;
  statePlaceholder: string;
  countryPlaceholder: string;
  attribution?: ReactNode;
  emptyLabel?: string;
  errorLabel?: string;
  loadingLabel?: string;
  resolvingLabel?: string;
}

export const DEFAULT_ADDRESS_LABELS: AddressFieldLabels = {
  line1: 'Street address',
  line2: 'Apt, suite, unit (optional)',
  city: 'City',
  state: 'State',
  zip: 'ZIP code',
  country: 'Country',
  line1Placeholder: 'Start typing your street address',
  statePlaceholder: 'Choose…',
  countryPlaceholder: 'Choose…'
};

export const EMPTY_ADDRESS: AddressValue = { line1: '', line2: '', city: '', state: '', zip: '', country: 'US' };

export interface AddressFieldsProps {
  value: AddressValue;
  onChange: (next: AddressValue) => void;
  /** Street suggestions (geo: Google Places with a key, else OSM): `addressLookup(api)` from the api-client. */
  searchAddresses: SearchAddresses;
  /** Called after a suggestion filled the fields (e.g. to clear a "can't verify" warning). */
  onPick?: (suggestion: AddressSuggestionItem) => void;
  /** Called when the person edits the street by hand. */
  onStreetEdit?: () => void;
  errors?: Partial<Record<AddressFieldKey, string | null | undefined>>;
  /** Element ids, so an error summary can link to each field; default `${idPrefix}-<field>`. */
  idPrefix?: string;
  labels?: Partial<AddressFieldLabels>;
  /** State / province options (e.g. US states): a select; without, a text field. */
  regions?: SelectOption[];
  /** Country options: a country select first; without, the country follows the picked suggestion. */
  countries?: SelectOption[];
  /** Hide the second address line (pickup points, home-only addresses). */
  hideLine2?: boolean;
  disabled?: boolean;
  /** Name prefix of the inputs (form posts, autofill). */
  name?: string;
}

/**
 * The standard address form: street autocomplete (session-billed Google Places through the BFF), apt/suite, city,
 * state and ZIP filled from the picked suggestion and editable. Every address form of the web apps uses it.
 */
export function AddressFields({
  value,
  onChange,
  searchAddresses,
  onPick,
  onStreetEdit,
  errors = {},
  idPrefix = 'addr',
  labels: labelsProp,
  regions,
  countries,
  hideLine2,
  disabled,
  name = 'address'
}: AddressFieldsProps) {
  const l = { ...DEFAULT_ADDRESS_LABELS, ...labelsProp };
  const id = (k: AddressFieldKey) => `${idPrefix}-${k}`;
  const set = (k: AddressFieldKey) => (v: string) => onChange({ ...value, [k]: v });

  const pick = (s: AddressSuggestionItem) => {
    const a = s.address;
    const known = !regions || regions.some((r) => r.value === a.state);
    onChange({
      ...value,
      line1: a.line1 || s.label,
      line2: a.line2 || value.line2,
      city: a.city || value.city,
      state: a.state && known ? a.state : value.state,
      zip: a.zip || value.zip,
      country: countries ? value.country : a.country || value.country,
      lat: a.lat,
      lon: a.lon
    });
    onPick?.(s);
  };

  return (
    <div className={styles.fields} data-testid="address-fields">
      {countries && (
        <Select
          id={id('country')}
          name={`${name}.country`}
          label={l.country}
          options={countries}
          placeholder={l.countryPlaceholder}
          value={value.country ?? ''}
          onChange={(e) => set('country')(e.target.value)}
          error={errors.country}
          disabled={disabled || countries.length < 2}
          autoComplete="country"
        />
      )}
      {disabled ? (
        <TextField id={id('line1')} label={l.line1} value={value.line1} disabled />
      ) : (
        <AddressAutocomplete
          id={id('line1')}
          name={`${name}.line1`}
          label={l.line1}
          value={value.line1}
          onChange={(v) => {
            set('line1')(v);
            onStreetEdit?.();
          }}
          onSelect={pick}
          searchAddresses={searchAddresses}
          placeholder={l.line1Placeholder}
          error={errors.line1}
          attribution={l.attribution}
          emptyLabel={l.emptyLabel}
          errorLabel={l.errorLabel}
          loadingLabel={l.loadingLabel}
          resolvingLabel={l.resolvingLabel}
        />
      )}
      {!hideLine2 && (
        <TextField
          id={id('line2')}
          name={`${name}.line2`}
          label={l.line2}
          value={value.line2}
          onChange={(e) => set('line2')(e.target.value)}
          error={errors.line2}
          autoComplete="address-line2"
          disabled={disabled}
        />
      )}
      <div className={styles.row}>
        <TextField
          id={id('city')}
          name={`${name}.city`}
          label={l.city}
          value={value.city}
          onChange={(e) => set('city')(e.target.value)}
          error={errors.city}
          autoComplete="address-level2"
          disabled={disabled}
        />
        {regions ? (
          <Select
            id={id('state')}
            name={`${name}.state`}
            label={l.state}
            options={regions}
            placeholder={l.statePlaceholder}
            value={value.state}
            onChange={(e) => set('state')(e.target.value)}
            error={errors.state}
            autoComplete="address-level1"
            disabled={disabled}
          />
        ) : (
          <TextField
            id={id('state')}
            name={`${name}.state`}
            label={l.state}
            value={value.state}
            onChange={(e) => set('state')(e.target.value)}
            error={errors.state}
            autoComplete="address-level1"
            disabled={disabled}
          />
        )}
        <TextField
          id={id('zip')}
          name={`${name}.zip`}
          label={l.zip}
          value={value.zip}
          onChange={(e) => set('zip')(e.target.value)}
          error={errors.zip}
          autoComplete="postal-code"
          inputMode={(value.country ?? 'US') === 'US' ? 'numeric' : 'text'}
          maxLength={10}
          disabled={disabled}
        />
      </div>
    </div>
  );
}
