'use client';

import { type FeeOrgHit, type FeesOverview, staffQueries } from '@afrotransact/api-client';
import { MagnifyingGlass } from '@phosphor-icons/react/ssr';
import { useQuery } from '@tanstack/react-query';
import { useEffect, useState } from 'react';

import { problemMessage } from '@/components/ui/States';
import { staffApi } from '@/lib/api';

/** Finds a seller or provider organisation for an override (AfroTransact Retail is listed but can't be chosen). */
export function OrgSearch({
  overview,
  onPick,
  taken,
  label = 'Find a seller organisation',
  takenNote = 'has an override — adds another category'
}: {
  overview: FeesOverview;
  onPick: (hit: FeeOrgHit) => void;
  taken: (orgId: string) => boolean;
  label?: string;
  takenNote?: string;
}) {
  const [text, setText] = useState('');
  const [q, setQ] = useState('');
  useEffect(() => {
    const t = setTimeout(() => setQ(text.trim()), 250);
    return () => clearTimeout(t);
  }, [text]);
  const hits = useQuery({ ...staffQueries.feeOrgs(staffApi(), q), enabled: q.length >= 2 });
  return (
    <div className="flex flex-col gap-2">
      <label className="flex h-10 max-w-xl items-center gap-2 rounded-pill border border-border-strong bg-surface px-3">
        <MagnifyingGlass size={16} aria-hidden className="text-muted" />
        <span className="at-visually-hidden">{label}</span>
        <input type="search" className="min-w-0 flex-1 bg-transparent text-sm outline-none" placeholder="Seller or provider name" value={text} onChange={(e) => setText(e.currentTarget.value)} />
      </label>
      {hits.isError ? <p className="m-0 text-sm text-danger">{problemMessage(hits.error)}</p> : null}
      {hits.data ? (
        hits.data.items.length ? (
          <ul aria-label="Organisations found" className="m-0 flex max-w-xl list-none flex-col gap-1 p-0">
            {hits.data.items.map((h) => (
              <li key={h.orgId || h.name}>
                <button
                  type="button"
                  disabled={!h.selectable}
                  className="flex w-full flex-wrap items-center gap-2 rounded-sm px-3 py-2 text-left hover:bg-surface-hover disabled:cursor-not-allowed disabled:opacity-70"
                  onClick={() => {
                    onPick(h);
                    setText('');
                    setQ('');
                  }}
                >
                  <strong className="font-semibold">{h.name}</strong>
                  <span className="text-xs text-muted">{h.retail ? (h.reason ?? overview.retail.note) : `${h.kind.toLowerCase()} · ${taken(h.orgId) ? takenNote : h.orgId}`}</span>
                </button>
              </li>
            ))}
          </ul>
        ) : (
          <p className="m-0 text-sm text-muted">No organisation matches “{q}”.</p>
        )
      ) : null}
    </div>
  );
}
