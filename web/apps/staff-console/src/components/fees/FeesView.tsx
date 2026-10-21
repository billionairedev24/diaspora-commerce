'use client';

import { Tabs } from '@afrotransact/ui';
import { usePathname, useRouter, useSearchParams } from 'next/navigation';

import { ModulePage } from '@/components/module/ModulePage';
import { ResourceTable } from '@/components/resources/ResourceTable';
import { useT } from '@/i18n/t';

import { FeeSchedulesPanel } from './FeeSchedulesPanel';
import { NegotiatedRatesPanel } from './NegotiatedRatesPanel';
import { RateCalculator } from './RateCalculator';
import { RateMatrixPanel } from './RateMatrixPanel';
import { TaxRatesImportPanel } from './TaxRatesImportPanel';

/**
 * Platform configuration → Fees & commissions: product commission and service connection fees (category × plan),
 * negotiated rates, the rate calculator, the fee-schedule versions and marketplace-facilitator tax rates.
 */
export function FeesView() {
  const t = useT();
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
          { value: 'commission', label: t('st.rates.tab.commission'), content: <RateMatrixPanel kind="PRODUCT" /> },
          { value: 'connection-fees', label: t('st.rates.tab.connection'), content: <RateMatrixPanel kind="SERVICE" /> },
          { value: 'negotiated', label: t('st.rates.tab.negotiated'), content: <NegotiatedRatesPanel /> },
          { value: 'calculator', label: t('st.rates.tab.calculator'), content: <RateCalculator /> },
          {
            value: 'tax-rates',
            label: 'Tax rates',
            content: (
              <div className="flex flex-col gap-6">
                <TaxRatesImportPanel />
                <ResourceTable resource="tax-rates" urlPrefix="tax-rates" />
              </div>
            )
          },
          {
            value: 'tax-classification',
            label: 'Tax classification',
            content: (
              <div className="flex flex-col gap-8">
                <section className="flex flex-col gap-2">
                  <h3 className="m-0 text-md">Review queue</h3>
                  <p className="m-0 text-sm text-muted">
                    Items taxed as general goods because no rule matched (an unanswered question, a custom category or a new product type). Add a rule below, or mark the item reviewed.
                  </p>
                  <ResourceTable resource="tax-review" urlPrefix="tax-review" />
                </section>
                <section className="flex flex-col gap-2">
                  <h3 className="m-0 text-md">Rules</h3>
                  <ResourceTable resource="tax-rules" urlPrefix="tax-rules" />
                </section>
                <section className="flex flex-col gap-2">
                  <h3 className="m-0 text-md">Tax codes</h3>
                  <ResourceTable resource="tax-codes" urlPrefix="tax-codes" />
                </section>
              </div>
            )
          }
        ]}
      />
    </ModulePage>
  );
}
