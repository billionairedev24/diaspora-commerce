import { type Meta, type StoryObj } from '@storybook/react-vite';
import { type CountryCode } from 'libphonenumber-js/mobile';
import { type FormEvent, useState } from 'react';
import { expect, waitFor } from 'storybook/test';

import { Button } from '../button/Button';
import { mobile, rtl } from '../stories.helpers';
import { Checkbox } from './Checkbox';
import { ErrorSummary, type ErrorSummaryItem } from './ErrorSummary';
import { PasswordField } from './PasswordField';
import { PhoneInput } from './PhoneInput';
import { Select, TextField } from './TextField';

const meta: Meta = { title: 'Forms/Fields' };
export default meta;
type Story = StoryObj;

export const Input: Story = {
  render: () => <TextField label="Email" type="email" placeholder="you@example.com" help="We’ll send your receipt here" />,
  play: async ({ canvas, userEvent }) => {
    const input = canvas.getByRole('textbox', { name: 'Email' });
    await expect(input).toHaveAccessibleDescription('We’ll send your receipt here');
    await userEvent.type(input, 'adaeze@example.com');
    await expect(input).toHaveValue('adaeze@example.com');
  }
};
export const InputError: Story = {
  render: () => <TextField label="Email" defaultValue="adaeze@" error="Enter a valid email address" />,
  play: async ({ canvas }) => {
    const input = canvas.getByRole('textbox', { name: 'Email' });
    await expect(input).toBeInvalid();
    await expect(input).toHaveAccessibleDescription(expect.stringContaining('Enter a valid email address'));
  }
};
export const SelectField: Story = {
  render: () => <Select label="Team" options={[{ value: 'all', label: 'All teams' }, { value: 'eng', label: 'Engineering' }, { value: 'ops', label: 'Operations' }]} />
};

function PhoneDemo({ initial = '', country: c = 'US' as CountryCode, error }: { initial?: string; country?: CountryCode; error?: string }) {
  const [country, setCountry] = useState<CountryCode>(c);
  const [value, setValue] = useState(initial);
  return <PhoneInput label="Mobile phone" country={country} onCountryChange={setCountry} value={value} onChange={setValue} error={error} help="We’ll text a code to verify it" />;
}
export const Phone: Story = { render: () => <PhoneDemo /> };
export const PhoneFilled: Story = { render: () => <PhoneDemo initial="(713) 249-0142" /> };
export const PhoneError: Story = { render: () => <PhoneDemo initial="713" error="Enter a valid mobile number" /> };
export const PhoneNigeria: Story = { render: () => <PhoneDemo country="NG" initial="0803 123 4567" /> };

function PasswordDemo() {
  const [pw, setPw] = useState('Kente2026');
  const rules = [
    { id: 'length', label: '12+ characters', ok: pw.length >= 12 },
    { id: 'case', label: 'Upper and lower case', ok: /[A-Z]/.test(pw) && /[a-z]/.test(pw) },
    { id: 'number', label: 'At least one number', ok: /\d/.test(pw) },
    { id: 'personal', label: 'Not your name or email', ok: true }
  ];
  return <PasswordField label="Password" value={pw} onChange={(e) => setPw(e.target.value)} rules={rules} autoComplete="new-password" />;
}
export const Password: Story = {
  render: () => <PasswordDemo />,
  play: async ({ canvas, userEvent, step }) => {
    const input = canvas.getByLabelText('Password', { selector: 'input' });
    const rules = canvas.getByRole('list', { name: 'Password requirements' });
    await step('the checklist updates while typing', async () => {
      await expect(rules).toHaveTextContent('12+ characters — not met');
      await userEvent.type(input, '!Ankara');
      await expect(rules).toHaveTextContent('12+ characters — met');
    });
    await step('show / hide toggle', async () => {
      const toggle = canvas.getByRole('button', { name: 'Show password' });
      await userEvent.click(toggle);
      await expect(input).toHaveAttribute('type', 'text');
      await expect(canvas.getByRole('button', { name: 'Hide password' })).toHaveAttribute('aria-pressed', 'true');
      await userEvent.click(canvas.getByRole('button', { name: 'Hide password' }));
      await expect(input).toHaveAttribute('type', 'password');
    });
  }
};
export const PasswordError: Story = { render: () => <PasswordField label="Confirm password" defaultValue="nope" error="Passwords don’t match" /> };

export const Checkboxes: Story = {
  render: () => (
    <div style={{ display: 'grid', gap: 12, maxWidth: 480 }}>
      <Checkbox label="I agree to AfroTransact’s Conditions of Use and Privacy Notice, and I’m 18 or older." />
      <Checkbox
        label={<strong>Text me order, delivery and booking updates (optional).</strong>}
        description="By checking this box, you agree to receive recurring automated texts from AfroTransact Alerts at the number above. Consent is not a condition of purchase. Msg & data rates may apply. Reply STOP to cancel, HELP for help."
      />
      <Checkbox label="Accept the terms" error="Please accept the Conditions of Use to continue." />
    </div>
  ),
  play: async ({ canvas, userEvent }) => {
    const terms = canvas.getByRole('checkbox', { name: /I agree to AfroTransact’s Conditions of Use/ });
    await expect(terms).not.toBeChecked();
    // The whole label is the click target.
    await userEvent.click(canvas.getByText(/I agree to AfroTransact’s Conditions of Use/));
    await expect(terms).toBeChecked();
    await expect(canvas.getByRole('checkbox', { name: 'Accept the terms' })).toBeInvalid();
  }
};
function ErrorSummaryDemo() {
  const [errors, setErrors] = useState<ErrorSummaryItem[]>([]);
  const [submits, setSubmits] = useState(0);
  const submit = (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    const data = new FormData(e.currentTarget);
    const next: ErrorSummaryItem[] = [];
    if (!String(data.get('name') ?? '').trim()) next.push({ fieldId: 'es-name', message: 'Enter your full name' });
    if (!/^\S+@\S+\.\S+$/.test(String(data.get('email') ?? ''))) next.push({ fieldId: 'es-email', message: 'Enter a valid email address' });
    setErrors(next);
    setSubmits((n) => n + 1);
  };
  const errorFor = (id: string) => errors.find((x) => x.fieldId === id)?.message;
  return (
    <form noValidate onSubmit={submit} style={{ display: 'grid', gap: 12, maxWidth: 480 }}>
      <ErrorSummary title={`There ${errors.length === 1 ? 'is 1 problem' : `are ${errors.length} problems`} with this form`} errors={errors} submitCount={submits} />
      <TextField id="es-name" name="name" label="Full name" autoComplete="name" error={errorFor('es-name')} />
      <TextField id="es-email" name="email" type="email" label="Email" autoComplete="email" defaultValue="adaeze@" error={errorFor('es-email')} />
      <Button type="submit">Continue</Button>
    </form>
  );
}
export const ErrorSummaryStory: Story = {
  name: 'Error summary',
  render: () => <ErrorSummaryDemo />,
  play: async ({ canvas, userEvent, step }) => {
    await expect(canvas.queryByRole('alert')).toBeNull();
    await step('a failed submit lists every error and takes focus', async () => {
      await userEvent.click(canvas.getByRole('button', { name: 'Continue' }));
      const summary = await canvas.findByRole('alert', { name: 'There are 2 problems with this form' });
      await waitFor(() => expect(summary).toHaveFocus());
      await expect(canvas.getAllByRole('link')).toHaveLength(2);
    });
    await step('a link moves focus to its field', async () => {
      await userEvent.click(canvas.getByRole('link', { name: 'Enter a valid email address' }));
      await expect(canvas.getByRole('textbox', { name: 'Email' })).toHaveFocus();
    });
    await step('fixing one field updates the summary on the next submit', async () => {
      await userEvent.type(canvas.getByRole('textbox', { name: 'Full name' }), 'Adaeze Okafor');
      await userEvent.click(canvas.getByRole('button', { name: 'Continue' }));
      const summary = await canvas.findByRole('alert', { name: 'There is 1 problem with this form' });
      await waitFor(() => expect(summary).toHaveFocus());
      await expect(canvas.getAllByRole('link')).toHaveLength(1);
    });
  }
};
export const RTL: Story = { ...rtl, render: () => <PhoneDemo initial="(713) 249-0142" /> };
export const Mobile: Story = { ...mobile, render: () => <PasswordDemo /> };
