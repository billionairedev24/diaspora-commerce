'use client';

import { sellerKeys, sellerQueries, type ServiceInput, ServiceInputSchema, type ServiceOffer } from '@afrotransact/api-client';
import { Button, Checkbox, Dialog, TextField, useToast } from '@afrotransact/ui';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { type ColumnDef } from '@tanstack/react-table';
import { useState } from 'react';

import { DataTable } from '@/components/ui/DataTable';
import { ModulePage } from '@/components/ui/ModulePage';
import { ErrorState, problemMessage } from '@/components/ui/States';
import { StatusPill } from '@/components/ui/StatusPill';
import { sellerApi } from '@/lib/api';
import { minutesLabel, money, toMinor } from '@/lib/format';
import { useSeller } from '@/lib/session';

type Draft = { name: string; description: string; duration: string; price: string; deposit: string; active: boolean };
const EMPTY: Draft = { name: '', description: '', duration: '60', price: '', deposit: '0', active: true };

const draftOf = (s: ServiceOffer): Draft => ({
  name: s.name, description: s.description, duration: String(s.durationMinutes), price: (s.priceMinor / 100).toFixed(2), deposit: (s.depositMinor / 100).toFixed(2), active: s.active
});

/** Estimate shown while typing; the saved row carries the platform's own take-home figures. */
export function estimateTakeHome(priceMinor: number, commissionPercent: number) {
  const commissionMinor = Math.round((priceMinor * commissionPercent) / 100);
  const cardFeesMinor = priceMinor > 0 ? Math.round(priceMinor * 0.029) + 30 : 0;
  return { commissionMinor, cardFeesMinor, takeHomeMinor: priceMinor - commissionMinor - cardFeesMinor };
}

/** Services: what customers can book, with price, deposit and what the provider takes home. */
export function ServicesView() {
  const api = sellerApi();
  const qc = useQueryClient();
  const toast = useToast();
  const { orgId, can } = useSeller();
  const editable = can('services');
  const q = useQuery(sellerQueries.services(api, orgId));
  const [editing, setEditing] = useState<ServiceOffer | 'new' | null>(null);
  const [draft, setDraft] = useState<Draft>(EMPTY);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const commission = q.data?.[0]?.takeHome.commissionPercent ?? 10;
  const save = useMutation({
    mutationFn: (input: ServiceInput) => (editing && editing !== 'new' ? api.updateService(editing.id, input) : api.createService(input)),
    onSuccess: (s) => {
      setEditing(null);
      void qc.invalidateQueries({ queryKey: sellerKeys.services(orgId) });
      toast.show(`${s.name} saved`);
    },
    onError: (e) => setErrors({ form: problemMessage(e) })
  });
  const open = (s: ServiceOffer | 'new') => {
    setEditing(s);
    setDraft(s === 'new' ? EMPTY : draftOf(s));
    setErrors({});
  };
  const columns: ColumnDef<ServiceOffer>[] = [
    { id: 'name', header: 'Service', size: 30, cell: ({ row }) => <span className="flex flex-col"><strong>{row.original.name}</strong><span className="truncate text-xs text-muted">{row.original.description}</span></span> },
    { id: 'duration', header: 'Duration', size: 12, cell: ({ row }) => minutesLabel(row.original.durationMinutes) },
    { id: 'price', header: 'Price', size: 12, cell: ({ row }) => money(row.original.priceMinor, row.original.currency) },
    { id: 'deposit', header: 'Deposit', size: 12, cell: ({ row }) => money(row.original.depositMinor, row.original.currency) },
    { id: 'takeHome', header: 'You take home', size: 14, cell: ({ row }) => <strong>{money(row.original.takeHome.takeHomeMinor, row.original.currency)}</strong> },
    { id: 'status', header: 'Status', size: 10, cell: ({ row }) => <StatusPill tone={row.original.active ? 'good' : 'info'}>{row.original.active ? 'Bookable' : 'Hidden'}</StatusPill> },
    { id: 'edit', header: () => <span className="at-visually-hidden">Edit</span>, size: 10, cell: ({ row }) => (editable ? <Button size="sm" variant="neutral" onClick={() => open(row.original)}>Edit</Button> : null) }
  ];
  const priceMinor = toMinor(draft.price);
  const est = estimateTakeHome(Number.isNaN(priceMinor) ? 0 : priceMinor, commission);
  const submit = () => {
    const r = ServiceInputSchema.safeParse({
      name: draft.name, description: draft.description, durationMinutes: Number(draft.duration), priceMinor: Number.isNaN(priceMinor) ? 0 : priceMinor,
      depositMinor: toMinor(draft.deposit || '0'), active: draft.active
    });
    if (!r.success) {
      setErrors(Object.fromEntries(r.error.issues.map((i) => [String(i.path[0]), i.message])));
      return;
    }
    setErrors({});
    save.mutate(r.data);
  };
  return (
    <ModulePage module="services" actions={editable ? <Button onClick={() => open('new')}>Add service</Button> : null}>
      {q.isError ? <ErrorState error={q.error} onRetry={() => void q.refetch()} /> : (
        <DataTable label="Services" columns={columns} data={q.data ?? []} getRowId={(s) => s.id} loading={q.isFetching} empty="No services yet. Add the first thing customers can book." />
      )}
      <Dialog open={editing !== null} onOpenChange={(o) => (o ? undefined : setEditing(null))} width={520} title={editing === 'new' ? 'Add service' : 'Edit service'}
        description="Customers pay the deposit when they book; the rest is charged when you mark the booking complete.">
        <form className="flex flex-col gap-1" onSubmit={(e) => { e.preventDefault(); submit(); }}>
          <TextField label="Name" value={draft.name} error={errors.name} onChange={(e) => setDraft({ ...draft, name: e.target.value })} />
          <TextField label="Description" value={draft.description} error={errors.description} onChange={(e) => setDraft({ ...draft, description: e.target.value })} />
          <div className="grid gap-x-3 sm:grid-cols-3">
            <TextField label="Duration (minutes)" inputMode="numeric" value={draft.duration} error={errors.durationMinutes} onChange={(e) => setDraft({ ...draft, duration: e.target.value.replace(/\D/g, '') })} />
            <TextField label="Price ($)" inputMode="decimal" value={draft.price} error={errors.priceMinor} onChange={(e) => setDraft({ ...draft, price: e.target.value })} />
            <TextField label="Deposit ($)" inputMode="decimal" value={draft.deposit} error={errors.depositMinor} onChange={(e) => setDraft({ ...draft, deposit: e.target.value })} />
          </div>
          <Checkbox label="Bookable (show on my public page)" checked={draft.active} onChange={(e) => setDraft({ ...draft, active: e.target.checked })} />
          <dl aria-label="Take-home estimate" className="my-2 grid grid-cols-[1fr_auto] gap-x-4 gap-y-1 rounded-md bg-surface-muted p-3 text-sm">
            <dt>Price</dt><dd className="m-0 text-right">{money(Number.isNaN(priceMinor) ? 0 : priceMinor)}</dd>
            <dt>AfroTransact commission ({commission}%)</dt><dd className="m-0 text-right">−{money(est.commissionMinor)}</dd>
            <dt>Stripe card fees (2.9% + 30¢)</dt><dd className="m-0 text-right">−{money(est.cardFeesMinor)}</dd>
            <dt className="font-semibold">You take home (estimate)</dt><dd className="m-0 text-right font-semibold">{money(est.takeHomeMinor)}</dd>
          </dl>
          {errors.form ? <span role="alert" className="text-sm text-danger">{errors.form}</span> : null}
          <Button type="submit" loading={save.isPending}>Save service</Button>
        </form>
      </Dialog>
    </ModulePage>
  );
}
