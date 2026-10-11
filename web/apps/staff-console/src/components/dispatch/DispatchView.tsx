'use client';

import { Tabs } from '@afrotransact/ui';
import { usePathname, useRouter, useSearchParams } from 'next/navigation';

import { ModulePage } from '@/components/module/ModulePage';
import { ResourceTable } from '@/components/resources/ResourceTable';
import { useT } from '@/i18n/t';

import { PickupsPanel } from './PickupsPanel';

/**
 * Delivery dispatch: routes (with the route builder), the shipments waiting for a route, seller pickups (S255), the
 * customer pickup points (AfroTransact counters and lockers checkout offers for platform-fulfilled items), and what
 * the driver app raises: SOS alerts, vehicle defects that block a route, and the driver event log (S281).
 */
export function DispatchView() {
  const t = useT();
  const router = useRouter();
  const pathname = usePathname();
  const params = useSearchParams();
  const tab = params.get('tab') ?? 'routes';
  return (
    <ModulePage module="dispatch">
      <Tabs
        label={t('st.moduleView.tables')}
        value={tab}
        onValueChange={(v) => router.replace(`${pathname}?tab=${encodeURIComponent(v)}`, { scroll: false })}
        items={[
          { value: 'routes', label: t('st.pickups.routesTab'), content: <ResourceTable resource="routes" urlPrefix="routes" /> },
          { value: 'shipments', label: t('st.dispatch.shipmentsTab'), content: <ResourceTable resource="dispatch-shipments" urlPrefix="shipments" /> },
          { value: 'pickups', label: t('st.pickups.tab'), content: <PickupsPanel /> },
          { value: 'pickup-points', label: t('st.dispatch.pickupPointsTab'), content: <ResourceTable resource="pickup-locations" urlPrefix="pickup-points" /> },
          { value: 'sos', label: t('st.dispatch.sosTab'), content: <ResourceTable resource="sos-alerts" urlPrefix="sos" /> },
          { value: 'defects', label: t('st.dispatch.defectsTab'), content: <ResourceTable resource="vehicle-defects" urlPrefix="defects" /> },
          { value: 'events', label: t('st.dispatch.eventsTab'), content: <ResourceTable resource="driver-events" urlPrefix="events" /> }
        ]}
      />
    </ModulePage>
  );
}
