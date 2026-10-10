import { type Meta, type StoryObj } from '@storybook/react-vite';
import { expect, fn } from 'storybook/test';

import { Fingerprint, MagnifyingGlass, X } from '../icons';
import { mobile, rtl } from '../stories.helpers';
import { Button, IconButton } from './Button';

const meta: Meta<typeof Button> = { title: 'Actions/Button', component: Button, args: { children: 'Add to cart', variant: 'primary', size: 'md', onClick: fn() } };
export default meta;
type Story = StoryObj<typeof Button>;

export const Default: Story = {
  play: async ({ args, canvas, userEvent }) => {
    const button = canvas.getByRole('button', { name: 'Add to cart' });
    await userEvent.click(button);
    await expect(args.onClick).toHaveBeenCalledTimes(1);
    // Keyboard activation works too (native <button>).
    await userEvent.keyboard('{Enter}');
    await expect(args.onClick).toHaveBeenCalledTimes(2);
  }
};
export const Variants: Story = {
  render: () => (
    <div style={{ display: 'flex', flexWrap: 'wrap', gap: 12, alignItems: 'center' }}>
      <Button>Add to Cart</Button>
      <Button variant="secondary">Buy Now</Button>
      <Button variant="neutral">Email me when available</Button>
      <Button variant="dark" size="lg" icon={<Fingerprint size={20} aria-hidden />}>Sign in with a passkey</Button>
      <Button variant="link">Save for later</Button>
      <Button size="sm">Small</Button>
      <Button disabled>Disabled</Button>
    </div>
  )
};
export const Loading: Story = {
  args: { loading: true, children: 'Adding…' },
  play: async ({ canvas }) => {
    const button = canvas.getByRole('button', { name: 'Adding…' });
    await expect(button).toBeDisabled();
    await expect(button).toHaveAttribute('aria-busy', 'true');
  }
};
export const IconButtons: Story = {
  render: () => (
    <div style={{ display: 'flex', gap: 12 }}>
      <IconButton label="Search"><MagnifyingGlass size={20} aria-hidden /></IconButton>
      <IconButton label="Close"><X size={20} aria-hidden /></IconButton>
    </div>
  ),
  play: async ({ canvas }) => {
    // Icon-only buttons are named by their label.
    await expect(canvas.getByRole('button', { name: 'Search' })).toBeVisible();
    await expect(canvas.getByRole('button', { name: 'Close' })).toBeVisible();
  }
};
export const RTL: Story = { ...rtl, args: { children: 'أضف إلى السلة' } };
export const Mobile: Story = { ...mobile, args: { block: true } };
