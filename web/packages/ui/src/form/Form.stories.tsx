import { type Meta, type StoryObj } from '@storybook/react-vite';
import { type CountryCode } from 'libphonenumber-js/mobile';
import { useState } from 'react';

import { mobile, rtl } from '../stories.helpers';
import { Checkbox } from './Checkbox';
import { PasswordField } from './PasswordField';
import { PhoneInput } from './PhoneInput';
import { Select, TextField } from './TextField';

const meta: Meta = { title: 'Forms/Fields' };
export default meta;
type Story = StoryObj;

export const Input: Story = { render: () => <TextField label="Email" type="email" placeholder="you@example.com" help="We’ll send your receipt here" /> };
export const InputError: Story = { render: () => <TextField label="Email" defaultValue="adaeze@" error="Enter a valid email address" /> };
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
export const Password: Story = { render: () => <PasswordDemo /> };
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
  )
};
export const RTL: Story = { ...rtl, render: () => <PhoneDemo initial="(713) 249-0142" /> };
export const Mobile: Story = { ...mobile, render: () => <PasswordDemo /> };
