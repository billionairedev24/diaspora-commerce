'use client';

import { PageStack } from '@afrotransact/ui';
import { CaretLeft } from '@phosphor-icons/react/ssr';
import Link from 'next/link';
import { usePathname } from 'next/navigation';
import { type ReactNode } from 'react';

import { EmbeddedInSettings } from '@/components/module/ModulePage';
import { Notice } from '@/components/ui/Notice';
import { PageHeader } from '@/components/ui/PageHeader';
import { NoAccess } from '@/components/ui/States';
import { type MessageKey, useT } from '@/i18n/t';
import { useStaff } from '@/lib/session';
import { canEditSection, canOpenSection, sectionOf, type SettingsKey, settingsSectionOf } from '@/lib/settings-sections';

/** A section's title and its one line, from the catalogue (all five languages). */
export function useSectionCopy(key: SettingsKey): { title: string; description: string } {
  const t = useT();
  return { title: t(`st.sx.${key}.title` as MessageKey), description: t(`st.sx.${key}.description` as MessageKey) };
}

/** What this staff member may do in a section: open it (View on its module) and save in it (Edit). */
export function useSectionAccess(key: SettingsKey): { canView: boolean; canEdit: boolean } {
  const { session } = useStaff();
  const s = sectionOf(key);
  return { canView: canOpenSection(session, s), canEdit: canEditSection(session, s) };
}

/**
 * One content column. The Settings home is the list of sections; a section page has a "Settings" link above it to go back.
 * No persistent sub-nav column (the console's sidebar is the only navigation beside it): sections stay their own routes,
 * each with its own permission.
 */
export function SettingsShell({ children }: { children: ReactNode }) {
  const t = useT();
  const current = settingsSectionOf(usePathname());
  return (
    <div className="flex min-w-0 flex-col gap-(--at-page-gap-header)" data-testid="settings-shell">
      {current !== null ? (
        <Link href="/settings" className="inline-flex min-h-11 items-center gap-1 self-start text-sm text-link underline" data-testid="settings-back">
          <CaretLeft size={14} aria-hidden className="rtl:rotate-180" /> {t('st.sx.title')}
        </Link>
      ) : null}
      {children}
    </div>
  );
}

/**
 * One settings page: its title and one-line description, the read-only note for staff who may only look, then the body.
 * Bodies that are console modules (their own tables and forms) render embedded: the module's header gives way to this one.
 */
export function SettingsPage({ section, actions, children }: { section: SettingsKey; actions?: ReactNode; children: ReactNode }) {
  const t = useT();
  const { session } = useStaff();
  const copy = useSectionCopy(section);
  const { canView, canEdit } = useSectionAccess(section);
  if (!canView) return <NoAccess roles={session.roleLabel} />;
  return (
    <>
      <PageHeader title={copy.title} description={copy.description} actions={canEdit ? actions : null} tour={`page-settings-${section}`} />
      <PageStack>
        {!canEdit ? (
          <Notice tone="info" icon="eye" title={t('st.sx.readOnly.title')}>
            {t('st.sx.readOnly.body', { role: session.roleLabel, section: copy.title.toLowerCase() })}
          </Notice>
        ) : null}
        <div data-tour="settings-section" className="flex min-w-0 flex-col gap-4">
          <EmbeddedInSettings.Provider value>{children}</EmbeddedInSettings.Provider>
        </div>
      </PageStack>
    </>
  );
}
