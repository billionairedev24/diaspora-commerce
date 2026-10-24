'use client';

import { cx } from '@afrotransact/ui';
import { Gear } from '@phosphor-icons/react/ssr';
import Link from 'next/link';
import { useSearchParams } from 'next/navigation';
import { type ReactNode } from 'react';

import { FeesView } from '@/components/fees/FeesView';
import { IdentityAdminView } from '@/components/identity/IdentityAdminView';
import { MarketsView } from '@/components/markets/MarketsView';
import { EmbeddedInSettings } from '@/components/module/ModulePage';
import { ModuleView } from '@/components/module/ModuleView';
import { NotificationsView } from '@/components/notifications/NotificationsView';
import { SettingsPanel as PlanSettingsPanel } from '@/components/plans/SettingsPanel';
import { ResourceTable } from '@/components/resources/ResourceTable';
import { Notice } from '@/components/ui/Notice';
import { PageHeader } from '@/components/ui/PageHeader';
import { useStaff } from '@/lib/session';
import { SETTINGS_GROUPS, SETTINGS_SECTIONS, settingsHref, type SettingsSection, visibleSections } from '@/lib/settings-sections';

function SectionBody({ section }: { section: SettingsSection }): ReactNode {
  switch (section.key) {
    case 'general':
      return <ModuleView module="siteCfg" />;
    case 'markets':
      return <MarketsView />;
    case 'notifications':
      return <NotificationsView />;
    case 'signin':
      return <ModuleView module="dev" />;
    case 'identity':
      return <IdentityAdminView />;
    case 'payments':
      return (
        <div className="flex flex-col gap-3">
          <p className="m-0 max-w-[80ch] text-sm text-muted">
            These come from the deployment&rsquo;s configuration; open a row to see what it does and which setting changes it. Approving, holding and releasing payouts is in{' '}
            <Link href="/payouts">Payouts &amp; ledger</Link>.
          </p>
          <ResourceTable resource="payment-settings" urlPrefix="payment-settings" />
        </div>
      );
    case 'fees':
      return <FeesView scope="fees" />;
    case 'tax':
      return <FeesView scope="tax" />;
    case 'plans':
      return (
        <div className="flex flex-col gap-4">
          <PlanSettingsPanel />
          <p className="m-0 text-sm text-muted">
            The plan catalogue, trials, rewards and each seller&rsquo;s billing are in <Link href="/plans">Plans &amp; billing</Link>.
          </p>
        </div>
      );
    default:
      return null;
  }
}

/**
 * Settings: one place for everything that sets how AfroTransact runs. A rail of sections on the left (a row of chips on a
 * phone) and the chosen section on the right. Each section is an existing console module, so who may see or change it is
 * still decided by their access to that module.
 */
export function SettingsHub() {
  const { session, module: navModule } = useStaff();
  const params = useSearchParams();
  const sections = visibleSections(session);
  const current = sections.find((s) => s.key === params.get('section')) ?? sections[0];
  if (!current) return null;
  const level = navModule(current.module)?.level;
  return (
    <>
      <PageHeader title="Settings" description="Everything that sets how AfroTransact runs, in one place. Pick a section." />
      <div className="grid gap-6 lg:grid-cols-[232px_minmax(0,1fr)]">
        <nav aria-label="Settings sections" className="flex flex-col gap-3 lg:sticky lg:top-4 lg:self-start">
          {SETTINGS_GROUPS.map((g) => {
            const items = sections.filter((s) => s.group === g.key);
            if (items.length === 0) return null;
            return (
              <div key={g.key} className="flex flex-col gap-1">
                <span className="px-2 text-2xs font-bold tracking-[0.08em] text-muted uppercase">{g.label}</span>
                <ul className="m-0 flex list-none gap-1 overflow-x-auto p-0 lg:flex-col lg:overflow-visible">
                  {items.map((s) => {
                    const active = s.key === current.key;
                    return (
                      <li key={s.key} className="shrink-0">
                        <Link
                          href={settingsHref(s.key)}
                          aria-current={active ? 'page' : undefined}
                          className={cx(
                            'flex h-9 items-center rounded-sm px-3 text-md whitespace-nowrap text-text no-underline hover:bg-surface-hover hover:text-text hover:no-underline pointer-coarse:h-11',
                            active ? 'bg-gold-100 font-bold hover:bg-gold-100' : 'font-normal'
                          )}
                        >
                          {s.label}
                        </Link>
                      </li>
                    );
                  })}
                </ul>
              </div>
            );
          })}
        </nav>
        <section aria-labelledby="settings-section-title" className="flex min-w-0 flex-col gap-4">
          <header className="flex flex-col gap-1 border-b border-border pb-3">
            <h2 id="settings-section-title" className="m-0 flex items-center gap-2 text-xl font-bold">
              <Gear size={20} aria-hidden />
              {current.label}
            </h2>
            <p className="m-0 max-w-[80ch] text-sm text-muted">{current.description}</p>
          </header>
          {level === 'VIEW' ? (
            <Notice tone="info" icon="eye" title="Read only">
              {session.roleLabel} can view these settings but not change them.
            </Notice>
          ) : null}
          <EmbeddedInSettings.Provider value>
            <SectionBody key={current.key} section={current} />
          </EmbeddedInSettings.Provider>
        </section>
      </div>
    </>
  );
}

export const SETTINGS_SECTION_KEYS = SETTINGS_SECTIONS.map((s) => s.key);
