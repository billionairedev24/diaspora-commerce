'use client';

import { isApiError, staffAccessKeys, staffAccessQueries,type StaffInvitation } from '@afrotransact/api-client';
import { CellText, DataTable, useTableState } from '@afrotransact/data-table';
import { Button, useToast } from '@afrotransact/ui';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { type ColumnDef } from '@tanstack/react-table';
import { useSearchParams } from 'next/navigation';
import { useMemo, useState } from 'react';

import { ErrorState, problemMessage } from '@/components/ui/States';
import { useT } from '@/i18n/t';
import { staffApi } from '@/lib/api';
import { dateTime } from '@/lib/format';

import { InviteDialog } from './InviteDialog';
import { ReasonDialog } from './ReasonDialog';

/**
 * Invitations waiting: people invited to be staff who have not chosen a password yet, by name and email, with who invited them,
 * when the last link went out and when it stops working. Staff with access:admin can invite, send a link again (the old one
 * stops working) or revoke; everyone with access:read can look. The list is the server's page, so the total is exact.
 */
export function InvitationsPanel() {
  const t = useT();
  const api = staffApi();
  const qc = useQueryClient();
  const toast = useToast();
  const state = useTableState({ searchParams: useSearchParams(), prefix: 'inv', defaultPageSize: 10 });
  const q = useQuery(staffAccessQueries.invitations(api.access, state.pagination.pageIndex, state.pagination.pageSize));
  const [inviting, setInviting] = useState(false);
  const [revoking, setRevoking] = useState<StaffInvitation | null>(null);
  const [error, setError] = useState<string | null>(null);
  const canAdmin = q.data?.canAdmin ?? false;

  const resend = useMutation({
    mutationFn: (i: StaffInvitation) => api.access.resendInvitation(i.id),
    retry: false,
    onSuccess: (done) => {
      void qc.invalidateQueries({ queryKey: staffAccessKeys.all });
      toast.show(t('st.inv.toast.resent', { email: done.email }));
    },
    onError: (e) => toast.show(problemMessage(e))
  });
  const revoke = useMutation({
    mutationFn: (v: { invitation: StaffInvitation; reason: string }) => api.access.revokeInvitation(v.invitation.id, v.reason),
    retry: false,
    onSuccess: (_done, v) => {
      void qc.invalidateQueries({ queryKey: staffAccessKeys.all });
      toast.show(t('st.inv.toast.revoked', { email: v.invitation.email }));
      setRevoking(null);
      setError(null);
    },
    onError: (e) => setError(isApiError(e) && e.fieldErrors.reason ? e.fieldErrors.reason : problemMessage(e))
  });

  // The table's own search box narrows the page that is showing.
  const rows = useMemo(() => {
    const needle = state.q.trim().toLowerCase();
    const items = q.data?.items ?? [];
    return needle ? items.filter((i) => `${i.name} ${i.email} ${i.roleName} ${i.invitedByName}`.toLowerCase().includes(needle)) : items;
  }, [q.data, state.q]);

  const columns = useMemo<ColumnDef<StaffInvitation>[]>(
    () => [
      { id: 'name', accessorFn: (i) => i.name, header: t('st.inv.col.name'), size: 190, meta: { kind: 'strong', label: t('st.inv.col.name') }, cell: ({ row }) => <strong className="font-semibold">{row.original.name}</strong> },
      { id: 'email', accessorFn: (i) => i.email, header: t('st.inv.col.email'), size: 250, cell: ({ row }) => <CellText>{row.original.email}</CellText> },
      { id: 'role', accessorFn: (i) => i.roleName, header: t('st.inv.col.role'), size: 160 },
      { id: 'by', accessorFn: (i) => i.invitedByName, header: t('st.inv.col.by'), size: 160 },
      {
        id: 'status',
        accessorFn: (i) => i.status,
        header: t('st.inv.col.status'),
        size: 230,
        cell: ({ row }) => {
          const i = row.original;
          const state = i.status === 'EXPIRED' ? t('st.inv.status.expired', { when: dateTime(i.expiresAt) }) : t('st.inv.status.waiting', { when: dateTime(i.expiresAt) });
          return <CellText>{i.sendCount > 1 ? `${state} · ${t('st.inv.status.sent', { count: i.sendCount })}` : state}</CellText>;
        }
      },
      {
        id: 'actions',
        header: t('st.inv.col.actions'),
        size: 230,
        enableSorting: false,
        cell: ({ row }) =>
          canAdmin ? (
            <span className="flex flex-wrap items-center gap-2">
              <Button size="sm" variant="secondary" loading={resend.isPending && resend.variables?.id === row.original.id} onClick={() => resend.mutate(row.original)}>
                {t('st.inv.action.resend')}
              </Button>
              <Button
                size="sm"
                variant="secondary"
                onClick={() => {
                  setError(null);
                  setRevoking(row.original);
                }}
              >
                {t('st.inv.action.revoke')}
              </Button>
            </span>
          ) : null
      }
    ],
    [t, canAdmin, resend]
  );

  return (
    <section aria-labelledby="inv-title" className="flex flex-col gap-3" data-testid="invitations">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="flex flex-col gap-1">
          <h2 id="inv-title" className="m-0 text-md font-bold">
            {t('st.inv.title')}
          </h2>
          <p className="m-0 max-w-[80ch] text-sm text-muted">{t('st.inv.intro')}</p>
        </div>
        {canAdmin ? (
          <Button onClick={() => setInviting(true)} data-testid="invite-open">
            {t('st.inv.invite.open')}
          </Button>
        ) : null}
      </div>
      <DataTable<StaffInvitation>
        id="staff.access.invitations"
        label={t('st.inv.table')}
        columns={columns}
        data={rows}
        getRowId={(i) => i.id}
        rowLabel={(i) => i.name}
        state={state}
        mode="server"
        total={q.data?.total ?? null}
        loading={q.isPending}
        fetching={q.isFetching}
        error={q.isError ? <ErrorState error={q.error} onRetry={() => void q.refetch()} /> : undefined}
        enableSelection={false}
        searchPlaceholder={t('st.inv.search')}
        empty={t('st.inv.none')}
      />
      <InviteDialog open={inviting} onOpenChange={setInviting} />
      <ReasonDialog
        open={revoking !== null}
        onOpenChange={(o) => {
          if (!o) {
            setRevoking(null);
            setError(null);
          }
        }}
        title={revoking ? t('st.inv.revoke.title', { name: revoking.name }) : ''}
        confirmLabel={t('st.inv.revoke.confirm')}
        danger
        pending={revoke.isPending}
        error={error}
        onConfirm={(reason) => revoking && revoke.mutate({ invitation: revoking, reason })}
      >
        {revoking ? t('st.inv.revoke.body', { email: revoking.email }) : null}
      </ReasonDialog>
    </section>
  );
}
