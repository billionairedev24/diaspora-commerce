'use client';

import { Tabs } from '@afrotransact/ui';
import { usePathname, useRouter, useSearchParams } from 'next/navigation';

import { TaxRatesImportPanel } from '@/components/fees/TaxRatesImportPanel';
import { ModulePage } from '@/components/module/ModulePage';
import { ResourceTable } from '@/components/resources/ResourceTable';
import { useT } from '@/i18n/t';
import { useStaff } from '@/lib/session';

import { TaxCodeMapPanel } from './TaxCodeMapPanel';
import { TaxMethodPanel } from './TaxMethodPanel';
import { TaxProfilesPanel } from './TaxProfilesPanel';
import { TaxReviewPanel } from './TaxReviewPanel';
import { TaxReviewSettingsPanel } from './TaxReviewSettingsPanel';
import { TaxShadowPanel } from './TaxShadowPanel';

/**
 * Sales tax, a module of its own in the sidebar: the review queue and its settings, who calculates tax, the category
 * profiles, provider codes, the shadow report and, for staff who may open Fees, filings, nexus and rates. Everything
 * here follows tax:read (see) and tax:classify-admin (change); the tabs keep the keys they had under Settings, so old
 * links to `?tab=tax-review` still land on the right one.
 */
/** What only makes sense with a detail sheet open: switching tab drops it, so coming back to a tab never reopens a stale sheet. */
const SHEET_PARAMS = ['open', 'variant', 'profile', 'do'];

export function TaxView() {
  const t = useT();
  const router = useRouter();
  const pathname = usePathname();
  const params = useSearchParams();
  const { module } = useStaff();
  // Filings, nexus and rates are tables of the Fees module: whoever may not open Fees does not get tabs that would refuse them.
  const withFees = module('fees') !== null;
  const items = [
    { value: 'tax-review', label: t('st.tax.tab.review'), content: <TaxReviewPanel /> },
    { value: 'tax-review-settings', label: t('st.tax.tab.reviewSettings'), content: <TaxReviewSettingsPanel /> },
    { value: 'tax-method', label: t('st.tax.tab.method'), content: <TaxMethodPanel /> },
    { value: 'tax-profiles', label: t('st.tax.tab.profiles'), content: <TaxProfilesPanel /> },
    { value: 'tax-codes', label: t('st.tax.tab.codes'), content: <TaxCodeMapPanel /> },
    { value: 'tax-shadow', label: t('st.tax.tab.shadow'), content: <TaxShadowPanel /> },
    ...(withFees
      ? [
          {
            value: 'tax-filings',
            label: t('st.tax.tab.filings'),
            content: (
              <div className="flex flex-col gap-3">
                <p className="m-0 max-w-[80ch] text-sm text-muted">{t('st.tax.filings.intro')}</p>
                <ResourceTable resource="tax-filings" urlPrefix="tax-filings" />
              </div>
            )
          },
          {
            value: 'tax-nexus',
            label: t('st.tax.tab.nexus'),
            content: (
              <div className="flex flex-col gap-3">
                <p className="m-0 max-w-[80ch] text-sm text-muted">{t('st.tax.nexus.intro')}</p>
                <ResourceTable resource="tax-nexus" urlPrefix="tax-nexus" />
              </div>
            )
          },
          {
            value: 'tax-rates',
            label: t('st.tax.tab.rates'),
            content: (
              <div className="flex flex-col gap-6">
                <TaxRatesImportPanel />
                <ResourceTable resource="tax-rates" urlPrefix="tax-rates" />
              </div>
            )
          }
        ]
      : [])
  ];
  const tab = params.get('tab') ?? '';
  return (
    <ModulePage module="tax">
      <div data-tour="tax-tabs">
        <Tabs
          label={t('st.tax.tabs')}
          value={items.some((it) => it.value === tab) ? tab : (items[0]?.value ?? tab)}
          onValueChange={(v) => router.replace(`${pathname}?${new URLSearchParams([...params.entries()].filter(([k]) => !SHEET_PARAMS.includes(k) && k !== 'tab').concat([['tab', v]])).toString()}`, { scroll: false })}
          items={items}
        />
      </div>
    </ModulePage>
  );
}
