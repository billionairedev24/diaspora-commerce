import { type Meta, type StoryObj } from '@storybook/react-vite';
import { useState } from 'react';
import { expect } from 'storybook/test';

import { mobile, rtl } from '../stories.helpers';
import { Badge, FulfilmentBadge } from './Badge';
import { ImagePlaceholder, Skeleton } from './ImagePlaceholder';
import { Price } from './Price';
import { QuantityStepper } from './QuantityStepper';
import { Stars } from './Stars';

const meta: Meta = { title: 'Commerce/Primitives' };
export default meta;
type Story = StoryObj;

export const Badges: Story = {
  render: () => (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
      <FulfilmentBadge fulfilment="AFROTRANSACT" />
      <FulfilmentBadge fulfilment="SELLER" shipsFrom="Bronx, NY" />
      <Badge tone="brand">AfroTransact Brand</Badge>
      <Badge tone="ai">AI summary</Badge>
      <Badge tone="warning">Pending review</Badge>
    </div>
  )
};
export const Rating: Story = {
  render: () => (
    <div style={{ display: 'grid', gap: 8 }}>
      <Stars rating={4.8} reviewCount={1284} />
      <Stars rating={4.5} reviewCount={612} showValue size={16} />
      <Stars rating={3} />
    </div>
  )
};
export const Prices: Story = {
  render: () => (
    <div style={{ display: 'grid', gap: 12 }}>
      <Price amountMinor={649} listPriceMinor={799} size="lg" />
      <Price amountMinor={2199} listPriceMinor={2599} size="xl" showDeal />
      <Price amountMinor={1850} size="md" />
    </div>
  )
};

function StepperDemo({ start = 1, max = 3 }: { start?: number; max?: number }) {
  const [q, setQ] = useState(start);
  return q > 0 ? <QuantityStepper value={q} max={max} onChange={setQ} format={(n) => `${n} in cart`} itemLabel="Auto Gele Headwrap" /> : <span>Removed</span>;
}
const ITEM = 'Auto Gele Headwrap';
export const Stepper: Story = {
  render: () => <StepperDemo />,
  play: async ({ canvas, userEvent, step }) => {
    const group = canvas.getByRole('group', { name: `Quantity of ${ITEM}` });
    const increase = canvas.getByRole('button', { name: `Increase quantity of ${ITEM}` });
    await step('+ goes up to the stock limit', async () => {
      await userEvent.click(increase);
      await expect(group).toHaveTextContent('2 in cart');
      await userEvent.click(increase);
      await expect(group).toHaveTextContent('3 in cart');
      await expect(increase).toBeDisabled();
      await expect(increase).toHaveAttribute('title', 'Only 3 available');
    });
    await step('− goes back down; at 1 it becomes "Remove"', async () => {
      await userEvent.click(canvas.getByRole('button', { name: `Decrease quantity of ${ITEM}` }));
      await userEvent.click(canvas.getByRole('button', { name: `Decrease quantity of ${ITEM}` }));
      await expect(group).toHaveTextContent('1 in cart');
      await expect(canvas.getByRole('button', { name: `Remove from cart: ${ITEM}` })).toBeEnabled();
    });
  }
};
export const StepperAtStock: Story = {
  render: () => <StepperDemo start={3} max={3} />,
  play: async ({ canvas }) => {
    await expect(canvas.getByRole('button', { name: `Increase quantity of ${ITEM}` })).toBeDisabled();
    await expect(canvas.getByRole('button', { name: `Decrease quantity of ${ITEM}` })).toBeEnabled();
  }
};
export const StepperBusy: Story = {
  render: () => <QuantityStepper value={2} max={5} onChange={() => undefined} busy />,
  play: async ({ canvas }) => {
    await expect(canvas.getByRole('group', { name: 'Quantity' })).toHaveAttribute('aria-busy', 'true');
    for (const b of canvas.getAllByRole('button')) await expect(b).toBeDisabled();
  }
};
export const Placeholders: Story = {
  render: () => (
    <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 160px)', gap: 12 }}>
      <ImagePlaceholder alt="Product photo" />
      <ImagePlaceholder alt="Campaign" ratio="16 / 9" caption="Campaign image · 1600×900" />
      <div style={{ display: 'grid', gap: 8 }}>
        <Skeleton height={160} />
        <Skeleton width="80%" />
        <Skeleton width="40%" />
      </div>
    </div>
  )
};
export const Loading: Story = { render: () => <Skeleton height={24} width={240} /> };
export const RTL: Story = { ...rtl, render: () => <StepperDemo /> };
export const Mobile: Story = { ...mobile, render: () => <Price amountMinor={2199} listPriceMinor={2599} size="xl" showDeal /> };
