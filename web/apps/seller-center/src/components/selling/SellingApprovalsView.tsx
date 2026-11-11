'use client';

import { sellerKeys, sellerQueries, type SellingApprovalRow, type SellingApprovalState } from '@afrotransact/api-client';
import { Button, buttonClassName, TextField } from '@afrotransact/ui';
import { useQuery } from '@tanstack/react-query';
import Link from 'next/link';
import { useSearchParams } from 'next/navigation';
import { useMemo, useState } from 'react';

import { ModulePage } from '@/components/ui/ModulePage';
import { Notice } from '@/components/ui/Notice';
import { EmptyState, ErrorState, Spinner } from '@/components/ui/States';
import { StatusPill, type Tone } from '@/components/ui/StatusPill';
import { sellerApi } from '@/lib/api';
import { shortDate } from '@/lib/format';
import { useSeller } from '@/lib/session';

import { ApplyToSellDialog } from './ApplyToSellDialog';

const TONE: Record<SellingApprovalState, Tone> = { NOT_APPLIED: 'info', APPLIED: 'brand', NEEDS_ATTENTION: 'warn', APPROVED: 'good', EXPIRED: 'warn', NOT_AVAILABLE: 'bad' };
const SCOPE_TITLE: Record<string, string> = {
  CAPABILITY: 'Seller capabilities',
  CATEGORY: 'Categories and departments',
  PRODUCT_TYPE: 'Product types',
  BRAND: 'Designer and well-known brands',
  BRAND_IN_CATEGORY: 'Brands in a category',
  PRODUCT: 'Single products'
};
const ORDER = ['CAPABILITY', 'CATEGORY', 'PRODUCT_TYPE', 'BRAND', 'BRAND_IN_CATEGORY', 'PRODUCT'];

/**
 * Selling approvals: every scope that needs approval with this store's state in plain words, what is on file and when it
 * expires, and "Apply to sell" (or "Renew", "Update my application") with the documents each approval asks for. Approve once;
 * after that every listing in the scope goes live. A document that expires is warned about 30, 14 and 7 days ahead; 14
 * days after it expires the listings are paused (never deleted) and come back when the renewal is approved.
 */
export function SellingApprovalsView() {
  const { orgId, can } = useSeller();
  const api = sellerApi();
  const q = useQuery({ ...sellerQueries.sellingApprovals(api, orgId), refetchOnWindowFocus: true });
  const editable = can('listings');
  const params = useSearchParams();
  const [filter, setFilter] = useState('');
  const [opened, setOpened] = useState<string | null>(null);
  const apply = opened ?? params.get('apply');
  const rows = useMemo(() => q.data?.approvals ?? [], [q.data]);
  const shown = useMemo(() => {
    const text = filter.trim().toLowerCase();
    const list = text ? rows.filter((r) => `${r.label} ${r.scopeKey}`.toLowerCase().includes(text)) : rows;
    return ORDER.map((type) => ({ type, rows: list.filter((r) => r.scopeType === type) })).filter((g) => g.rows.length > 0);
  }, [rows, filter]);
  const target = apply ? rows.find((r) => `${r.scopeType}:${r.scopeKey}` === apply) : undefined;

  return (
    <ModulePage module="listings" description="Some products need our approval before they go live. Apply once for a category or brand; after that every listing there goes live without another check.">
      {q.isPending ? <Spinner /> : q.isError ? <ErrorState error={q.error} onRetry={() => void q.refetch()} /> : (
        <>
          <div className="flex flex-wrap items-center gap-2">
            <Link href="/listings" className={buttonClassName({ variant: 'neutral' })}>Back to listings</Link>
          </div>
          {(q.data?.notices ?? []).map((n) => (
            <Notice key={`${n.scopeKey}-${n.message}`} tone={n.severity === 'urgent' ? 'danger' : n.severity === 'warning' ? 'warning' : 'info'} title={n.scopeLabel ?? 'Selling approvals'}>
              {n.message}
            </Notice>
          ))}
          <TextField label="Find a category or brand" value={filter} onChange={(e) => setFilter(e.target.value)} placeholder="Supplements, Gucci, Apple…" />
          {shown.length === 0 ? <EmptyState title="Nothing matches">Try another word.</EmptyState> : shown.map((g) => (
            <section key={g.type} aria-labelledby={`sel-${g.type}`} className="flex flex-col gap-2">
              <h2 id={`sel-${g.type}`} className="m-0 text-lg font-bold">{SCOPE_TITLE[g.type] ?? g.type}</h2>
              <ul className="m-0 flex list-none flex-col gap-2 p-0">
                {g.rows.map((r) => <ApprovalRow key={`${r.scopeType}:${r.scopeKey}`} row={r} editable={editable} onApply={() => setOpened(`${r.scopeType}:${r.scopeKey}`)} />)}
              </ul>
            </section>
          ))}
        </>
      )}
      {target ? <ApplyToSellDialog row={target} onClose={() => setOpened('')} invalidate={sellerKeys.sellingApprovals(orgId)} /> : null}
    </ModulePage>
  );
}

function ApprovalRow({ row, editable, onApply }: { row: SellingApprovalRow; editable: boolean; onApply: () => void }) {
  const action = row.state === 'EXPIRED' || (row.state === 'APPROVED' && row.daysToExpiry != null && row.daysToExpiry <= 30) ? 'Renew'
    : row.state === 'NEEDS_ATTENTION' && row.applicationStatus === 'INFO_REQUESTED' ? 'Update my application'
    : row.state === 'NEEDS_ATTENTION' ? 'Apply again' : 'Apply to sell';
  const can = editable && (row.canApply || (row.state === 'APPROVED' && row.daysToExpiry != null && row.daysToExpiry <= 30));
  return (
    <li className="flex flex-wrap items-start gap-x-4 gap-y-2 rounded-md border border-border bg-surface p-3" data-state={row.state}>
      <div className="min-w-0 flex-1 basis-80">
        <div className="flex flex-wrap items-center gap-2">
          <span className="font-semibold">{row.label}</span>
          <StatusPill tone={TONE[row.state]}>{row.statusWords}</StatusPill>
          {row.listingsPaused ? <StatusPill tone="bad">Listings paused</StatusPill> : null}
        </div>
        {row.message ? <p className="mt-1 mb-0 text-sm text-muted">{row.message}</p> : null}
        {row.expiresAt ? <p className="mt-1 mb-0 text-xs text-muted">Runs until {shortDate(row.expiresAt)}{row.daysToExpiry != null && row.daysToExpiry >= 0 ? ` (${row.daysToExpiry} days)` : ''}</p> : null}
        {row.reapplyAfter ? <p className="mt-1 mb-0 text-xs text-muted">You can apply again from {shortDate(row.reapplyAfter)}.</p> : null}
        {row.onFile.length > 0 ? (
          <ul className="mt-1 mb-0 flex list-none flex-wrap gap-x-3 p-0 text-xs text-muted">
            {row.onFile.map((d) => <li key={d.id}>{d.label.split(' (')[0]}{d.expiresOn ? `, expires ${shortDate(d.expiresOn)}` : ''}</li>)}
          </ul>
        ) : null}
      </div>
      {can ? <Button variant="primary" onClick={onApply}>{action}</Button> : null}
    </li>
  );
}
