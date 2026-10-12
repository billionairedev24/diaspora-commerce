'use client';

import { Tabs } from '@afrotransact/ui';
import { usePathname, useRouter, useSearchParams } from 'next/navigation';

import { ModulePage } from '@/components/module/ModulePage';
import { ResourceTable } from '@/components/resources/ResourceTable';
import { useT } from '@/i18n/t';

/** Growth → Marketing: announcements, placements & ads, and platform-funded checkout coupons. */
export function MarketingView() {
  const t = useT();
  const router = useRouter();
  const pathname = usePathname();
  const params = useSearchParams();
  const tab = params.get('tab') ?? 'announcements';
  return (
    <ModulePage module="marketing">
      <Tabs
        label={t('st.marketing.tabs')}
        value={tab}
        onValueChange={(v) => router.replace(`${pathname}?tab=${encodeURIComponent(v)}`, { scroll: false })}
        items={[
          { value: 'announcements', label: t('st.marketing.tab.announcements'), content: <ResourceTable resource="announcements" urlPrefix="announcements" /> },
          { value: 'placements', label: t('st.marketing.tab.placements'), content: <ResourceTable resource="placements" urlPrefix="placements" /> },
          { value: 'coupons', label: t('st.marketing.tab.coupons'), content: <ResourceTable resource="coupons" urlPrefix="coupons" /> }
        ]}
      />
    </ModulePage>
  );
}
