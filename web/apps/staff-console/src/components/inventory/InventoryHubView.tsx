'use client';

import { Tabs } from '@afrotransact/ui';
import { usePathname, useRouter, useSearchParams } from 'next/navigation';

import { ModulePage } from '@/components/module/ModulePage';

import { CountsPanel } from './CountsPanel';
import { ExpiryPanel } from './ExpiryPanel';
import { FlowPanel } from './FlowPanel';
import { InboundPanel } from './InboundPanel';
import { MovementsPanel } from './MovementsPanel';
import { OverviewPanel } from './OverviewPanel';
import { ReceivingPanel } from './ReceivingPanel';
import { StockPanel } from './StockPanel';

const TABS = [
  ['flow', 'Workflow'],
  ['overview', 'Overview'],
  ['inbound', 'Inbound shipments'],
  ['receiving', 'Receiving'],
  ['stock', 'Stock & lots'],
  ['counts', 'Cycle counts'],
  ['expiry', 'Expiry'],
  ['movements', 'Movements']
] as const;

/** Inventory hub (design/InventoryHub.dc.html): the pipeline from purchase order to the storefront and every step of it. */
export function InventoryHubView() {
  const router = useRouter();
  const pathname = usePathname();
  const params = useSearchParams();
  const tab = params.get('tab') ?? 'flow';
  const panel = (v: string) => {
    switch (v) {
      case 'overview':
        return <OverviewPanel />;
      case 'inbound':
        return <InboundPanel />;
      case 'receiving':
        return <ReceivingPanel />;
      case 'stock':
        return <StockPanel />;
      case 'counts':
        return <CountsPanel />;
      case 'expiry':
        return <ExpiryPanel />;
      case 'movements':
        return <MovementsPanel />;
      default:
        return <FlowPanel />;
    }
  };
  return (
    <ModulePage module="inventory">
      <Tabs
        label="Inventory"
        value={tab}
        onValueChange={(v) => router.replace(`${pathname}?tab=${encodeURIComponent(v)}`, { scroll: false })}
        items={TABS.map(([value, label]) => ({ value, label, content: value === tab ? panel(value) : null }))}
      />
    </ModulePage>
  );
}
