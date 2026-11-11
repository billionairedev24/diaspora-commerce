'use client';

import { Tabs } from '@afrotransact/ui';
import { usePathname, useRouter, useSearchParams } from 'next/navigation';

import { ModulePage } from '@/components/module/ModulePage';
import { ResourceTable } from '@/components/resources/ResourceTable';
import { useT } from '@/i18n/t';

import { TaxCodeMapPanel } from '../tax/TaxCodeMapPanel';
import { TaxMethodPanel } from '../tax/TaxMethodPanel';
import { TaxProfilesPanel } from '../tax/TaxProfilesPanel';
import { TaxReviewPanel } from '../tax/TaxReviewPanel';
import { TaxReviewSettingsPanel } from '../tax/TaxReviewSettingsPanel';
import { TaxShadowPanel } from '../tax/TaxShadowPanel';
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
          { value: 'tax-method', label: 'Who calculates tax', content: <TaxMethodPanel /> },
          { value: 'tax-profiles', label: 'Tax profiles', content: <TaxProfilesPanel /> },
          { value: 'tax-review', label: 'Tax review', content: <TaxReviewPanel /> },
          { value: 'tax-review-settings', label: 'Review settings', content: <TaxReviewSettingsPanel /> },
          { value: 'tax-codes', label: 'Provider codes', content: <TaxCodeMapPanel /> },
          { value: 'tax-shadow', label: 'Shadow report', content: <TaxShadowPanel /> },
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
            value: 'tax-nexus',
            label: 'Nexus tracker',
            content: (
              <div className="flex flex-col gap-3">
                <p className="m-0 max-w-[80ch] text-sm text-muted">
                  Sales shipped into each state over the last 12 months against its threshold. A state past its threshold with no registration comes first. Open a row to set that state&apos;s thresholds or record its registration. This tracks and warns; registering is done with the state, and the default threshold is a common figure to confirm with your tax advisor.
                </p>
                <ResourceTable resource="tax-nexus" urlPrefix="tax-nexus" />
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
          }
  ];
  const taxTabs = ['tax-method', 'tax-profiles', 'tax-review', 'tax-review-settings', 'tax-codes', 'tax-shadow', 'tax-filings', 'tax-nexus', 'tax-rates'];
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
