'use client';

import { cx, PageStack } from '@afrotransact/ui';
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

/** Left sub-nav on desktop, a "Settings" link above the page on phones. Pages live under /settings/<section>. */
export function SettingsShell({ children }: { children: ReactNode }) {
  const t = useT();
  const pathname = usePathname();
  const { level, isProvider } = useSeller();
  const current = settingsSectionOf(pathname);
  const sections = visibleSections(isProvider, level);
  return (
    <div className="grid min-w-0 gap-x-8 gap-y-4 lg:grid-cols-[220px_minmax(0,1fr)]" data-testid="settings-shell">
      <nav aria-label={t('sc.settings.navLabel')} className="hidden lg:block" data-tour="settings-nav">
        <p className="m-0 pb-2 text-2xs font-bold tracking-[0.08em] text-muted uppercase">
          <Link href="/settings" className="text-muted no-underline hover:text-text hover:no-underline" aria-current={current === null ? 'page' : undefined}>
            {t('sc.settings.title')}
          </Link>
        </p>
        <ul className="m-0 flex list-none flex-col gap-0.5 p-0">
          {sections.map((s) => (
            <li key={s.key}>
              <Link
                href={s.href}
                aria-current={current === s.key ? 'page' : undefined}
                className={cx(
                  'flex min-h-9 items-center rounded-sm px-3 text-md text-text no-underline hover:bg-surface-hover hover:text-text hover:no-underline',
                  current === s.key ? 'bg-gold-100 font-bold hover:bg-gold-100' : 'font-normal'
                )}
              >
                {t(`sc.settings.${s.key}.title` as SectionCopy)}
              </Link>
            </li>
          ))}
        </ul>
      </nav>
      <div className="flex min-w-0 flex-col gap-(--at-page-gap-header)">
        {current !== null ? (
          <Link href="/settings" className="inline-flex min-h-11 items-center gap-1 self-start text-sm text-link underline lg:hidden">
            <CaretLeft size={14} aria-hidden className="rtl:rotate-180" /> {t('sc.settings.title')}
          </Link>
        ) : null}
        {children}
      </div>
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
