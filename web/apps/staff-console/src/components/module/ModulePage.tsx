'use client';

import { type StaffModuleKey } from '@afrotransact/api-client';
import { createContext, type ReactNode, useContext } from 'react';

import { Notice } from '@/components/ui/Notice';
import { PageHeader } from '@/components/ui/PageHeader';
import { useT } from '@/i18n/t';
import { useStaff } from '@/lib/session';

/** True inside Settings, whose own header and section title replace each module page's. */
export const EmbeddedInSettings = createContext(false);

/** Page header from the module (label and line under it come with the session), plus the read-only notice. */
export function ModulePage({ module, actions, children }: { module: StaffModuleKey; actions?: ReactNode; children: ReactNode }) {
  const t = useT();
  const { module: navModule, session } = useStaff();
  const m = navModule(module);
  const embedded = useContext(EmbeddedInSettings);
  if (embedded) {
    return (
      <>
        {actions ? <div className="flex flex-wrap items-center justify-end gap-2">{actions}</div> : null}
        {children}
      </>
    );
  }
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
