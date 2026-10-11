'use client';

import { type StaffModuleKey } from '@afrotransact/api-client';

import { DashboardView } from '@/components/dashboard/DashboardView';
import { DispatchView } from '@/components/dispatch/DispatchView';
import { ExportsView } from '@/components/exports/ExportsView';
import { FeesView } from '@/components/fees/FeesView';
import { InventoryHubView } from '@/components/inventory/InventoryHubView';
import { NotificationsView } from '@/components/notifications/NotificationsView';
import { PreordersView } from '@/components/preorders/PreordersView';
import { RetailView } from '@/components/retail/RetailView';
import { SupportView } from '@/components/support/SupportView';

import { ModuleView } from './ModuleView';

/** The screen of a module: dashboard, exports, support, fees, dispatch, notifications, pre-orders, the inventory hub and
    AfroTransact Retail have their own; the rest are server-driven tables. */
export function ModuleScreen({ module }: { module: StaffModuleKey }) {
  switch (module) {
    case 'dash':
      return <DashboardView />;
    case 'exports':
      return <ExportsView />;
    case 'support':
      return <SupportView />;
    case 'fees':
      return <FeesView />;
    case 'dispatch':
      return <DispatchView />;
    case 'notifications':
      return <NotificationsView />;
    case 'preorders':
      return <PreordersView screen={{ kind: 'list' }} />;
    case 'inventory':
      return <InventoryHubView />;
    case 'retail':
      return <RetailView />;
    default:
      return <ModuleView module={module} />;
  }
}
