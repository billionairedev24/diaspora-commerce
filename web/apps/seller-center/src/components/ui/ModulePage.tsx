'use client';

import { type ModuleKey } from '@afrotransact/api-client';
import { type ReactNode } from 'react';

import { useT } from '@/i18n/t';
import { moduleCopy } from '@/lib/modules';
import { useSeller } from '@/lib/session';

import { Notice } from './Notice';
import { PageHeader } from './PageHeader';

/** Page header from the module copy, plus the design's "Read-only." notice for roles with view access only. */
export function ModulePage({ module, actions, description, children, readOnlyNote = true }: { module: ModuleKey; actions?: ReactNode; description?: ReactNode; children: ReactNode; readOnlyNote?: boolean }) {
  const t = useT();
  const { isProvider, session, level } = useSeller();
  const copy = moduleCopy(module, { provider: isProvider, orgName: session.activeOrg?.name ?? '' }, t);
  const lv = level(module);
  return (
    <>
      <PageHeader title={copy.title} description={description ?? copy.description} actions={actions} />
      {readOnlyNote && lv === 'VIEW' ? (
        <Notice tone="info" icon="eye" title={t('sc.modulePage.readOnly')}>
          {session.activeOrg?.roleLabel} can view {copy.title.toLowerCase()} but not change it.
        </Notice>
      ) : null}
      {children}
    </>
  );
}
