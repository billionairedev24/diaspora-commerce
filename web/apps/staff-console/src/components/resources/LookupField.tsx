'use client';

import { type FormField, type LookupHit, staffQueries } from '@afrotransact/api-client';
import { TextField } from '@afrotransact/ui';
import { X } from '@phosphor-icons/react/ssr';
import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';

import { staffApi } from '@/lib/api';
import { useDebounced } from '@/lib/use-debounced';

const NOUN: Record<string, string> = { drivers: 'driver', sellers: 'seller' };

/**
 * A form field that picks a driver or a seller by name (instead of a typed id): type to search, choose one and its name
 * shows with a way to change it. The form still sends the id. Without anything typed the first matches are listed.
 */
export function LookupField({
  field,
  id,
  label,
  value,
  error,
  onChange
}: {
  field: FormField;
  id: string;
  label: string;
  value: string;
  error?: string;
  onChange: (id: string) => void;
}) {
  const kind = field.lookup ?? 'drivers';
  const [text, setText] = useState('');
  const [picked, setPicked] = useState<LookupHit | null>(null);
  const settled = useDebounced(text.trim(), 300);
  const typed = settled.length === 1 ? '' : settled;
  const search = useQuery({ ...staffQueries.lookup(staffApi(), kind, typed), enabled: !value });
  const choose = (hit: LookupHit) => {
    setPicked(hit);
    setText('');
    onChange(hit.id);
  };
  if (value) {
    return (
      <div className="flex flex-col gap-1" data-testid={`lookup-${field.key}`}>
        <span className="text-sm font-semibold">{label}</span>
        <div className="flex min-h-11 items-center justify-between gap-2 rounded-md border border-border bg-surface-muted ps-3 text-sm">
          <span className="min-w-0 break-words">
            <strong>{picked?.name ?? value}</strong>
            {picked?.detail ? <span className="text-muted"> · {picked.detail}</span> : null}
          </span>
          <button
            type="button"
            aria-label={`Choose another ${NOUN[kind] ?? 'one'}`}
            className="inline-flex size-11 shrink-0 cursor-pointer items-center justify-center border-0 bg-transparent text-text"
            onClick={() => {
              setPicked(null);
              onChange('');
            }}
          >
            <X aria-hidden size={16} />
          </button>
        </div>
      </div>
    );
  }
  const hits = search.data?.items ?? [];
  return (
    <div className="flex flex-col gap-2" data-testid={`lookup-${field.key}`}>
      <TextField
        id={id}
        label={label}
        placeholder={`Search ${NOUN[kind] ?? 'by'} name`}
        value={text}
        autoComplete="off"
        error={error}
        help={field.help ?? 'Type part of the name, then choose from the list.'}
        onChange={(e) => setText(e.currentTarget.value)}
      />
      {search.isError ? (
        <p role="alert" className="m-0 text-sm text-danger">
          The search is not answering. Try again in a moment.
        </p>
      ) : search.isSuccess && hits.length === 0 ? (
        <p className="m-0 text-sm text-muted">No {NOUN[kind] ?? 'match'} found with that name.</p>
      ) : null}
      {hits.length > 0 ? (
        <ul className="m-0 flex max-h-56 list-none flex-col gap-1 overflow-auto p-0" aria-label={`Matching ${kind}`} data-testid="lookup-hits">
          {hits.map((h) => (
            <li key={h.id}>
              <button
                type="button"
                className="flex min-h-11 w-full cursor-pointer flex-col items-start justify-center rounded-md border border-border bg-surface px-3 text-start text-sm sm:min-h-9"
                onClick={() => choose(h)}
              >
                <span className="font-semibold">{h.name}</span>
                {h.detail ? <span className="text-xs text-muted">{h.detail}</span> : null}
              </button>
            </li>
          ))}
        </ul>
      ) : null}
    </div>
  );
}
