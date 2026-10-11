'use client';

import { type StaffModuleKey } from '@afrotransact/api-client';
import { Tabs } from '@afrotransact/ui';
import { usePathname, useRouter, useSearchParams } from 'next/navigation';

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
  const tab = params.get('tab') ?? tables[0]?.resource ?? '';
  const only = tables.length === 1 ? tables[0] : undefined;
  return (
    <ModulePage module={module}>
      {only ? (
        <ResourceTable resource={only.resource} />
      ) : (
        <Tabs
          label={t('st.moduleView.tables')}
          value={tab}
          onValueChange={(v) => router.replace(`${pathname}?tab=${encodeURIComponent(v)}`, { scroll: false })}
          items={tables.map((t) => ({ value: t.resource, label: t.label, content: <ResourceTable resource={t.resource} urlPrefix={t.resource} /> }))}
        />
      )}
    </ModulePage>
  );
}
