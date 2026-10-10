import { type ReactNode, useId } from 'react';

import { cx } from '../cx';
import styles from './Field.module.css';

export interface FieldRenderProps {
  id: string;
  'aria-invalid': boolean | undefined;
  'aria-describedby': string | undefined;
}

export interface FieldProps {
  label: ReactNode;
  error?: string | null;
  help?: ReactNode;
  /** Shows the help text in the success colour (e.g. "Mobile number looks good"). */
  helpTone?: 'muted' | 'success';
  id?: string;
  className?: string;
  children: (control: FieldRenderProps) => ReactNode;
}

/** Label + control + help/error message, wired with aria-invalid and aria-describedby. */
export function Field({ label, error, help, helpTone = 'muted', id: idProp, className, children }: FieldProps) {
  const auto = useId();
  const id = idProp ?? `f${auto.replace(/:/g, '')}`;
  const msgId = `${id}-msg`;
  const hasMsg = !!error || !!help;
  return (
    <div className={cx(styles.field, className)}>
      <label htmlFor={id} className={styles.label}>
        {label}
      </label>
      {children({ id, 'aria-invalid': error ? true : undefined, 'aria-describedby': hasMsg ? msgId : undefined })}
      <p id={msgId} className={cx(styles.message, error ? styles.error : helpTone === 'success' && styles.success)} aria-live={error ? 'polite' : undefined}>
        {error || help}
      </p>
    </div>
  );
}

export const fieldStyles = styles;
