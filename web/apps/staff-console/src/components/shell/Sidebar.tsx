'use client';

import { type StaffModuleKey, type StaffNavGroup, type StaffSession } from '@afrotransact/api-client';
import { cx } from '@afrotransact/ui';
import { CaretDown, CaretUp } from '@phosphor-icons/react/ssr';
import Link from 'next/link';
import { useState } from 'react';

import { useT } from '@/i18n/t';
import { hrefOf, MODULE_ICONS } from '@/lib/modules';

function NavGroupSection({ group, active }: { group: StaffNavGroup; active: StaffModuleKey | null }) {
  const t = useT();
  const holdsActive = group.modules.some((m) => m.key === active);
  // Collapsed by default except the group holding the current page (Overview with the dashboard); every group folds.
  const [openState, setOpen] = useState<boolean | null>(null);
  const open = openState ?? holdsActive;
  const hasBadge = group.modules.some((m) => (m.badge ?? 0) > 0);
  const listId = `nav-${group.key}`;
  return (
    <div className="flex flex-col gap-0.5 pb-2">
      <button
        type="button"
        aria-expanded={open}
        aria-controls={listId}
        onClick={() => setOpen(!open)}
        className="flex h-8 items-center gap-2 rounded-sm px-2 text-left text-2xs font-bold tracking-[0.08em] text-muted uppercase hover:bg-surface-hover"
      >
        <span className="flex-1">{group.label}</span>
        {!open && hasBadge ? <span aria-label={t('st.sidebar.needsAttention')} className="size-1.5 rounded-pill bg-gold-500" /> : null}
        {open ? <CaretUp size={12} aria-hidden /> : <CaretDown size={12} aria-hidden />}
      </button>
      <ul id={listId} hidden={!open} className="m-0 flex list-none flex-col gap-0.5 p-0">
        {group.modules.map((m) => {
          const Icon = MODULE_ICONS[m.key];
          const current = m.key === active;
          return (
            <li key={m.key}>
              <Link
                href={hrefOf(m.key)}
                aria-current={current ? 'page' : undefined}
                className={cx(
                  'flex h-[34px] items-center gap-3 rounded-sm pr-3 pl-4 text-md text-text no-underline hover:bg-surface-hover hover:text-text hover:no-underline pointer-coarse:h-11',
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

/** Grouped sidebar filtered by the staff member's roles (the BFF returns only the modules they may open). */
export function Sidebar({ session, active }: { session: StaffSession; active: StaffModuleKey | null }) {
  const t = useT();
  return (
    <nav aria-label={t('st.sidebar.staffConsole')} className="flex h-full flex-col gap-0.5 px-3 py-4">
      <div className="px-2 pb-3">
        <strong className="block text-md">{t('st.sidebar.afrotransactInc')}</strong>
        <span className="text-xs text-muted">{t('st.sidebar.staffUsaMarket')}</span>
      </div>
      {session.navGroups.map((g) => (
        <NavGroupSection key={g.key} group={g} active={active} />
      ))}
      <p className="mt-auto px-2 pt-3 text-2xs leading-normal text-subtle">
        {t('st.sidebar.access')} <strong>{session.roleLabel || '—'}</strong>
        <br />
        {session.accessNote}
      </p>
    </nav>
  );
}

/** Icon rail (tablets): every module the staff member may open, one icon each, grouped by a rule; names as tooltips. */
export function SidebarRail({ session, active }: { session: StaffSession; active: StaffModuleKey | null }) {
  const t = useT();
  return (
    <nav aria-label={t('st.sidebar.staffConsole')} className="flex flex-col gap-2 px-2 py-3">
      {session.navGroups.map((g, gi) => (
        <ul key={g.key} aria-label={g.label} className={cx('m-0 flex list-none flex-col items-center gap-1 p-0', gi > 0 && 'border-t border-border pt-2')}>
          {g.modules.map((m) => {
            const Icon = MODULE_ICONS[m.key];
            const current = m.key === active;
            return (
              <li key={m.key}>
                <Link
                  href={hrefOf(m.key)}
                  aria-current={current ? 'page' : undefined}
                  title={m.label}
                  className={cx('relative flex size-11 items-center justify-center rounded-sm text-text no-underline hover:bg-surface-hover hover:text-text hover:no-underline', current && 'bg-gold-100 hover:bg-gold-100')}
                >
                  <Icon size={20} aria-hidden weight={current ? 'fill' : 'regular'} />
                  <span className="at-visually-hidden">{m.badge ? `${m.label} (${m.badge})` : m.label}</span>
                  {m.badge ? <span aria-hidden className="absolute top-1.5 right-1.5 size-2 rounded-pill bg-gold-500" /> : null}
                </Link>
              </li>
            );
          })}
        </ul>
      ))}
    </nav>
  );
}
