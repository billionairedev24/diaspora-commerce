/* Stories for the Seller Center's presentational primitives (web/apps/seller-center/src/components/ui). They live
   here, not in the app, so the app is untouched; the app's Tailwind stylesheet is loaded through the Storybook Vite
   config (see .storybook/main.ts). Apps may also co-locate `*.stories.tsx` under src/ — main.ts globs them too. */
import '../../../../../apps/seller-center/src/app/globals.css';

import { type Meta, type StoryObj } from '@storybook/react-vite';

import { Card } from '../../../../../apps/seller-center/src/components/ui/Card';
import { KpiTile } from '../../../../../apps/seller-center/src/components/ui/Kpi';
import { Notice } from '../../../../../apps/seller-center/src/components/ui/Notice';
import { PageHeader } from '../../../../../apps/seller-center/src/components/ui/PageHeader';
import { EmptyState, ErrorState, NoAccess, Spinner } from '../../../../../apps/seller-center/src/components/ui/States';
import { StatusPill, toneOf } from '../../../../../apps/seller-center/src/components/ui/StatusPill';
import { Button } from '../../../src/button/Button';
import { mobile, rtl } from '../../../src/stories.helpers';

const meta: Meta = { title: 'Apps/Seller Center/Primitives', parameters: { layout: 'padded' } };
export default meta;
type Story = StoryObj;

type KpiData = Parameters<typeof KpiTile>[0]['kpi'];
const kpis: KpiData[] = [
  { label: 'Sales today', value: '$1,284.50', detail: '+12% vs last Friday', tone: 'positive' },
  { label: 'Orders to ship', value: '16', detail: '3 due by 4 pm', tone: 'attention' },
  { label: 'Buy box share', value: '87%', detail: 'Last 30 days', tone: 'neutral' }
];

export const Kpis: Story = {
  render: () => <div className="grid grid-cols-3 gap-3">{kpis.map((k) => <KpiTile key={k.label} kpi={k} />)}</div>
};

export const Header: Story = {
  render: () => <PageHeader title="Orders" description="Seller-fulfilled orders to ship, plus AfroTransact-fulfilled orders for visibility." actions={<Button size="sm">Buy labels</Button>} />
};

export const CardWithActions: Story = {
  render: () => (
    <Card title="Low stock" actions={<Button size="sm" variant="neutral">View inventory</Button>}>
      <p className="m-0 text-sm text-muted">3 listings will sell out within a week at the current pace.</p>
    </Card>
  )
};

export const StatusPills: Story = {
  render: () => (
    <div className="flex flex-wrap gap-2">
      {['PLACED', 'CONFIRMED', 'READY', 'DELIVERED', 'CANCELLED', 'IN_REVIEW', 'PAST_DUE'].map((s) => <StatusPill key={s} tone={toneOf(s)}>{s.replace(/_/g, ' ').toLowerCase()}</StatusPill>)}
    </div>
  )
};

export const Notices: Story = {
  render: () => (
    <div className="flex flex-col gap-3">
      <Notice tone="info" title="Read-only.">Your role can view orders but not change them.</Notice>
      <Notice tone="warning" title="Stripe needs more information." icon="shield" action={<Button size="sm" variant="neutral">Update</Button>}>Payouts pause on Oct 24.</Notice>
      <Notice tone="danger" title="Payouts paused." role="alert">Verify your bank account to resume.</Notice>
      <Notice tone="success" title="Listing approved.">It is live in the storefront.</Notice>
    </div>
  )
};

export const Loading: Story = { render: () => <Spinner /> };
export const Empty: Story = { render: () => <EmptyState title="No orders yet" action={<Button size="sm">Create a listing</Button>}>Orders appear here as soon as a customer checks out.</EmptyState> };
export const ErrorStory: Story = { name: 'Error', render: () => <ErrorState error={new Error('boom')} onRetry={() => undefined} /> };
export const AccessDenied: Story = { render: () => <NoAccess role="Fulfilment" /> };
export const RTL: Story = { ...rtl, render: () => <Notice tone="info" title="للقراءة فقط.">لا يمكن لدورك تغيير الطلبات.</Notice> };
export const Mobile: Story = { ...mobile, render: () => <div className="grid gap-3">{kpis.map((k) => <KpiTile key={k.label} kpi={k} />)}</div> };
