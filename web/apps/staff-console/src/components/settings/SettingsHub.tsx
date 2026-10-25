'use client';

import { Select } from '@afrotransact/ui';
import { CaretRight, Gear } from '@phosphor-icons/react/ssr';
import Link from 'next/link';
import { useRouter, useSearchParams } from 'next/navigation';
import { type ReactNode } from 'react';

import { DeliveryFeesView } from '@/components/fees/DeliveryFeesView';
import { FeesView } from '@/components/fees/FeesView';
import { IdentityAdminView } from '@/components/identity/IdentityAdminView';
import { MarketsView } from '@/components/markets/MarketsView';
import { EmbeddedInSettings } from '@/components/module/ModulePage';
import { ModuleView } from '@/components/module/ModuleView';
import { NotificationsView } from '@/components/notifications/NotificationsView';
import { SettingsPanel as PlanSettingsPanel } from '@/components/plans/SettingsPanel';
import { ResourceTable } from '@/components/resources/ResourceTable';
import { EconomicsSettingsView } from '@/components/runs/EconomicsSettingsView';
import { Notice } from '@/components/ui/Notice';
import { PageHeader } from '@/components/ui/PageHeader';
import { useT } from '@/i18n/t';
import { useStaff } from '@/lib/session';
import { SETTINGS_GROUPS, SETTINGS_SECTIONS, settingsHref, type SettingsSection, visibleSections } from '@/lib/settings-sections';

function SectionBody({ section }: { section: SettingsSection }): ReactNode {
  const t = useT();
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
    case 'delivery-fees':
      return <DeliveryFeesView />;
    case 'run-economics':
      return <EconomicsSettingsView />;
    case 'tax':
      return <FeesView scope="tax" />;
    case 'exchange-rates':
      return (
        <div className="flex flex-col gap-3">
          <p className="m-0 max-w-[80ch] text-sm text-muted">{t('st.rates.intro')}</p>
          <ResourceTable resource="exchange-rates" urlPrefix="exchange-rates" />
        </div>
      );
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
 * Settings: one place for everything that sets how AfroTransact runs. With no section chosen it is a hub of grouped cards;
 * choosing one opens it full width under a breadcrumb, with a switcher to jump to another. There is no second sidebar:
 * the console's own navigation is the only rail. Each section is an existing console module, so who may see or change it
 * is still decided by their access to that module.
 */
export function SettingsHub() {
  const t = useT();
  const { session, module: navModule } = useStaff();
  const params = useSearchParams();
  const router = useRouter();
  const sections = visibleSections(session);
  const current = sections.find((s) => s.key === params.get('section'));
  if (!current) {
    return (
      <>
        <PageHeader title="Settings" description="Everything that sets how AfroTransact runs, in one place. Pick a section." />
        <div className="flex flex-col gap-6">
          {SETTINGS_GROUPS.map((g) => {
            const items = sections.filter((s) => s.group === g.key);
            if (items.length === 0) return null;
            return (
              <section key={g.key} aria-label={g.label} className="flex flex-col gap-2">
                <h2 className="m-0 text-2xs font-bold tracking-[0.08em] text-muted uppercase">{g.label}</h2>
                <ul className="m-0 grid list-none grid-cols-[repeat(auto-fill,minmax(min(100%,280px),1fr))] gap-3 p-0">
                  {items.map((s) => (
                    <li key={s.key}>
                      <Link
                        href={settingsHref(s.key)}
                        className="flex h-full items-start justify-between gap-3 rounded-md border border-border p-4 text-text no-underline hover:bg-surface-hover hover:text-text hover:no-underline"
                      >
                        <span className="flex min-w-0 flex-col gap-1">
                          <span className="text-md font-bold">{s.labelKey ? t(s.labelKey) : s.label}</span>
                          <span className="text-sm text-muted">{s.descriptionKey ? t(s.descriptionKey) : s.description}</span>
                        </span>
                        <CaretRight size={16} aria-hidden className="mt-1 shrink-0 text-muted" />
                      </Link>
                    </li>
                  ))}
                </ul>
              </section>
            );
          })}
        </div>
      </>
    );
  }
  const level = navModule(current.module)?.level;
  return (
    <>
      <nav aria-label="Breadcrumb" className="mb-2 flex items-center gap-1 text-sm text-muted">
        <Link href="/settings">Settings</Link>
        <CaretRight size={12} aria-hidden />
        <span aria-current="page" className="text-text">{current.labelKey ? t(current.labelKey) : current.label}</span>
      </nav>
      <section aria-labelledby="settings-section-title" className="flex min-w-0 flex-col gap-4">
        <header className="flex flex-wrap items-start justify-between gap-3 border-b border-border pb-3">
          <div className="flex min-w-0 flex-col gap-1">
            <h1 id="settings-section-title" className="m-0 flex items-center gap-2 text-2xl font-bold">
              <Gear size={22} aria-hidden />
              {current.labelKey ? t(current.labelKey) : current.label}
            </h1>
            <p className="m-0 max-w-[80ch] text-sm text-muted">{current.descriptionKey ? t(current.descriptionKey) : current.description}</p>
          </div>
          <Select
            id="settings-switch"
            label="Go to another setting"
            value={current.key}
            onChange={(e) => router.push(settingsHref(e.currentTarget.value))}
            options={sections.map((s) => ({ value: s.key, label: s.labelKey ? t(s.labelKey) : s.label }))}
          />
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
    </>
  );
}

export const SETTINGS_SECTION_KEYS = SETTINGS_SECTIONS.map((s) => s.key);
