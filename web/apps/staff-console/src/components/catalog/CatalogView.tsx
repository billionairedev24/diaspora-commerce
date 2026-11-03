'use client';

import { staffTaxonomyQueries } from '@afrotransact/api-client';
import { Tabs } from '@afrotransact/ui';
import { useQuery } from '@tanstack/react-query';
import { usePathname, useRouter, useSearchParams } from 'next/navigation';

import { ModulePage } from '@/components/module/ModulePage';
import { ResourceTable } from '@/components/resources/ResourceTable';
import { useT } from '@/i18n/t';
import { staffApi } from '@/lib/api';
import { MODULE_TABLES } from '@/lib/modules';
import { useStaff } from '@/lib/session';

import { BrandsView } from './BrandsView';
import { CategoriesView } from './CategoriesView';

/** The tab that shows the category tree (it is not a table of the records service). */
export const CATEGORIES_TAB = 'categories';
/** The brand registry: the review queue of brands sellers typed in, and the approved list. */
export const BRANDS_TAB = 'brands';
const TAXONOMY = 'catalog:taxonomy';

/**
 * Catalog: the moderation queue, product reviews and products, and, for staff who may manage the taxonomy
 * (catalog:taxonomy), the category tree, the brand registry and the queue of sellers' category requests with the number waiting.
 */
export function CatalogView() {
  const t = useT();
  const { hasPermission } = useStaff();
  const router = useRouter();
  const pathname = usePathname();
  const params = useSearchParams();
  const taxonomy = hasPermission(TAXONOMY);
  const pending = useQuery({ ...staffTaxonomyQueries.tree(staffApi().taxonomy), enabled: taxonomy });
  const waiting = pending.data?.pendingRequests ?? 0;
  const brandQueue = useQuery({ ...staffTaxonomyQueries.brands(staffApi().taxonomy, { status: ['PENDING'], limit: 1 }), enabled: taxonomy });
  const brandsWaiting = brandQueue.data?.total ?? 0;
  const brandsTab = { value: BRANDS_TAB, label: brandsWaiting > 0 ? t('st.catalog.tab.brandsCount', { n: brandsWaiting }) : t('st.catalog.tab.brands'), content: <BrandsView /> };
  const tables = MODULE_TABLES.catalog ?? [];

  const items = tables
    .filter((x) => taxonomy || x.resource !== 'category-requests')
    .flatMap((x) => {
      const table = {
        value: x.resource,
        label: x.resource === 'category-requests' && waiting > 0 ? t('st.catalog.tab.requestsCount', { n: waiting }) : x.label,
        content: <ResourceTable resource={x.resource} urlPrefix={x.resource} statusFilter={x.statusFilter} />
      };
      // The tree and the brand registry sit right before the requests queue they feed.
      return x.resource === 'category-requests' ? [{ value: CATEGORIES_TAB, label: t('st.catalog.tab.categories'), content: <CategoriesView /> }, brandsTab, table] : [table];
    });
  if (taxonomy && !items.some((i) => i.value === CATEGORIES_TAB)) items.push({ value: CATEGORIES_TAB, label: t('st.catalog.tab.categories'), content: <CategoriesView /> }, brandsTab);

  const requested = params.get('tab');
  const tab = items.some((i) => i.value === requested) ? (requested as string) : (items[0]?.value ?? '');
  return (
    <ModulePage module="catalog">
      <Tabs
        label={t('st.moduleView.tables')}
        value={tab}
        onValueChange={(v) => router.replace(`${pathname}?${new URLSearchParams([['tab', v]]).toString()}`, { scroll: false })}
        items={items}
      />
    </ModulePage>
  );
}
