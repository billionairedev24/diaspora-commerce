'use client';

import { type ModuleKey, type NavGroup, type OrgSummary, type SellerSession } from '@afrotransact/api-client';
import { cx, Menu, MenuGroup, MenuItem } from '@afrotransact/ui';
import { CaretDown, CaretUp, CaretUpDown, Check } from '@phosphor-icons/react/ssr';
import Link from 'next/link';
import { useState } from 'react';

import { useT } from '@/i18n/t';
import { MODULE_ICONS } from '@/lib/modules';
import { hrefOf } from '@/lib/routes';

function orgSub(o: OrgSummary): string {
  const kind = o.kind === 'PROVIDER' ? 'Service provider' : 'Seller';
  return o.approved ? `${kind} · ${o.roleLabel}` : `${kind} · application ${o.status === 'INFO_REQUESTED' ? 'needs info' : 'in review'}`;
}

/** Org switcher: every seller and provider organisation of the member (sellers vs providers vs both). */
export function OrgSwitcher({ session, onSwitch, busy }: { session: SellerSession; onSwitch: (orgId: string) => void; busy: boolean }) {
  const t = useT();
  const active = session.activeOrg;
  if (!active) return null;
  const sellers = session.orgs.filter((o) => o.kind === 'SELLER');
  const providers = session.orgs.filter((o) => o.kind === 'PROVIDER');
  const trigger = (
    <button
      type="button"
      aria-label={`Switch organisation (current: ${active.name})`}
      aria-busy={busy || undefined}
      className="flex w-full items-center gap-2 rounded-sm px-2 py-2 text-left hover:bg-surface-hover"
    >
      <span className="flex min-w-0 flex-1 flex-col">
        <strong className="truncate text-md">{active.name}</strong>
        <span className="truncate text-xs text-muted">{orgSub(active)}</span>
      </span>
      {session.orgs.length > 1 ? <CaretUpDown size={16} aria-hidden className="flex-none text-muted" /> : null}
    </button>
  );
  if (session.orgs.length < 2) return trigger;
  const item = (o: OrgSummary) => (
    <MenuItem key={o.id} icon={o.id === active.id ? <Check size={16} aria-hidden /> : <span className="inline-block w-4" />} onSelect={() => onSwitch(o.id)}>
      <span className="flex flex-col">
        <span>{o.name}</span>
        <span className="text-xs text-muted">{orgSub(o)}</span>
      </span>
    </MenuItem>
  );
  return (
    <Menu trigger={trigger} align="start" width={260} label={t('sc.sidebar.organisations')}>
      {sellers.length ? (
        <MenuGroup label={t('sc.sidebar.sellerCenter')}>
          <div className="px-3 pt-1 pb-0.5 text-2xs font-bold tracking-[0.08em] text-muted uppercase">{t('sc.sidebar.sellerCenter')}</div>
          {sellers.map(item)}
        </MenuGroup>
      ) : null}
      {providers.length ? (
        <MenuGroup label={t('sc.sidebar.providerHub')}>
          <div className="px-3 pt-1 pb-0.5 text-2xs font-bold tracking-[0.08em] text-muted uppercase">{t('sc.sidebar.providerHub')}</div>
          {providers.map(item)}
        </MenuGroup>
      ) : null}
    </Menu>
  );
}

function NavGroupSection({ group, active, single, onNavigate }: { group: NavGroup; active: ModuleKey | null; single: boolean; onNavigate?: () => void }) {
  const t = useT();
  const holdsActive = group.modules.some((m) => m.key === active);
  // Collapsed by default except the group holding the current page; every group folds on click.
  const [openState, setOpen] = useState<boolean | null>(null);
  const open = single || (openState ?? holdsActive);
  const hasBadge = group.modules.some((m) => (m.badge ?? 0) > 0);
  const listId = `nav-${group.key}`;
  return (
    <div className="flex flex-col gap-0.5 pb-2">
      {single ? null : (
        <button
          type="button"
          aria-expanded={open}
          aria-controls={listId}
          onClick={() => setOpen(!open)}
          className="flex h-8 items-center gap-2 rounded-sm px-2 text-left text-2xs font-bold tracking-[0.08em] text-muted uppercase hover:bg-surface-hover"
        >
          <span className="flex-1">{group.label}</span>
          {!open && hasBadge ? <span aria-label={t('sc.sidebar.needsAttention')} className="size-1.5 rounded-pill bg-gold-500" /> : null}
          {open ? <CaretUp size={12} aria-hidden /> : <CaretDown size={12} aria-hidden />}
        </button>
      )}
      <ul id={listId} hidden={!open} className="m-0 flex list-none flex-col gap-0.5 p-0">
        {group.modules.map((m) => {
          const Icon = MODULE_ICONS[m.key];
          const current = m.key === active;
          return (
            <li key={m.key}>
              <Link
                href={hrefOf(m.key)}
                onClick={onNavigate}
                aria-current={current ? 'page' : undefined}
                className={cx(
                  'flex h-[34px] items-center gap-3 rounded-sm pr-3 pl-4 text-md text-text no-underline hover:bg-surface-hover hover:text-text hover:no-underline',
                  current ? 'bg-gold-100 font-bold hover:bg-gold-100' : 'font-normal'
                )}
              >
                <Icon size={18} aria-hidden weight={current ? 'fill' : 'regular'} />
                <span className="flex-1 truncate">{m.label}</span>
                {m.badge ? <span className="min-w-5 rounded-pill bg-gold-500 px-1.5 text-center text-2xs font-bold text-ink-1000">{m.badge}</span> : null}
              </Link>
            </li>
          );
        })}
      </ul>
    </div>
  );
}

/** Grouped sidebar: collapsible groups, role-aware entries, org switcher on top, access note at the bottom. */
export function Sidebar({ session, active, onSwitch, switching, onNavigate }: { session: SellerSession; active: ModuleKey | null; onSwitch: (orgId: string) => void; switching: boolean; onNavigate?: () => void }) {
  const t = useT();
  const single = session.navGroups.length === 1;
  return (
    <nav aria-label={t('sc.sidebar.backOffice')} className="flex h-full flex-col gap-0.5 px-3 py-4">
      <div className="pb-3">
        <OrgSwitcher session={session} onSwitch={onSwitch} busy={switching} />
      </div>
      {session.navGroups.map((g) => (
        <NavGroupSection key={`${session.activeOrg?.id}-${g.key}`} group={g} active={active} single={single} onNavigate={onNavigate} />
      ))}
      <p className="mt-auto px-2 pt-3 text-2xs leading-normal text-subtle">
        {t('sc.sidebar.access')} <strong>{session.activeOrg?.roleLabel ?? '—'}</strong>
        <br />
        {session.accessNote}
      </p>
    </nav>
  );
}
