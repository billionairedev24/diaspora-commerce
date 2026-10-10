'use client';

import { forwardRef, type ReactNode, useState } from 'react';

import { cx } from '../cx';
import { CheckCircle, Circle, Eye, EyeSlash } from '../icons';
import { Field, fieldStyles } from './Field';
import { type TextFieldProps } from './TextField';

export interface PasswordRuleView {
  id: string;
  label: string;
  ok: boolean;
}

export interface PasswordFieldProps extends Omit<TextFieldProps, 'type'> {
  /** Live checklist (e.g. evaluatePassword() from @afrotransact/api-client). */
  rules?: PasswordRuleView[];
  rulesLabel?: string;
  showLabel?: string;
  hideLabel?: string;
}

/** Password input with a show/hide toggle and an optional live rule checklist. */
export const PasswordField = forwardRef<HTMLInputElement, PasswordFieldProps>(function PasswordField(
  { label, error, help, rules, rulesLabel = 'Password requirements', showLabel = 'Show password', hideLabel = 'Hide password', id, className, ...rest },
  ref
) {
  const [visible, setVisible] = useState(false);
  const checklist: ReactNode = rules?.length ? (
    <ul className={fieldStyles.rules} aria-label={rulesLabel} aria-live="polite">
      {rules.map((r) => (
        <li key={r.id} className={cx(fieldStyles.rule, r.ok && fieldStyles.ruleOk)}>
          {r.ok ? <CheckCircle weight="fill" aria-hidden /> : <Circle aria-hidden />}
          <span>
            {r.label}
            <span className="at-visually-hidden">{r.ok ? ' — met' : ' — not met'}</span>
          </span>
        </li>
      ))}
    </ul>
  ) : null;
  return (
    <div className={className}>
      <Field label={label} error={error} help={help} id={id}>
        {(a11y) => (
          <span className={fieldStyles.withAddon}>
            <input ref={ref} type={visible ? 'text' : 'password'} className={fieldStyles.control} {...a11y} {...rest} />
            <button type="button" className={fieldStyles.addon} onClick={() => setVisible((v) => !v)} aria-label={visible ? hideLabel : showLabel} aria-pressed={visible}>
              {visible ? <EyeSlash size={20} aria-hidden /> : <Eye size={20} aria-hidden />}
            </button>
          </span>
        )}
      </Field>
      {checklist}
    </div>
  );
});
