'use client';

import { type StaffModuleKey } from '@afrotransact/api-client';

import { DashboardView } from '@/components/dashboard/DashboardView';
import { ExportsView } from '@/components/exports/ExportsView';
import { FeesView } from '@/components/fees/FeesView';
import { SupportView } from '@/components/support/SupportView';

import { ModuleView } from './ModuleView';

/** The screen of a module: dashboard, exports, support and fees have their own; the rest are server-driven tables. */
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
    default:
      return <ModuleView module={module} />;
  }
}
