'use client';

import { Tabs } from '@afrotransact/ui';
import { usePathname, useRouter, useSearchParams } from 'next/navigation';

import { ModulePage } from '@/components/module/ModulePage';
import { ResourceTable } from '@/components/resources/ResourceTable';
import { useT } from '@/i18n/t';

import { PickupsPanel } from './PickupsPanel';

/** Delivery dispatch: the routes table and the seller pickups of AfroTransact Delivery from the seller's store (S255). */
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
          { value: 'routes', label: t('st.pickups.routesTab'), content: <ResourceTable resource="routes" /> },
          { value: 'pickups', label: t('st.pickups.tab'), content: <PickupsPanel /> }
        ]}
      />
    </ModulePage>
  );
}
