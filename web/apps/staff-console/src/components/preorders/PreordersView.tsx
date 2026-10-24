'use client';

import { staffPreorderKeys, staffPreorderQueries } from '@afrotransact/api-client';
import { type PreorderListTab, PreorderListTabSchema } from '@afrotransact/api-client/staff-preorders';
import { useToast } from '@afrotransact/ui';
import { ArrowLeft, GearSix, Plus } from '@phosphor-icons/react/ssr';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import Link from 'next/link';
import { useRouter, useSearchParams } from 'next/navigation';

import { ModulePage } from '@/components/module/ModulePage';
import { problemMessage } from '@/components/ui/States';
import { useT } from '@/i18n/t';

import { href, NO_EDIT_TITLE, NO_SETTINGS_TITLE } from './model';
import { PreorderDetail } from './PreorderDetail';
import { PreorderList } from './PreorderList';
import { PreorderSettingsPanel } from './PreorderSettings';
import { PreorderWizard } from './PreorderWizard';
import { usePreorderAccess, usePreordersApi } from './primitives';

export type PreorderScreen = { kind: 'list' } | { kind: 'detail'; id: string } | { kind: 'new' } | { kind: 'edit'; id: string } | { kind: 'settings' };

/** The master switch as the design's toolbar button ("Pre-order on / off"); Full level only. */
function MasterSwitchButton() {
  const api = usePreordersApi();
  const access = usePreorderAccess();
  const qc = useQueryClient();
  const toast = useToast();
  const settings = useQuery(staffPreorderQueries.settings(api));
  const toggle = useMutation({
    mutationFn: () => {
      const s = settings.data;
      if (!s) throw new Error('Settings not loaded');
      const { version, ...rest } = s;
      return api.updateSettings({ ...rest, enabled: !s.enabled }, version);
    },
    onSuccess: (next) => {
      qc.setQueryData(staffPreorderKeys.settings(), next);
      void qc.invalidateQueries({ queryKey: staffPreorderKeys.all });
      toast.show(`Pre-order switched ${next.enabled ? 'on' : 'off'} for all cities`);
    },
    onError: (e) => toast.show(problemMessage(e), 'error')
  });
  const on = settings.data?.enabled ?? true;
  return (
    <button
      type="button"
      onClick={() => toggle.mutate()}
      disabled={!access.canSettings || !settings.data || toggle.isPending}
      title={access.canSettings ? 'Switch pre-order on or off everywhere' : NO_SETTINGS_TITLE}
      aria-pressed={on}
      className="flex h-10 cursor-pointer items-center gap-2 rounded-md border border-border-strong bg-surface px-3 text-sm font-semibold text-text disabled:cursor-not-allowed disabled:opacity-45"
    >
      <span aria-hidden className={`relative block h-5 w-[34px] rounded-pill ${on ? 'bg-green-600' : 'bg-ink-500'}`}>
        <span className={`absolute top-[3px] size-3.5 rounded-pill bg-ink-0 ${on ? 'left-[17px]' : 'left-[3px]'}`} />
      </span>
      Pre-order {on ? 'on' : 'off'}
    </button>
  );
}

function ListTabs({ tab }: { tab: PreorderListTab }) {
  const api = usePreordersApi();
  const t = useT();
  const counts = useQuery(staffPreorderQueries.list(api, { status: 'all', page: 0 })).data?.counts;
  const tabs: [PreorderListTab, string][] = [
    ['all', 'All'],
    ['open', 'Open'],
    ['upcoming', 'Scheduled'],
    ['decision', t('st.preorders.tab.decision')],
    ['closed', 'Closed'],
    ['draft', 'Drafts']
  ];
  return (
    <div role="tablist" aria-label="Pre-order status" className="flex max-w-full gap-0.5 overflow-x-auto overscroll-x-contain rounded-md border border-border bg-surface-muted p-0.5">
      {tabs.map(([k, label]) => (
        <Link
          key={k}
          role="tab"
          aria-selected={tab === k}
          href={href.list(k)}
          replace
          scroll={false}
          className={`flex h-8 shrink-0 items-center gap-1.5 rounded-sm px-3 text-sm font-semibold text-text no-underline hover:text-text hover:no-underline ${tab === k ? 'bg-surface shadow-sm' : ''}`}
        >
          {label}
          <span className="text-xs text-muted" data-testid={`tab-count-${k}`}>
            {counts ? counts[k] : '–'}
          </span>
        </Link>
      ))}
    </div>
  );
}

/** Pre-orders that closed below their minimum wait for a staff decision: say so on every list tab, and link to them. */
function DecisionNotice() {
  const api = usePreordersApi();
  const t = useT();
  const n = useQuery(staffPreorderQueries.list(api, { status: 'all', page: 0 })).data?.counts.decision ?? 0;
  if (!n) return null;
  return (
    <div role="status" data-testid="decision-notice" className="flex flex-wrap items-center gap-3 rounded-md border border-gold-500 bg-gold-50 px-4 py-3 text-sm">
      <strong>{t('st.preorders.banner.decision', { n })}</strong>
      <Link href={href.list('decision')} className="font-bold text-link">
        {t('st.preorders.banner.review')}
      </Link>
    </div>
  );
}

/** Back office › Pre-orders: list, detail, wizard and settings (the "Back office" tab of the Pre-Order v2 design). */
export function PreordersView({ screen }: { screen: PreorderScreen }) {
  const access = usePreorderAccess();
  const router = useRouter();
  const params = useSearchParams();
  const parsedTab = PreorderListTabSchema.safeParse(params.get('status') ?? 'all');
  const tab: PreorderListTab = parsedTab.success ? parsedTab.data : 'all';
  const inList = screen.kind === 'list';
  return (
    <ModulePage module="preorders">
      <p className="m-0 text-xs text-muted" data-testid="role-note">
        {access.roleNote}
      </p>
      <div className="flex flex-col gap-4">
        <div className="flex flex-wrap items-center gap-2">
          {inList ? (
            <ListTabs tab={tab} />
          ) : (
            <Link href={href.list()} className="flex h-10 items-center gap-2 rounded-md border border-border-strong bg-surface px-3 text-sm font-semibold text-text no-underline hover:text-text hover:no-underline">
              <ArrowLeft size={16} aria-hidden />
              All pre-orders
            </Link>
          )}
          <span className="flex-1" />
          <MasterSwitchButton />
          <Link
            href={href.settings()}
            aria-current={screen.kind === 'settings' ? 'page' : undefined}
            className="flex h-10 items-center gap-2 rounded-md border border-border-strong bg-surface px-3 text-sm font-semibold text-text no-underline hover:text-text hover:no-underline"
          >
            <GearSix size={16} aria-hidden />
            Areas &amp; settings
          </Link>
          <button
            type="button"
            onClick={() => access.canEdit && router.push(href.create())}
            disabled={!access.canEdit}
            title={access.canEdit ? undefined : NO_EDIT_TITLE}
            className="flex h-10 cursor-pointer items-center gap-2 rounded-md border-0 bg-primary px-4 text-sm font-bold text-on-primary disabled:cursor-not-allowed disabled:opacity-45"
          >
            <Plus size={16} aria-hidden />
            Open a pre-order
          </button>
        </div>
        {inList && tab !== 'decision' ? <DecisionNotice /> : null}
        {screen.kind === 'list' ? <PreorderList tab={tab} /> : null}
        {screen.kind !== 'list' ? (
          <section className="min-w-0 rounded-md border border-border bg-surface">
            {screen.kind === 'detail' ? <PreorderDetail id={screen.id} /> : null}
            {screen.kind === 'new' ? <PreorderWizard /> : null}
            {screen.kind === 'edit' ? <PreorderWizard id={screen.id} /> : null}
            {screen.kind === 'settings' ? <PreorderSettingsPanel /> : null}
          </section>
        ) : null}
      </div>
    </ModulePage>
  );
}
