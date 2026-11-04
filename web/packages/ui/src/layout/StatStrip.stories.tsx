import { type Meta, type StoryObj } from '@storybook/react-vite';
import { expect } from 'storybook/test';

import { mobile, rtl } from '../stories.helpers';
import { StatStrip } from './StatStrip';

const items = [
  { label: 'Available', value: 1 },
  { label: 'Reserved', value: 4 },
  { label: 'Inbound', value: 0 },
  { label: 'Low-stock SKUs', value: 1, sub: 'Needs restocking', tone: 'attention' as const, onSelect: () => undefined }
];

const meta: Meta<typeof StatStrip> = { title: 'Layout/StatStrip', component: StatStrip, args: { items, label: 'Stock summary' } };
export default meta;
type Story = StoryObj<typeof StatStrip>;

export const Default: Story = {
  play: async ({ canvas }) => {
    await expect(canvas.getAllByRole('listitem')).toHaveLength(4);
    await expect(canvas.getByRole('button', { name: /Low-stock SKUs/ })).toBeVisible();
  }
};

export const Rtl: Story = { ...rtl };

/** Two columns at phone width. */
export const Mobile: Story = { ...mobile };
