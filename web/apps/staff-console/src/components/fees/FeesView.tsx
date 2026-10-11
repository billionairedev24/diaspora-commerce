'use client';

import { Tabs } from '@afrotransact/ui';
import { usePathname, useRouter, useSearchParams } from 'next/navigation';

import { ModulePage } from '@/components/module/ModulePage';
import { ResourceTable } from '@/components/resources/ResourceTable';

import { FeeSchedulesPanel } from './FeeSchedulesPanel';

/** Platform configuration → Fees: the fee-schedule editor and marketplace-facilitator tax rates. */
export function FeesView() {
  const router = useRouter();
  const pathname = usePathname();
  const params = useSearchParams();
  const tab = params.get('tab') ?? 'fee-schedules';
  return (
    <ModulePage module="fees">
      <Tabs
        label="Fees, commissions & tax"
        value={tab}
        onValueChange={(v) => router.replace(`${pathname}?tab=${encodeURIComponent(v)}`, { scroll: false })}
        items={[
          { value: 'fee-schedules', label: 'Fee schedules', content: <FeeSchedulesPanel /> },
          { value: 'tax-rates', label: 'Tax rates', content: <ResourceTable resource="tax-rates" urlPrefix="tax-rates" /> }
        ]}
      />
    </ModulePage>
  );
}
