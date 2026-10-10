import { type FieldValue, type StaffForm } from '../schemas/staff';

/**
 * Validates submitted values against a staff console form (required, number range, length, pattern, email, date and
 * options). The Go BFF applies the same rules (domain.ValidateForm), so the console catches mistakes before submitting
 * and the server still refuses them. Returns a message per invalid field (empty when valid).
 */
export function validateStaffForm(form: StaffForm, values: Record<string, FieldValue>): Record<string, string> {
  const errors: Record<string, string> = {};
  form.fields.forEach((f) => {
    const v = values[f.key];
    const empty = v === undefined || v === null || v === '' || (Array.isArray(v) && v.length === 0);
    if (empty) {
      if (f.required && f.type !== 'toggle') errors[f.key] = 'Required';
      return;
    }
    if (f.type === 'number' || f.type === 'money') {
      if (typeof v !== 'number' || Number.isNaN(v)) errors[f.key] = 'Enter a number';
      else if (f.min !== undefined && v < f.min) errors[f.key] = `Must be at least ${f.min}`;
      else if (f.max !== undefined && v > f.max) errors[f.key] = `Must be at most ${f.max}`;
      return;
    }
    if (f.type === 'toggle') {
      if (typeof v !== 'boolean') errors[f.key] = 'Choose yes or no';
      return;
    }
    if (f.type === 'multiselect') {
      if (!Array.isArray(v) || (f.options && v.some((x) => !f.options?.some((o) => o.value === x)))) errors[f.key] = 'Choose from the options';
      return;
    }
    if (typeof v !== 'string') {
      errors[f.key] = 'Enter text';
      return;
    }
    if (f.maxLength !== undefined && [...v].length > f.maxLength) errors[f.key] = `Keep it under ${f.maxLength} characters`;
    else if (f.pattern && !new RegExp(f.pattern).test(v)) errors[f.key] = f.help ?? 'Invalid format';
    else if (f.type === 'email' && !/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(v)) errors[f.key] = 'Enter a valid email';
    else if (f.type === 'select' && f.options?.length && !f.options.some((o) => o.value === v)) errors[f.key] = 'Choose one of the options';
    else if (f.type === 'date' && !/^\d{4}-\d{2}-\d{2}$/.test(v)) errors[f.key] = 'Enter a date (YYYY-MM-DD)';
    else if (f.type === 'datetime' && Number.isNaN(Date.parse(v))) errors[f.key] = 'Enter a date and time';
  });
  return errors;
}
