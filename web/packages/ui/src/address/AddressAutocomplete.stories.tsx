import { type Meta, type StoryObj } from '@storybook/react-vite';
import { useState } from 'react';
import { expect, within } from 'storybook/test';

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

export const Default: Story = {
  render: () => <Demo />,
  play: async ({ canvas, userEvent, step }) => {
    const input = canvas.getByRole('combobox', { name: 'Street address' });
    await step('typing shows matching suggestions', async () => {
      await userEvent.type(input, 'Houston');
      const list = await canvas.findByRole('listbox', { name: 'Address suggestions' });
      await expect(await within(list).findAllByRole('option')).toHaveLength(3);
      await expect(input).toHaveAttribute('aria-expanded', 'true');
    });
    await step('arrow keys + Enter pick a suggestion', async () => {
      await userEvent.keyboard('{ArrowDown}{ArrowDown}{Enter}');
      await expect(input).toHaveValue('2400 Fountain View Dr');
      await expect(canvas.getByText('Selected: 2400 Fountain View Dr, Houston, TX 77057')).toBeVisible();
    });
  }
};
export const Loading: Story = { render: () => <Demo search={never} initial="9800" /> };
export const Empty: Story = { render: () => <Demo initial="zzzz" /> };
export const ErrorState: Story = { name: 'Error', render: () => <Demo search={failing} initial="1250" /> };
export const RTL: Story = { ...rtl, render: () => <Demo /> };
export const Mobile: Story = { ...mobile, render: () => <Demo /> };
