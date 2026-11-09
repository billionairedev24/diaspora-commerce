import { type Meta, type StoryObj } from '@storybook/react-vite';
import { expect, fn } from 'storybook/test';

import { ChartLineUp, CurrencyDollar, ListChecks, Package, Plus, Wallet } from '../icons';
import { mobile, rtl } from '../stories.helpers';
import { Donut, MiniBars, Sparkline } from './charts';
import { EmptyPrompt } from './EmptyPrompt';
import { HelpStrip } from './HelpStrip';
import { ModuleGrid, ModuleSection } from './ModuleSection';
import { PeriodSelect } from './PeriodSelect';

const days = Array.from({ length: 30 }, (_, i) => ({ label: `Day ${i + 1}`, value: 120 + Math.round(60 * Math.sin(i / 3)) + i * 4 }));
const usd = (v: number) => `$${v.toLocaleString('en-US')}`;
const actions = [
  { label: 'All orders', href: '#orders', icon: <ListChecks size={16} /> },
  { label: 'Add product', href: '#new-product', icon: <Plus size={16} /> },
  { label: 'Payouts', href: '#payouts', icon: <Wallet size={16} /> }
];

const meta: Meta<typeof ModuleSection> = {
  title: 'Dashboard/Kit',
  component: ModuleSection,
  decorators: [(Story) => <div style={{ maxWidth: 360 }}><Story /></div>]
};
export default meta;
type Story = StoryObj<typeof ModuleSection>;

export const Sales: Story = {
  args: {
    title: 'Sales',
    icon: <CurrencyDollar size={18} />,
    headline: { value: '$4,820.50', label: 'revenue', period: 'Last 30 days', tone: 'positive' },
    chart: <Sparkline label="Revenue per day, last 30 days" points={days} format={usd} tone="green" />,
    totalLine: '128 orders · 211 units',
    actions
  },
  play: async ({ canvas }) => {
    await expect(canvas.getByRole('heading', { name: 'Sales' })).toBeVisible();
    await expect(canvas.getByText('Last 30 days')).toBeVisible();
    await expect(canvas.getByRole('img', { name: /Revenue per day/ })).toBeInTheDocument();
    await expect(canvas.getAllByRole('link')).toHaveLength(3);
  }
};

export const Orders: Story = {
  args: {
    title: 'Orders',
    icon: <Package size={18} />,
    headline: { value: '7', label: 'to ship now', period: 'Right now', tone: 'attention' },
    chart: (
      <Donut
        label="Orders by fulfilment"
        points={[{ label: 'Ship yourself', value: 12, tone: 'ink' }, { label: 'Fulfilled by AfroTransact', value: 30, tone: 'green' }, { label: 'Local driver', value: 4, tone: 'gold' }]}
        centre="46"
      />
    ),
    totalLine: '46 orders in the period',
    actions
  }
};

export const Bars: Story = {
  args: { title: 'Bookings', icon: <ChartLineUp size={18} />, headline: { value: '18', label: 'bookings', period: 'Last 7 days' }, chart: <MiniBars label="Bookings per day" points={days.slice(0, 7).map((d) => ({ ...d, value: d.value / 20 }))} />, actions }
};

export const Empty: Story = {
  args: {
    title: 'Listings',
    icon: <Package size={18} />,
    state: 'empty',
    empty: <EmptyPrompt art="listings" prompt="Add your first product to start selling." action={{ label: 'Add your first product', href: '#new-product' }} />
  },
  play: async ({ canvas }) => {
    await expect(canvas.getByRole('link', { name: 'Add your first product' })).toBeVisible();
  }
};

export const Loading: Story = { args: { title: 'Sales', state: 'loading' }, parameters: { chromatic: { disableSnapshot: true } } };

export const ErrorWithRetry: Story = {
  args: { title: 'Payouts', state: 'error', onRetry: fn() },
  play: async ({ canvas, userEvent, args }) => {
    await userEvent.click(canvas.getByRole('button', { name: 'Try again' }));
    await expect(args.onRetry).toHaveBeenCalled();
  }
};

export const Rtl: Story = { ...Sales, ...rtl };
export const Mobile: Story = { ...Sales, ...mobile };

export const FullHome: StoryObj = {
  render: () => (
    <div style={{ display: 'grid', gap: 24 }}>
      <PeriodSelect value="30" onChange={() => undefined} />
      <ModuleGrid>
        <ModuleSection title="Sales" headline={{ value: '$4,820', label: 'revenue', period: 'Last 30 days' }} chart={<Sparkline label="Revenue" points={days} format={usd} />} totalLine="128 orders" actions={actions} />
        <ModuleSection title="Listings" state="empty" empty={<EmptyPrompt art="listings" prompt="Add your first product." action={{ label: 'Add your first product', href: '#' }} />} />
        <ModuleSection title="Payouts" headline={{ value: '$1,204.00', label: 'available', period: 'Today' }} totalLine="$310.00 still settling" actions={actions} />
      </ModuleGrid>
      <HelpStrip description="Talk to our seller support team any time." links={[{ label: 'Help centre', href: '#' }, { label: 'Contact support', href: '#' }]} />
    </div>
  )
};
