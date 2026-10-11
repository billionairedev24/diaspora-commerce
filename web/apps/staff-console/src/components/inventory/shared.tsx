'use client';

import { type InventoryHubApi, inventoryHubKeys, type RecordTone } from '@afrotransact/api-client';
import { useToast } from '@afrotransact/ui';
import { type MutationFunction, useMutation, useQueryClient } from '@tanstack/react-query';
import { usePathname, useRouter, useSearchParams } from 'next/navigation';
import { useCallback } from 'react';

import { problemMessage } from '@/components/ui/States';
import { staffApi } from '@/lib/api';
import { useStaff } from '@/lib/session';

/** The Inventory hub and Retail (1P) routes of the staff BFF. */
export function useHub(): InventoryHubApi {
  return staffApi().inventory;
}

/** Today in the browser's calendar (YYYY-MM-DD), the date lots and dates are compared with. */
export function todayIso(): string {
  const d = new Date();
  const p = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
}

/** What the staff member may do in the hub (the BFF checks the same levels). */
export function useHubAccess() {
  const { can } = useStaff();
  return {
    inventoryEdit: can('inventory', 'EDIT'),
    inventoryApprove: can('inventory', 'APPROVE'),
    retailView: can('retail', 'VIEW'),
    retailEdit: can('retail', 'EDIT'),
    retailApprove: can('retail', 'APPROVE')
  };
}

const TONE: Record<string, RecordTone> = {
  SELLABLE: 'good', LIVE: 'good', CLOSED: 'good', POSTED: 'good', COMPLETED: 'good', MATCHED: 'good', APPROVED: 'good', RECEIVED: 'good', CONFIRMED: 'good', ACTIVE: 'good', DONE: 'good',
  QUARANTINE: 'warn', DAMAGED: 'warn', PAUSED: 'warn', PENDING_APPROVAL: 'warn', COUNTED: 'warn', EXCEPTION: 'warn', ON_HOLD: 'warn', PARTIALLY_RECEIVED: 'warn', WAITING: 'warn', RECEIVING: 'warn',
  EXPIRED: 'bad', CANCELLED: 'bad', REJECTED: 'bad', WRITTEN_OFF: 'bad', BROKEN: 'bad',
  WORKING: 'info', SHIPPED: 'info', DRAFT: 'info', SENT: 'info', OPEN: 'info', POSTING: 'info'
};

export const toneOf = (status: string): RecordTone => TONE[status] ?? 'info';

/** A mutation that toasts its outcome and refreshes everything the hub shows. */
export function useHubMutation<TVars, TData>(fn: MutationFunction<TData, TVars>, success: (data: TData) => string, onDone?: (data: TData) => void) {
  const qc = useQueryClient();
  const toast = useToast();
  return useMutation({
    mutationFn: fn,
    onSuccess: (data) => {
      void qc.invalidateQueries({ queryKey: inventoryHubKeys.all });
      toast.show(success(data));
      onDone?.(data);
    },
    onError: (e) => toast.show(problemMessage(e), 'error')
  });
}

/** One URL search param as state (sheets and tabs survive a reload and can be linked). */
export function useParam(name: string): [string | null, (v: string | null) => void] {
  const params = useSearchParams();
  const router = useRouter();
  const pathname = usePathname();
  const set = useCallback(
    (v: string | null) => {
      const next = new URLSearchParams(params.toString());
      if (v === null || v === '') next.delete(name);
      else next.set(name, v);
      const s = next.toString();
      router.replace(s ? `${pathname}?${s}` : pathname, { scroll: false });
    },
    [name, params, pathname, router]
  );
  return [params.get(name), set];
}

/** Small action button used in sheets and toolbars. */
export const smallButton =
  'inline-flex h-8 items-center gap-1 rounded-pill border border-border-strong bg-surface px-3 text-sm font-semibold hover:bg-surface-muted disabled:cursor-not-allowed disabled:opacity-50';

/** Days to expiry as the lot tables show it. */
export function expiryLabel(days: number | null): string {
  if (days === null) return 'No expiry';
  if (days < 0) return `Expired ${-days} d ago`;
  if (days === 0) return 'Expires today';
  return `${days} d left`;
}

export const EXPIRY_CLASS: Record<string, string> = {
  expired: 'text-danger font-semibold',
  within30: 'text-danger',
  within60: 'text-warning',
  ok: 'text-success',
  none: 'text-muted'
};
