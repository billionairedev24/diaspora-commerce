'use client';

import { Tabs } from '@afrotransact/ui';
import { usePathname, useRouter, useSearchParams } from 'next/navigation';

import { ModulePage } from '@/components/module/ModulePage';
import { useT } from '@/i18n/t';

import { FeeSchedulesPanel } from './FeeSchedulesPanel';
import { NegotiatedRatesPanel } from './NegotiatedRatesPanel';
import { RateCalculator } from './RateCalculator';
import { RateMatrixPanel } from './RateMatrixPanel';

/**
 * Platform configuration → Fees & commissions: product commission and service connection fees (category × plan),
 * negotiated rates, the rate calculator and the fee-schedule versions. Sales tax has its own module (TaxView).
 */
export function FeesView() {
  const t = useT();
  const router = useRouter();
  const pathname = usePathname();
  const params = useSearchParams();
  const tab = params.get('tab') ?? 'fee-schedules';
  const items = [
    { value: 'fee-schedules', label: 'Fee schedules', content: <FeeSchedulesPanel /> },
    { value: 'commission', label: t('st.rates.tab.commission'), content: <RateMatrixPanel kind="PRODUCT" /> },
    { value: 'connection-fees', label: t('st.rates.tab.connection'), content: <RateMatrixPanel kind="SERVICE" /> },
    { value: 'negotiated', label: t('st.rates.tab.negotiated'), content: <NegotiatedRatesPanel /> },
    { value: 'calculator', label: t('st.rates.tab.calculator'), content: <RateCalculator /> }
  ];
  return (
    <ModulePage module="fees">
      <Tabs
        label="Fees & commissions"
        value={items.some((it) => it.value === tab) ? tab : (items[0]?.value ?? tab)}
        onValueChange={(v) => router.replace(`${pathname}?${new URLSearchParams([...params.entries()].filter(([k]) => k !== 'open' && k !== 'tab').concat([['tab', v]])).toString()}`, { scroll: false })}
        items={items}
      />
    </ModulePage>
  );
}
