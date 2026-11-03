'use client';

import { type StaffModuleKey } from '@afrotransact/api-client';
import { Tabs } from '@afrotransact/ui';
import { usePathname, useRouter, useSearchParams } from 'next/navigation';

import { PublishedPagesView } from '@/components/pages/PublishedPagesView';
import { ResourceTable } from '@/components/resources/ResourceTable';
import { useT } from '@/i18n/t';
import { MODULE_TABLES } from '@/lib/modules';

import { ModulePage } from './ModulePage';

/** A module page: one server-driven table, or tabs when the module has several (claims and returns, people and roles…). */
export function ModuleView({ module }: { module: StaffModuleKey }) {
  const t = useT();
  const tables = MODULE_TABLES[module] ?? [];
  const router = useRouter();
  const pathname = usePathname();
  const params = useSearchParams();
  // Site content & legal opens on the index of every public page; its other tables are the records behind them.
  const pagesTab = module === 'siteCfg';
  const tab = params.get('tab') ?? (pagesTab ? 'published-pages' : (tables[0]?.resource ?? ''));
  const only = tables.length === 1 ? tables[0] : undefined;
  return (
    <ModulePage module={module}>
      {only ? (
        <ResourceTable resource={only.resource} />
      ) : (
        <Tabs
          label={t('st.moduleView.tables')}
          value={tab}
          onValueChange={(v) => router.replace(`${pathname}?${new URLSearchParams([...params.entries()].filter(([k]) => k === 'section').concat([['tab', v]])).toString()}`, { scroll: false })}
          items={[...(pagesTab ? [{ value: 'published-pages', label: t('st.settings.pages.label'), content: <PublishedPagesView /> }] : []), ...tables.map((table) => ({ value: table.resource, label: table.labelKey ? t(table.labelKey) : table.label, content: <ResourceTable resource={table.resource} urlPrefix={table.resource} statusFilter={table.statusFilter} /> }))]}
        />
      )}
    </ModulePage>
  );
}
