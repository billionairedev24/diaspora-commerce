import { forwardRef, type InputHTMLAttributes, type ReactNode, useId } from 'react';

import { cx } from '../cx';
import { fieldStyles } from './Field';

export interface CheckboxProps extends Omit<InputHTMLAttributes<HTMLInputElement>, 'type'> {
  label: ReactNode;
  error?: string | null;
  /** Additional text announced with the checkbox (e.g. the SMS carrier disclosure). */
  description?: ReactNode;
}

/** Checkbox with a clickable label; error and description are linked through aria-describedby. */
export const Checkbox = forwardRef<HTMLInputElement, CheckboxProps>(function Checkbox({ label, error, description, id: idProp, className, ...rest }, ref) {
  const auto = useId();
  const id = idProp ?? `c${auto.replace(/:/g, '')}`;
  const descId = description ? `${id}-desc` : undefined;
  const errId = error ? `${id}-err` : undefined;
  return (
    <div className={className}>
      <label htmlFor={id} className={fieldStyles.checkRow}>
        <input
          ref={ref}
          id={id}
          type="checkbox"
          className={fieldStyles.checkbox}
          aria-invalid={error ? true : undefined}
          aria-describedby={cx(descId, errId) || undefined}
          {...rest}
        />
        <span>
          {label}
          {description && (
            <>
              {' '}
              <span id={descId}>{description}</span>
            </>
          )}
        </span>
      </label>
      {error && (
        <p id={errId} className={cx(fieldStyles.message, fieldStyles.error)} aria-live="polite">
          {error}
        </p>
      )}
    </div>
  );
});
