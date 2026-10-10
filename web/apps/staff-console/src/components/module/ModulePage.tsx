'use client';

import { type StaffModuleKey } from '@afrotransact/api-client';
import { type ReactNode } from 'react';

import { Notice } from '@/components/ui/Notice';
import { PageHeader } from '@/components/ui/PageHeader';
import { useT } from '@/i18n/t';
import { useStaff } from '@/lib/session';

/** Page header from the module (label and line under it come with the session), plus the read-only notice. */
export function ModulePage({ module, actions, children }: { module: StaffModuleKey; actions?: ReactNode; children: ReactNode }) {
  const t = useT();
  const { module: navModule, session } = useStaff();
  const m = navModule(module);
  return (
    <>
      <PageHeader title={m?.label ?? module} description={m?.description} actions={actions} />
      {m?.level === 'VIEW' ? (
        <Notice tone="info" icon="eye" title={t('st.modulePage.readOnly')}>
          {session.roleLabel} can view {m.label.toLowerCase()} but not change it.
        </Notice>
      ) : null}
      {children}
    </>
  );
}
