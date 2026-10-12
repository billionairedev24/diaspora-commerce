'use client';

import { Tabs } from '@afrotransact/ui';
import { usePathname, useRouter, useSearchParams } from 'next/navigation';

import { ModulePage } from '@/components/module/ModulePage';
import { useT } from '@/i18n/t';

import { CataloguePanel } from './CataloguePanel';
import { GrantsPanel } from './GrantsPanel';
import { RewardsPanel } from './RewardsPanel';
import { SellerBillingPanel } from './SellerBillingPanel';
import { SettingsPanel } from './SettingsPanel';
import { TrialsPanel } from './TrialsPanel';

/**
 * Finance › Plans & billing (S292–S297): AfroTransact's plan catalogue (versions, publish, Stripe sync, migrations),
 * trials & promotions, reward rules with a "who qualifies now" preview, the grants log and per-seller billing with
 * audited adjustments. Stripe Billing only executes the charges.
 */
export function PlansView() {
  const t = useT();
  const router = useRouter();
  const pathname = usePathname();
  const params = useSearchParams();
  const tab = params.get('tab') ?? 'plans';
  return (
    <ModulePage module="plans">
      <Tabs
        label={t('st.plans.tabs')}
        value={tab}
        onValueChange={(v) => router.replace(`${pathname}?tab=${encodeURIComponent(v)}`, { scroll: false })}
        items={[
          { value: 'plans', label: t('st.plans.tab.plans'), content: <CataloguePanel /> },
          { value: 'trials', label: t('st.plans.tab.trials'), content: <TrialsPanel /> },
          { value: 'rewards', label: t('st.plans.tab.rewards'), content: <RewardsPanel /> },
          { value: 'grants', label: t('st.plans.tab.grants'), content: <GrantsPanel /> },
          { value: 'sellers', label: t('st.plans.tab.sellers'), content: <SellerBillingPanel /> },
          { value: 'settings', label: t('st.plans.tab.settings'), content: <SettingsPanel /> }
        ]}
      />
    </ModulePage>
  );
}
