import { forwardRef, type InputHTMLAttributes, type ReactNode } from 'react';

import { cx } from '../cx';
import { Field, fieldStyles } from './Field';

export interface TextFieldProps extends Omit<InputHTMLAttributes<HTMLInputElement>, 'size'> {
  label: ReactNode;
  error?: string | null;
  help?: ReactNode;
  helpTone?: 'muted' | 'success';
  /** Shows the red asterisk and sets aria-required (the field is marked, not natively validated). */
  required?: boolean;
  /** An info button next to the label opens this longer explanation. */
  tip?: ReactNode;
}

/** Labelled text input with help text and per-field error. */
export const TextField = forwardRef<HTMLInputElement, TextFieldProps>(function TextField({ label, error, help, helpTone, required, tip, id, className, ...rest }, ref) {
  return (
    <Field label={label} error={error} help={help} helpTone={helpTone} required={required} tip={tip} id={id} className={className}>
      {(a11y) => <input ref={ref} className={fieldStyles.control} {...a11y} {...rest} />}
    </Field>
  );
});

export interface SelectOption {
  value: string;
  label: string;
  disabled?: boolean;
}

export interface SelectProps extends Omit<InputHTMLAttributes<HTMLSelectElement>, 'size' | 'children'> {
  label: ReactNode;
  options: SelectOption[];
  error?: string | null;
  help?: ReactNode;
  placeholder?: string;
  /** Shows the red asterisk and sets aria-required. */
  required?: boolean;
  tip?: ReactNode;
}

/** Labelled native select (best keyboard and screen-reader support, works on every phone). */
export const Select = forwardRef<HTMLSelectElement, SelectProps>(function Select({ label, options, error, help, placeholder, required, tip, id, className, ...rest }, ref) {
  return (
    <Field label={label} error={error} help={help} required={required} tip={tip} id={id} className={className}>
      {(a11y) => (
        <select ref={ref} className={cx(fieldStyles.control)} {...a11y} {...rest}>
          {placeholder !== undefined && <option value="">{placeholder}</option>}
          {options.map((o) => (
            <option key={o.value} value={o.value} disabled={o.disabled}>
              {o.label}
            </option>
          ))}
        </select>
      )}
    </Field>
  );
});
