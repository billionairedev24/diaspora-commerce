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
export function FeesView({ scope = 'all' }: { scope?: 'all' | 'fees' | 'tax' } = {}) {
  const t = useT();
  const router = useRouter();
  const pathname = usePathname();
  const params = useSearchParams();
  const tab = params.get('tab') ?? (scope === 'tax' ? 'tax-method' : 'fee-schedules');
  const all = [
          { value: 'fee-schedules', label: 'Fee schedules', content: <FeeSchedulesPanel /> },
          { value: 'commission', label: t('st.rates.tab.commission'), content: <RateMatrixPanel kind="PRODUCT" /> },
          { value: 'connection-fees', label: t('st.rates.tab.connection'), content: <RateMatrixPanel kind="SERVICE" /> },
          { value: 'negotiated', label: t('st.rates.tab.negotiated'), content: <NegotiatedRatesPanel /> },
          { value: 'calculator', label: t('st.rates.tab.calculator'), content: <RateCalculator /> },
          {
            value: 'tax-method',
            label: 'Who calculates tax',
            content: (
              <div className="flex flex-col gap-3">
                <p className="m-0 max-w-[80ch] text-sm text-muted">
                  Until Stripe Tax is set up, AfroTransact works out the tax itself from the rates and tax codes in the next tabs. Open the row to change the method.
                </p>
                <ResourceTable resource="tax-settings" urlPrefix="tax-settings" />
              </div>
            )
          },
          {
            value: 'tax-filings',
            label: 'Tax filings',
            content: (
              <div className="flex flex-col gap-3">
                <p className="m-0 max-w-[80ch] text-sm text-muted">
                  For each month and state: the tax the ledger says is due, and what was filed and paid. After you file with the state, open the row and record it. If a refund or a late sale changes the month afterwards, the status shows the difference.
                </p>
                <ResourceTable resource="tax-filings" urlPrefix="tax-filings" />
              </div>
            )
          },
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
  ];
  const taxTabs = ['tax-method', 'tax-filings', 'tax-rates', 'tax-classification'];
  const items = all.filter((it) => (scope === 'all' ? it.value !== 'tax-method' : scope === 'tax' ? taxTabs.includes(it.value) : !taxTabs.includes(it.value)));
  return (
    <ModulePage module="fees">
      <Tabs
        label={scope === 'tax' ? 'Sales tax' : scope === 'fees' ? 'Fees & commissions' : 'Fees, commissions & tax'}
        value={items.some((it) => it.value === tab) ? tab : (items[0]?.value ?? tab)}
        onValueChange={(v) => router.replace(`${pathname}?${new URLSearchParams([...params.entries()].filter(([k]) => k !== 'open' && k !== 'tab').concat([['tab', v]])).toString()}`, { scroll: false })}
        items={items}
      />
    </ModulePage>
  );
}
