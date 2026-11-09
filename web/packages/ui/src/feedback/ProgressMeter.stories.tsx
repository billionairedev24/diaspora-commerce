import { type Meta, type StoryObj } from '@storybook/react-vite';
import { expect } from 'storybook/test';

import { mobile, rtl } from '../stories.helpers';
import { Checklist, ProgressMeter } from './ProgressMeter';

const meta: Meta<typeof ProgressMeter> = {
  title: 'Feedback/ProgressMeter',
  component: ProgressMeter,
  args: { label: 'Stripe Tax', value: 78, max: 104, valueText: '78 of 104 outcomes verified', attention: true, help: '14 outcomes have no code yet and 12 have codes that are not verified. A line with no verified code is blocked at checkout, never guessed.' }
};
export default meta;
type Story = StoryObj<typeof ProgressMeter>;

export const Unfinished: Story = {
  play: async ({ canvas }) => {
    const bar = canvas.getByRole('progressbar', { name: 'Stripe Tax' });
    await expect(bar).toHaveAttribute('aria-valuenow', '78');
    await expect(bar).toHaveAttribute('aria-valuetext', '78 of 104 outcomes verified');
  }
};

export const Complete: Story = { args: { value: 104, valueText: '104 of 104 outcomes verified', attention: false, help: 'Every outcome has a verified code.' } };

export const Empty: Story = { args: { label: 'Avalara', value: 0, valueText: '0 of 104 outcomes verified' } };

export const Rtl: Story = { ...rtl };
export const Mobile: Story = { ...mobile };

export const Checks: StoryObj<typeof Checklist> = {
  render: () => (
    <Checklist
      label="Ready to cut over?"
      items={[
        { key: 'window', title: 'Long enough', ok: true, detail: 'Compared 31 days; at least 30 needed.' },
        { key: 'outcomes', title: 'Same exempt or taxable answer', ok: false, detail: '2 lines where the provider disagreed on exempt or taxable.' }
      ]}
    />
  ),
  play: async ({ canvas }) => {
    await expect(canvas.getAllByRole('listitem')).toHaveLength(2);
    await expect(canvas.getByText(': needs work')).toBeInTheDocument();
    await expect(canvas.getByText(': passes')).toBeInTheDocument();
  }
};
