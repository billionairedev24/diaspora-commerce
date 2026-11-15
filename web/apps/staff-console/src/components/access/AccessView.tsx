'use client';

import { Tabs } from '@afrotransact/ui';

import { Notice } from '@/components/ui/Notice';
import { useT } from '@/i18n/t';

import { LogPanel } from './LogPanel';
import { ModuleMatrixPanel } from './ModuleMatrixPanel';
import { PeoplePanel } from './PeoplePanel';
import { RolesPanel } from './RolesPanel';
import { type AccessView as View, useAccessNav } from './useAccessNav';

/**
 * Settings > Roles and permissions. Four tabs: Roles (by portal, with permissions grouped by module and a switch for each), People
 * (what a person can do and where it comes from, with direct grants), Module access (what each role may do in each module) and Change log. Identity owns the data and is the final
 * judge: viewing needs access:read, changing needs access:admin and a reason. Nothing is hidden from Super Admin.
 */
export function AccessView() {
  const t = useT();
  const nav = useAccessNav();
  return (
    <div className="flex flex-col gap-4" data-testid="access-view">
      <Notice tone="info" title={t('st.acc.delay.title')}>
        {t('st.acc.delay.body')}
      </Notice>
      <Tabs
        label={t('st.acc.tabs')}
        value={nav.view}
        onValueChange={(v) => nav.go({ view: v === 'roles' ? null : v, role: null, person: null, portal: v === 'roles' ? nav.portal : null, mportal: v === 'modules' ? nav.mportal : null }, false)}
        items={[
          { value: 'roles' satisfies View, label: t('st.acc.tab.roles'), content: <RolesPanel /> },
          { value: 'people' satisfies View, label: t('st.acc.tab.people'), content: <PeoplePanel /> },
          { value: 'modules' satisfies View, label: t('st.acc.tab.modules'), content: <ModuleMatrixPanel /> },
          { value: 'log' satisfies View, label: t('st.acc.tab.log'), content: <LogPanel /> }
        ]}
      />
    </div>
  );
}
