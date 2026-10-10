import { type Meta, type StoryObj } from '@storybook/react-vite';

import { Fingerprint, MagnifyingGlass, X } from '../icons';
import { mobile, rtl } from '../stories.helpers';
import { Button, IconButton } from './Button';

const meta: Meta<typeof Button> = { title: 'Actions/Button', component: Button, args: { children: 'Add to cart', variant: 'primary', size: 'md' } };
export default meta;
type Story = StoryObj<typeof Button>;

export const Default: Story = {};
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
export const Loading: Story = { args: { loading: true, children: 'Adding…' } };
export const IconButtons: Story = {
  render: () => (
    <div style={{ display: 'flex', gap: 12 }}>
      <IconButton label="Search"><MagnifyingGlass size={20} aria-hidden /></IconButton>
      <IconButton label="Close"><X size={20} aria-hidden /></IconButton>
    </div>
  )
};
export const RTL: Story = { ...rtl, args: { children: 'أضف إلى السلة' } };
export const Mobile: Story = { ...mobile, args: { block: true } };
