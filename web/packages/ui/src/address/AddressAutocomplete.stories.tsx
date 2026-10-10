import { type Meta, type StoryObj } from '@storybook/react-vite';
import { useState } from 'react';

import { mobile, rtl } from '../stories.helpers';
import { AddressAutocomplete } from './AddressAutocomplete';
import { createFixtureAddressSearch, SAMPLE_ADDRESSES } from './fixture';
import { type SearchAddresses } from './types';

const fixture = createFixtureAddressSearch(SAMPLE_ADDRESSES, { delayMs: 150 });
const never: SearchAddresses = () => new Promise(() => undefined);
const failing: SearchAddresses = async () => {
  throw new Error('geo service down');
};

function Demo({ search = fixture, initial = '' }: { search?: SearchAddresses; initial?: string }) {
  const [value, setValue] = useState(initial);
  const [picked, setPicked] = useState('');
  return (
    <div style={{ maxWidth: 480, minHeight: 360 }}>
      <AddressAutocomplete label="Street address" value={value} onChange={setValue} onSelect={(s) => { setValue(s.label); setPicked(`${s.label}, ${s.secondary}`); }} searchAddresses={search} attribution="Sample addresses" />
      {picked && <p>Selected: {picked}</p>}
    </div>
  );
}

const meta: Meta = { title: 'Forms/AddressAutocomplete' };
export default meta;
type Story = StoryObj;

export const Default: Story = { render: () => <Demo /> };
export const Loading: Story = { render: () => <Demo search={never} initial="9800" /> };
export const Empty: Story = { render: () => <Demo initial="zzzz" /> };
export const ErrorState: Story = { name: 'Error', render: () => <Demo search={failing} initial="1250" /> };
export const RTL: Story = { ...rtl, render: () => <Demo /> };
export const Mobile: Story = { ...mobile, render: () => <Demo /> };
