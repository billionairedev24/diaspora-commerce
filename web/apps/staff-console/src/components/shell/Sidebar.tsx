'use client';

import { type StaffModuleKey, type StaffNavGroup, type StaffSession } from '@afrotransact/api-client';
import { cx } from '@afrotransact/ui';
import { CaretDown, CaretUp } from '@phosphor-icons/react/ssr';
import Link from 'next/link';
import { useState } from 'react';

import { useT } from '@/i18n/t';
import { hrefOf, MODULE_ICONS } from '@/lib/modules';

function NavGroupSection({ group, active, onNavigate }: { group: StaffNavGroup; active: StaffModuleKey | null; onNavigate?: () => void }) {
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

/** Grouped sidebar filtered by the staff member's roles (the BFF returns only the modules they may open). */
export function Sidebar({ session, active, onNavigate }: { session: StaffSession; active: StaffModuleKey | null; onNavigate?: () => void }) {
  const t = useT();
  return (
    <nav aria-label={t('st.sidebar.staffConsole')} className="flex h-full flex-col gap-0.5 px-3 py-4">
      <div className="px-2 pb-3">
        <strong className="block text-md">{t('st.sidebar.afrotransactInc')}</strong>
        <span className="text-xs text-muted">{t('st.sidebar.staffUsaMarket')}</span>
      </div>
      {session.navGroups.map((g) => (
        <NavGroupSection key={g.key} group={g} active={active} onNavigate={onNavigate} />
      ))}
      <p className="mt-auto px-2 pt-3 text-2xs leading-normal text-subtle">
        {t('st.sidebar.access')} <strong>{session.roleLabel || '—'}</strong>
        <br />
        {session.accessNote}
      </p>
    </nav>
  );
}
