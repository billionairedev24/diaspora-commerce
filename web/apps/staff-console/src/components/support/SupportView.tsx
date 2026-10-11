'use client';

import { staffQueries } from '@afrotransact/api-client';
import { Tabs } from '@afrotransact/ui';
import { MagnifyingGlass } from '@phosphor-icons/react/ssr';
import { useQuery } from '@tanstack/react-query';
import { usePathname, useRouter, useSearchParams } from 'next/navigation';
import { useEffect, useState } from 'react';

import { ModulePage } from '@/components/module/ModulePage';
import { ResourceTable } from '@/components/resources/ResourceTable';
import { ErrorState } from '@/components/ui/States';
import { staffApi } from '@/lib/api';

import { CustomerPanel } from './CustomerPanel';

function CustomerLookup({ onPick }: { onPick: (id: string) => void }) {
  const [text, setText] = useState('');
  const [q, setQ] = useState('');
  useEffect(() => {
    const t = setTimeout(() => setQ(text.trim()), 300);
    return () => clearTimeout(t);
  }, [text]);
  const hits = useQuery({ ...staffQueries.customers(staffApi(), q), enabled: q.length >= 2 });
  return (
    <div className="flex flex-col gap-2">
      <label className="flex h-10 max-w-xl items-center gap-2 rounded-pill border border-border-strong bg-surface px-3">
        <MagnifyingGlass size={16} aria-hidden className="text-muted" />
        <span className="at-visually-hidden">Find a customer</span>
        <input type="search" className="min-w-0 flex-1 bg-transparent text-sm outline-none" placeholder="Name or email" value={text} onChange={(e) => setText(e.currentTarget.value)} />
      </label>
      {hits.isError ? <ErrorState error={hits.error} /> : null}
      {hits.data ? (
        hits.data.items.length ? (
          <ul aria-label="Customers found" className="m-0 flex max-w-xl list-none flex-col gap-1 p-0">
            {hits.data.items.map((h) => (
              <li key={h.id}>
                <button type="button" className="flex w-full items-center gap-2 rounded-sm px-3 py-2 text-left hover:bg-surface-hover" onClick={() => onPick(h.id)}>
                  <strong className="font-semibold">{h.name}</strong>
                  <span className="text-sm text-muted">{h.email}</span>
                </button>
              </li>
            ))}
          </ul>
        ) : (
          <p className="m-0 text-sm text-muted">No customer matches “{q}”.</p>
        )
      ) : null}
    </div>
  );
}

/** Customer support console: Lola hand-off queue, customer lookup and the customer 360 with role-limited actions. */
export function SupportView() {
  const router = useRouter();
  const pathname = usePathname();
  const params = useSearchParams();
  const customer = params.get('customer');
  const caseId = params.get('case') ?? undefined;
  const tab = params.get('tab') ?? (customer ? 'customer' : 'cases');
  const go = (next: Record<string, string | null>) => {
    const p = new URLSearchParams(params.toString());
    Object.entries(next).forEach(([k, v]) => (v === null ? p.delete(k) : p.set(k, v)));
    router.replace(`${pathname}?${p.toString()}`, { scroll: false });
  };
  return (
    <ModulePage module="support">
      <Tabs
        label="Support"
        value={tab}
        onValueChange={(v) => go({ tab: v })}
        items={[
          { value: 'cases', label: 'Lola hand-offs', content: <ResourceTable resource="support-cases" urlPrefix="cases" /> },
          {
            value: 'customer',
            label: 'Customer lookup',
            content: (
              <div className="flex flex-col gap-4">
                <CustomerLookup onPick={(id) => go({ customer: id, tab: 'customer', case: null })} />
                {customer ? <CustomerPanel customerId={customer} caseId={caseId} /> : null}
              </div>
            )
          }
        ]}
      />
    </ModulePage>
  );
}
