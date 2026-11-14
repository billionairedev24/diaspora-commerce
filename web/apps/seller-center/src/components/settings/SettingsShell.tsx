'use client';

import { PageStack } from '@afrotransact/ui';
import { CaretLeft } from '@phosphor-icons/react/ssr';
import Link from 'next/link';
import { usePathname } from 'next/navigation';
import { type ReactNode } from 'react';

import { Notice } from '@/components/ui/Notice';
import { PageHeader } from '@/components/ui/PageHeader';
import { NoAccess } from '@/components/ui/States';
import { type MessageKey, useT } from '@/i18n/t';
import { useSeller } from '@/lib/session';
import { canEditSection, sectionOf, type SettingsKey, settingsSectionOf, visibleSections } from '@/lib/settings-sections';

type SectionCopy = Extract<MessageKey, `sc.settings.${SettingsKey}.${'title' | 'description'}`>;

export function useSectionCopy(key: SettingsKey): { title: string; description: string } {
  const t = useT();
  return { title: t(`sc.settings.${key}.title` as SectionCopy), description: t(`sc.settings.${key}.description` as SectionCopy) };
}

/** What this member may do in a section: open it (View on its module) and save in it (Edit). */
export function useSectionAccess(key: SettingsKey): { canView: boolean; canEdit: boolean } {
  const { level, isProvider } = useSeller();
  const s = sectionOf(key);
  const canView = visibleSections(isProvider, level).some((v) => v.key === s.key);
  return { canView, canEdit: canView && canEditSection(s, level) };
}

/**
 * One content column. The Settings home is the list of sections; a section page has a "Settings" link above it to go back.
 * No persistent sub-nav column (the app already has the sidebar): sections stay their own routes with their own permissions.
 */
export function SettingsShell({ children }: { children: ReactNode }) {
  const t = useT();
  const current = settingsSectionOf(usePathname());
  return (
    <div className="flex min-w-0 flex-col gap-(--at-page-gap-header)" data-testid="settings-shell">
      {current !== null ? (
        <Link href="/settings" className="inline-flex min-h-11 items-center gap-1 self-start text-sm text-link underline" data-testid="settings-back">
          <CaretLeft size={14} aria-hidden className="rtl:rotate-180" /> {t('sc.settings.title')}
        </Link>
      ) : null}
      {children}
    </div>
  );
}

/** One settings page: its title and one-line description, the read-only note for members who may only look, then the body. */
export function SettingsPage({ section, actions, children }: { section: SettingsKey; actions?: ReactNode; children: ReactNode }) {
  const t = useT();
  const { session } = useSeller();
  const copy = useSectionCopy(section);
  const { canView, canEdit } = useSectionAccess(section);
  if (!canView) return <NoAccess role={session.activeOrg?.roleLabel ?? ''} />;
  return (
    <>
      <PageHeader title={copy.title} description={copy.description} actions={canEdit ? actions : null} tour={`page-settings-${section}`} />
      <PageStack>
        {!canEdit ? (
          <Notice tone="info" icon="eye" title={t('sc.modulePage.readOnly')}>
            {t('sc.settings.readOnlyBody', { role: session.activeOrg?.roleLabel ?? '', section: copy.title.toLowerCase() })}
          </Notice>
        ) : null}
        {children}
      </PageStack>
    </>
  );
}
