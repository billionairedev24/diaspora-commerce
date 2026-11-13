import { type ReactNode, useId } from 'react';

import { cx } from '../cx';
import styles from './Field.module.css';
import { useFormLabels } from './FormLabels';
import { InfoTip } from './InfoTip';

export interface FieldRenderProps {
  id: string;
  'aria-invalid': boolean | undefined;
  'aria-describedby': string | undefined;
  'aria-required': true | undefined;
}

export interface FieldProps {
  label: ReactNode;
  error?: string | null;
  help?: ReactNode;
  /** The field must be answered: a red asterisk after the label (never colour alone: the screen reader hears "required") and aria-required on the control. Optional fields carry no marker. */
  required?: boolean;
  /** Longer explanation kept out of the page: an info button next to the label opens it (click, Enter or keyboard focus; Escape closes). */
  tip?: ReactNode;
  /** Shows the help text in the success colour (e.g. "Mobile number looks good"). */
  helpTone?: 'muted' | 'success';
  id?: string;
  className?: string;
  children: (control: FieldRenderProps) => ReactNode;
}

/** Label + control + help/error message, wired with aria-invalid and aria-describedby. */
export function Field({ label, error, help, required, tip, helpTone = 'muted', id: idProp, className, children }: FieldProps) {
  const labels = useFormLabels();
  const auto = useId();
  const id = idProp ?? `f${auto.replace(/:/g, '')}`;
  const msgId = `${id}-msg`;
  const hasMsg = !!error || !!help;
  return (
    <div data-at-field="" className={cx(styles.field, className)}>
      <span className={styles.labelRow}>
        <label htmlFor={id} className={styles.label}>
          {label}
        </label>
        {/* Beside the label, not inside it: the control's name stays the plain label; aria-required announces the state and the word is there for browse mode. */}
        {required ? <RequiredMark text={labels.required} /> : null}
        {tip ? <InfoTip label={labels.info}>{tip}</InfoTip> : null}
      </span>
      {children({ id, 'aria-invalid': error ? true : undefined, 'aria-describedby': hasMsg ? msgId : undefined, 'aria-required': required ? true : undefined })}
      <p id={msgId} className={cx(styles.message, error ? styles.error : helpTone === 'success' && styles.success)} aria-live={error ? 'polite' : undefined}>
        {error || help}
      </p>
    </div>
  );
}

/** The red asterisk of a required field and the word a screen reader hears instead of it. */
export function RequiredMark({ text }: { text: string }) {
  return (
    <>
      <span aria-hidden className={styles.star}>&nbsp;*</span>
      <span className="at-visually-hidden"> {text}</span>
    </>
  );
}

export const fieldStyles = styles;
