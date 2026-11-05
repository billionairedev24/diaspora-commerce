'use client';

import { Field, fieldStyles, TextField } from '@afrotransact/ui';

import { type MessageKey, type Translator, useT } from '@/i18n/t';

/** What every staff reason dialog asks for unless it says otherwise (kept in the audit log with the person's name). */
export const MIN_REASON = 10;

export interface ReasonRule {
  /** The reason is long enough: the dialog's button may be enabled (for this rule). */
  ok: boolean;
  /** Danger text under the field once the person has started typing and is still short. */
  error: string | undefined;
  /** The rule, shown until it turns into `error`; never empty so a disabled button always has its reason on screen. */
  help: string;
}

/**
 * The rule of a reason field as the person types: nothing typed shows the rule as help, a few characters show
 * "Add a few more words: at least N characters (k of N)" in the error state, enough characters clear it. The one place
 * every staff reason dialog gets its inline rule from, so a disabled Save button is never silent.
 */
export function reasonRule(t: Translator<MessageKey>, value: string, min: number = MIN_REASON): ReasonRule {
  const count = [...value.trim()].length;
  const ok = count >= min;
  return {
    ok,
    error: !ok && count > 0 ? t('st.reason.more', { min: String(min), count: String(count) }) : undefined,
    help: t('st.reason.rule', { min: String(min) })
  };
}

/** A required reason: a multi-line field with its length rule shown as the person types. `error` is a server answer. */
export function ReasonField({
  id,
  label,
  value,
  onChange,
  min = MIN_REASON,
  max = 1000,
  rows = 3,
  single = false,
  help,
  error
}: {
  id?: string;
  label: string;
  value: string;
  onChange: (value: string) => void;
  min?: number;
  max?: number;
  rows?: number;
  /** One line instead of a text area (short reasons such as a page's publish note). */
  single?: boolean;
  /** Extra help; the length rule is shown instead of it while the reason is too short. */
  help?: string;
  error?: string | null;
}) {
  const t = useT();
  const rule = reasonRule(t, value, min);
  const message = error ?? rule.error ?? null;
  const hint = rule.ok && help ? help : rule.help;
  if (single) return <TextField id={id} label={label} value={value} maxLength={max} error={message ?? undefined} help={hint} onChange={(e) => onChange(e.currentTarget.value)} />;
  return (
    <Field label={label} id={id} error={message} help={hint}>
      {(a11y) => <textarea {...a11y} className={fieldStyles.control} rows={rows} maxLength={max} value={value} onChange={(e) => onChange(e.currentTarget.value)} />}
    </Field>
  );
}
