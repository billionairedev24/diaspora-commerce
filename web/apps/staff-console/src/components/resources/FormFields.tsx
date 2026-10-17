'use client';

import { type FieldValue, type FormField, type StaffForm, validateStaffForm } from '@afrotransact/api-client';
import { Button, Checkbox, Field, fieldStyles, Select, TextField } from '@afrotransact/ui';
import { type FormEvent, useState } from 'react';

import { Notice } from '@/components/ui/Notice';
import { toLocalInput, toMinor } from '@/lib/format';

import { FormErrors } from '../ui/FormErrors';

type Draft = Record<string, string | boolean | string[]>;

/** Draft (what the inputs hold) from the form's initial values: money as dollars, date-times in local time. */
export function draftOf(form: StaffForm): Draft {
  const d: Draft = {};
  form.fields.forEach((f) => {
    const v = f.value;
    if (f.type === 'toggle') d[f.key] = v === true;
    else if (f.type === 'multiselect') d[f.key] = Array.isArray(v) ? v : [];
    else if (f.type === 'money') d[f.key] = typeof v === 'number' ? (v / 100).toFixed(2) : '';
    else if (f.type === 'datetime') d[f.key] = typeof v === 'string' && v ? toLocalInput(v) : '';
    else d[f.key] = v === null || v === undefined || Array.isArray(v) ? '' : String(v);
  });
  return d;
}

/** Values to send: numbers, minor units for money, ISO date-times, empty optional fields left out. */
export function valuesOf(form: StaffForm, draft: Draft): Record<string, FieldValue> {
  const out: Record<string, FieldValue> = {};
  form.fields.forEach((f) => {
    const v = draft[f.key];
    if (f.type === 'toggle' || f.type === 'multiselect') {
      out[f.key] = v ?? (f.type === 'toggle' ? false : []);
      return;
    }
    const s = typeof v === 'string' ? v.trim() : '';
    if (s === '') return;
    if (f.type === 'number') out[f.key] = Number(s);
    else if (f.type === 'money') out[f.key] = toMinor(s);
    else if (f.type === 'datetime') out[f.key] = Number.isNaN(Date.parse(s)) ? s : new Date(s).toISOString();
    else out[f.key] = s;
  });
  return out;
}

const inputType = (f: FormField): string => {
  switch (f.type) {
    case 'email':
      return 'email';
    case 'password':
      return 'password';
    case 'date':
      return 'date';
    case 'datetime':
      return 'datetime-local';
    case 'number':
      return 'number';
    default:
      return 'text';
  }
};

const fieldId = (key: string) => `fld-${key.replace(/[^a-z0-9]/gi, '-')}`;

function Input({ f, value, error, onChange }: { f: FormField; value: string | boolean | string[] | undefined; error?: string; onChange: (v: string | boolean | string[]) => void }) {
  const id = fieldId(f.key);
  const label = `${f.label}${f.required && f.type !== 'toggle' ? '' : ' (optional)'}`;
  switch (f.type) {
    case 'toggle':
      return <Checkbox id={id} label={f.label} checked={value === true} onChange={(e) => onChange(e.currentTarget.checked)} />;
    case 'select':
      return <Select id={id} label={label} options={f.options ?? []} placeholder="Choose…" value={typeof value === 'string' ? value : ''} onChange={(e) => onChange(e.currentTarget.value)} error={error} help={f.help} />;
    case 'multiselect': {
      const list = Array.isArray(value) ? value : [];
      return (
        <fieldset className="m-0 flex flex-col gap-1.5 border-0 p-0" aria-describedby={error ? `${id}-err` : undefined}>
          <legend className="mb-1 text-sm font-semibold">{label}</legend>
          {(f.options ?? []).map((o) => (
            <Checkbox key={o.value} id={`${id}-${o.value}`} label={o.label} checked={list.includes(o.value)} onChange={(e) => onChange(e.currentTarget.checked ? [...list, o.value] : list.filter((x) => x !== o.value))} />
          ))}
          {error ? (
            <span id={`${id}-err`} role="alert" className="text-xs text-danger">
              {error}
            </span>
          ) : null}
        </fieldset>
      );
    }
    case 'textarea':
      return (
        <Field label={label} error={error} help={f.help} id={id}>
          {(a11y) => <textarea {...a11y} className={fieldStyles.control} rows={4} maxLength={f.maxLength} value={typeof value === 'string' ? value : ''} onChange={(e) => onChange(e.currentTarget.value)} />}
        </Field>
      );
    case 'money':
      return <TextField id={id} label={`${label} ($)`} inputMode="decimal" placeholder="0.00" value={typeof value === 'string' ? value : ''} onChange={(e) => onChange(e.currentTarget.value)} error={error} help={f.help} />;
    default:
      return (
        <TextField
          id={id}
          label={label}
          type={inputType(f)}
          step={f.type === 'number' ? 'any' : undefined}
          maxLength={f.maxLength}
          placeholder={f.placeholder}
          autoComplete={f.type === 'password' ? 'new-password' : 'off'}
          value={typeof value === 'string' ? value : ''}
          onChange={(e) => onChange(e.currentTarget.value)}
          error={error}
          help={f.help}
        />
      );
  }
}

/** Renders a server-sent form, validates with the same rules as the BFF, then submits the typed values. */
export function FormFields({
  form,
  onSubmit,
  onCancel,
  busy,
  serverErrors,
  error
}: {
  form: StaffForm;
  onSubmit: (values: Record<string, FieldValue>) => void;
  onCancel?: () => void;
  busy?: boolean;
  serverErrors?: Record<string, string>;
  error?: string | null;
}) {
  const [draft, setDraft] = useState<Draft>(() => draftOf(form));
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [attempts, setAttempts] = useState(0);
  const shown = { ...serverErrors, ...errors };
  const submit = (e: FormEvent) => {
    e.preventDefault();
    // One request at a time: Enter or a second click while the first is in flight does nothing.
    if (busy) return;
    const values = valuesOf(form, draft);
    const found = validateStaffForm(form, values);
    setErrors(found);
    setAttempts((n) => n + 1);
    if (Object.keys(found).length === 0) onSubmit(values);
  };
  return (
    <form noValidate onSubmit={submit} className="flex flex-col gap-3">
      {form.intro ? <p className="m-0 text-sm text-muted">{form.intro}</p> : null}
      <FormErrors errors={shown} ids={Object.fromEntries(form.fields.map((f) => [f.key, fieldId(f.key)]))} submitCount={attempts} />
      {form.fields.map((f) => (
        <Input key={f.key} f={f} value={draft[f.key]} error={shown[f.key]} onChange={(v) => setDraft((d) => ({ ...d, [f.key]: v }))} />
      ))}
      {error ? (
        <Notice tone="danger" title="Not saved." role="alert">
          {error}
        </Notice>
      ) : null}
      <div className="flex justify-end gap-2 pt-1">
        {onCancel ? (
          <Button variant="neutral" onClick={onCancel}>
            Cancel
          </Button>
        ) : null}
        <Button type="submit" loading={busy}>
          {form.submitLabel}
        </Button>
      </div>
    </form>
  );
}
