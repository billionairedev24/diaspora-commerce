import { type Meta, type StoryObj } from '@storybook/react-vite';
import { expect } from 'storybook/test';

import { rtl } from '../stories.helpers';
import { Spinner } from './Spinner';

const meta: Meta<typeof Spinner> = {
  title: 'Feedback/Spinner',
  component: Spinner,
  args: {
    label: 'Loading…',
    variant: 'primary',
    size: 'md'
  }
};
export default meta;
type Story = StoryObj<typeof Spinner>;

export const Default: Story = {
  play: async ({ canvas }) => {
    const status = canvas.getByRole('status');
    await expect(status).toBeVisible();
    await expect(status).toHaveTextContent('Loading…');
  }
};

export const Sizes: Story = {
  render: () => (
    <div style={{ display: 'flex', gap: 24, alignItems: 'center' }}>
      <Spinner size="xs" label="Extra Small (14px)" />
      <Spinner size="sm" label="Small (18px)" />
      <Spinner size="md" label="Medium (24px)" />
      <Spinner size="lg" label="Large (32px)" />
      <Spinner size="xl" label="Extra Large (44px)" />
    </div>
  )
};

export const Variants: Story = {
  render: () => (
    <div style={{ display: 'flex', gap: 24, alignItems: 'center', background: 'var(--at-ink-800)', padding: 24, borderRadius: 8 }}>
      <Spinner variant="primary" label="Primary (Brand Gold)" />
      <Spinner variant="white" label="White (On Dark)" />
      <Spinner variant="muted" label="Muted" />
    </div>
  )
};

export const CenteredContainer: Story = {
  render: () => (
    <div style={{ width: '100%', maxWidth: 600 }}>
      <Spinner center label="Loading your back office…" />
    </div>
  ),
  play: async ({ canvas }) => {
    const status = canvas.getByRole('status');
    await expect(status).toBeVisible();
    await expect(status).toHaveTextContent('Loading your back office…');
  }
};

export const ScreenReaderOnlyLabel: Story = {
  args: {
    srOnlyLabel: true,
    label: 'Processing transaction…'
  },
  play: async ({ canvas }) => {
    const status = canvas.getByRole('status');
    await expect(status).toBeVisible();
    await expect(status).toHaveTextContent('Processing transaction…');
  }
};

export const RTL: Story = {
  ...rtl,
  args: {
    label: 'جارٍ التحميل…'
  }
};
