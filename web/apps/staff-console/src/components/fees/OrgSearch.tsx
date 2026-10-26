'use client';

import { type FeeOrgHit, type FeesOverview, staffQueries } from '@afrotransact/api-client';
import { fieldStyles } from '@afrotransact/ui';
import { MagnifyingGlass } from '@phosphor-icons/react/ssr';
import { useQuery } from '@tanstack/react-query';
import { type KeyboardEvent, useEffect, useId, useRef, useState } from 'react';

import { problemMessage } from '@/components/ui/States';
import { staffApi } from '@/lib/api';

/**
 * A searchable dropdown of seller and provider organisations: type two letters of a name and pick from the list
 * (AfroTransact Retail is listed but cannot be chosen). Arrow keys move, Enter picks, Escape closes.
 */
export function OrgSearch({
  overview,
  onPick,
  taken,
  label = 'Seller or provider',
  takenNote = 'has an override — adds another category'
}: {
  overview: FeesOverview;
  onPick: (hit: FeeOrgHit) => void;
  taken: (orgId: string) => boolean;
  label?: string;
  takenNote?: string;
}) {
  const id = useId();
  const [text, setText] = useState('');
  const [q, setQ] = useState('');
  const [open, setOpen] = useState(false);
  const [active, setActive] = useState(0);
  const box = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const t = setTimeout(() => setQ(text.trim()), 250);
    return () => clearTimeout(t);
  }, [text]);
  useEffect(() => {
    const close = (e: MouseEvent) => {
      if (box.current && !box.current.contains(e.target as Node)) setOpen(false);
    };
    document.addEventListener('mousedown', close);
    return () => document.removeEventListener('mousedown', close);
  }, []);
  const hits = useQuery({ ...staffQueries.feeOrgs(staffApi(), q), enabled: q.length >= 2 });
  const items = hits.data?.items ?? [];
  const choose = (h: FeeOrgHit) => {
    if (!h.selectable) return;
    onPick(h);
    setText('');
    setQ('');
    setOpen(false);
  };
  const onKey = (e: KeyboardEvent<HTMLInputElement>) => {
    if (e.key === 'ArrowDown') {
      e.preventDefault();
      setOpen(true);
      setActive((i) => Math.min(items.length - 1, i + 1));
    } else if (e.key === 'ArrowUp') {
      e.preventDefault();
      setActive((i) => Math.max(0, i - 1));
    } else if (e.key === 'Enter' && open && items[active]) {
      e.preventDefault();
      choose(items[active]);
    } else if (e.key === 'Escape') {
      setOpen(false);
    }
  };
  const status =
    text.trim().length < 2 ? 'Type at least two letters of the name'
      : hits.isFetching && !hits.data ? 'Searching…'
        : hits.isError ? problemMessage(hits.error)
          : items.length === 0 ? `No organisation matches “${q}”` : null;
  const listId = `${id}-list`;
  return (
    <div ref={box} className="relative flex flex-col gap-1">
      <label htmlFor={`${id}-input`} className="text-sm font-semibold">
        {label}
      </label>
      <div className="relative">
        <MagnifyingGlass size={16} aria-hidden className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-muted" />
        <input
          id={`${id}-input`}
          role="combobox"
          aria-expanded={open}
          aria-controls={listId}
          aria-autocomplete="list"
          aria-activedescendant={open && items[active] ? `${id}-opt-${active}` : undefined}
          autoComplete="off"
          className={fieldStyles.control}
          style={{ paddingLeft: '2.25rem' }}
          placeholder="Search by name"
          value={text}
          onChange={(e) => {
            setText(e.currentTarget.value);
            setActive(0);
            setOpen(true);
          }}
          onFocus={() => setOpen(true)}
          onKeyDown={onKey}
        />
      </div>
      {open ? (
        <ul id={listId} role="listbox" aria-label={label} className="absolute left-0 right-0 top-full z-20 m-0 mt-1 max-h-64 list-none overflow-auto rounded-md border border-border bg-surface p-1 shadow-lg">
          {status ? (
            <li role="presentation" className="px-3 py-2 text-sm text-muted">
              {status}
            </li>
          ) : null}
          {items.map((h, i) => (
            <li
              key={h.orgId || h.name}
              id={`${id}-opt-${i}`}
              role="option"
              aria-selected={i === active}
              aria-disabled={!h.selectable}
              className={`flex flex-wrap items-center gap-2 rounded-sm px-3 py-2 text-left ${h.selectable ? 'cursor-pointer' : 'cursor-not-allowed opacity-70'} ${i === active ? 'bg-surface-hover' : ''}`}
              onMouseEnter={() => setActive(i)}
              onMouseDown={(e) => {
                e.preventDefault();
                choose(h);
              }}
            >
              <strong className="font-semibold">{h.name}</strong>
              <span className="text-xs text-muted">{h.retail ? (h.reason ?? overview.retail.note) : `${h.kind.toLowerCase()}${taken(h.orgId) ? ` · ${takenNote}` : ''}`}</span>
            </li>
          ))}
        </ul>
      ) : null}
    </div>
  );
}
