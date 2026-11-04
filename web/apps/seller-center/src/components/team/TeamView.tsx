'use client';

import { InviteFormSchema, isApiError, type RoleCard, sellerKeys, sellerQueries, TEAM_ERROR_MESSAGES, type TeamMember } from '@afrotransact/api-client';
import { DataTable, useTableState } from '@afrotransact/data-table';
import { Button, buttonClassName, cx, Dialog, Select, TextField, useToast } from '@afrotransact/ui';
import { CheckCircle, Eye, Prohibit } from '@phosphor-icons/react/ssr';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { type ColumnDef } from '@tanstack/react-table';
import Link from 'next/link';
import { useSearchParams } from 'next/navigation';
import { useMemo, useState } from 'react';

import { FormErrors } from '@/components/ui/FormErrors';
import { ModulePage } from '@/components/ui/ModulePage';
import { ErrorState, problemMessage, Spinner } from '@/components/ui/States';
import { StatusPill, toneOf } from '@/components/ui/StatusPill';
import { sellerApi } from '@/lib/api';
import { shortDate } from '@/lib/format';
import { useSeller } from '@/lib/session';
import { exportDataset, pollExport } from '@/lib/table-export';

/** One card per role: what it can change, only view, and can't access. The member's own role is highlighted. */
export function RoleCards({ roles, yours }: { roles: RoleCard[]; yours: string }) {
  return (
    <div className="grid grid-cols-[repeat(auto-fit,minmax(min(100%,280px),1fr))] gap-3">
      {roles.map((r) => {
        const mine = r.key === yours;
        return (
          <section key={r.key} aria-label={`${r.label} role`} aria-current={mine ? 'true' : undefined} className={cx('flex flex-col gap-2 rounded-lg border p-4', mine ? 'border-gold-500 bg-gold-50' : 'border-border')}>
            <div className="flex items-center gap-2">
              <strong className="flex-1 text-md">{r.label}</strong>
              {mine ? <span className="rounded-pill bg-gold-500 px-2 py-0.5 text-2xs font-bold text-ink-1000">Your role</span> : null}
            </div>
            <p className="m-0 text-sm text-muted">{r.summary}</p>
            {r.can.length ? (
              <div className="text-sm">
                <span className="flex items-center gap-1 font-semibold text-success"><CheckCircle size={16} weight="fill" aria-hidden /> Can change</span>
                <ul className="m-0 pl-5">{r.can.map((x) => <li key={x}>{x}</li>)}</ul>
              </div>
            ) : null}
            {r.view.length ? (
              <div className="text-sm">
                <span className="flex items-center gap-1 font-semibold text-info"><Eye size={16} aria-hidden /> Can only view</span>
                <ul className="m-0 pl-5">{r.view.map((x) => <li key={x}>{x}</li>)}</ul>
              </div>
            ) : null}
            {r.cannot.length ? (
              <div className="text-sm">
                <span className="flex items-center gap-1 font-semibold text-danger"><Prohibit size={16} aria-hidden /> Can’t access</span>
                <ul className="m-0 pl-5 text-muted">{r.cannot.map((x) => <li key={x}>{x}</li>)}</ul>
              </div>
            ) : null}
            {r.note ? <p className="m-0 mt-auto border-t border-border pt-2 text-xs text-muted">{r.note}</p> : null}
          </section>
        );
      })}
    </div>
  );
}

/** Team problem codes (identity, mapped by the BFF) → plain-language message; anything else → the problem's detail. */
export function teamError(e: unknown): string {
  const code = isApiError(e) ? e.code : undefined;
  return (code && TEAM_ERROR_MESSAGES[code]) ?? problemMessage(e);
}

type Pending = { kind: 'remove' | 'revoke'; member: TeamMember } | null;

/** Team & roles: identity's member directory with pending invitations (INVITED rows), invite / revoke, role changes,
    removal, and capability cards. */
export function TeamView() {
  const api = sellerApi();
  const qc = useQueryClient();
  const toast = useToast();
  const { orgId } = useSeller();
  const q = useQuery(sellerQueries.team(api, orgId));
  const state = useTableState({ searchParams: useSearchParams() });
  const [invite, setInvite] = useState(false);
  const [form, setForm] = useState({ email: '', roleKey: '' });
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [pending, setPending] = useState<Pending>(null);
  const roleOptions = useMemo(() => (q.data?.roles ?? []).map((r) => ({ value: r.key, label: r.label })), [q.data]);
  const inviteM = useMutation({
    mutationFn: () => api.inviteMember(form.email.trim(), form.roleKey),
    onSuccess: (m) => {
      setInvite(false);
      void qc.invalidateQueries({ queryKey: sellerKeys.team(orgId) });
      toast.show(`Invitation sent to ${m.email ?? m.name}`);
    },
    onError: (e) => {
      const code = isApiError(e) ? e.code : undefined;
      setErrors(code === 'INVALID_ROLE' || code === 'OWNER_ONLY' ? { roleKey: teamError(e) } : { email: teamError(e) });
    }
  });
  const assign = useMutation({
    mutationFn: (v: { userId: string; roleKey: string }) => api.assignRole(v.userId, v.roleKey),
    onSuccess: (t) => {
      qc.setQueryData(sellerKeys.team(orgId), t);
      toast.show('Role updated');
    },
    onError: (e) => {
      toast.show(teamError(e), 'error');
      void qc.invalidateQueries({ queryKey: sellerKeys.team(orgId) });
    }
  });
  const removeM = useMutation({
    mutationFn: (p: NonNullable<Pending>) => (p.kind === 'revoke' ? api.revokeInvitation(p.member.invitationId ?? '') : api.removeMember(p.member.userId)),
    onSuccess: (t, p) => {
      qc.setQueryData(sellerKeys.team(orgId), t);
      setPending(null);
      toast.show(p.kind === 'revoke' ? `Invitation to ${p.member.email ?? p.member.name} withdrawn` : `${p.member.name} was removed`);
    },
    onError: (e) => toast.show(teamError(e), 'error')
  });
  const canManage = q.data?.canManage ?? false;
  const columns = useMemo<ColumnDef<TeamMember>[]>(
    () => [
      { id: 'name', accessorFn: (m) => m.name, header: 'Name', size: 220, meta: { kind: 'strong' }, cell: ({ row }) => <strong className="font-semibold">{row.original.name}{row.original.isYou ? <span className="font-normal text-muted"> (you)</span> : null}</strong> },
      { id: 'email', accessorFn: (m) => m.email ?? undefined, header: 'Email', size: 240, cell: ({ row }) => row.original.email ?? '—' },
      {
        id: 'role', accessorFn: (m) => m.roleLabel, header: 'Role', size: 200,
        cell: ({ row }) =>
          canManage && !row.original.isYou && row.original.status !== 'INVITED' ? (
            <label className="flex flex-col text-xs">
              <span className="at-visually-hidden">Role of {row.original.name}</span>
              <select
                className="h-8 rounded-sm border border-border-strong bg-surface px-2 text-sm"
                value={row.original.roleKey}
                disabled={assign.isPending}
                onChange={(e) => assign.mutate({ userId: row.original.userId, roleKey: e.target.value })}
              >
                {roleOptions.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
              </select>
            </label>
          ) : (
            row.original.roleLabel
          )
      },
      {
        id: 'status', accessorFn: (m) => m.status, header: 'Status', size: 150, meta: { kind: 'status' },
        cell: ({ row }) => (
          <span className="flex flex-col gap-0.5">
            <StatusPill tone={toneOf(row.original.status)}>{row.original.status.toLowerCase()}</StatusPill>
            {row.original.status === 'INVITED' && row.original.expiresAt ? <span className="text-xs text-muted">Expires {shortDate(row.original.expiresAt)}</span> : null}
          </span>
        )
      },
      {
        id: 'actions', header: () => <span className="at-visually-hidden">Actions</span>, size: 130, enableSorting: false, meta: { export: false, label: 'Actions' },
        cell: ({ row }) => {
          const m = row.original;
          if (!canManage || m.isYou) return null;
          return m.status === 'INVITED' ? (
            <Button variant="neutral" size="sm" onClick={() => setPending({ kind: 'revoke', member: m })} aria-label={`Withdraw the invitation to ${m.email ?? m.name}`}>
              Withdraw
            </Button>
          ) : (
            <Button variant="neutral" size="sm" onClick={() => setPending({ kind: 'remove', member: m })} aria-label={`Remove ${m.name}`}>
              Remove
            </Button>
          );
        }
      }
    ],
    [canManage, assign, roleOptions]
  );
  if (q.isPending) return <ModulePage module="team"><Spinner /></ModulePage>;
  if (q.isError) return <ModulePage module="team"><ErrorState error={q.error} onRetry={() => void q.refetch()} /></ModulePage>;
  return (
    <ModulePage
      module="team"
      actions={
        <>
          <Link href="/team/business-page" className={buttonClassName({ variant: 'neutral' })}>Business page</Link>
          {canManage ? <Button onClick={() => { setErrors({}); setForm({ email: '', roleKey: roleOptions[1]?.value ?? '' }); setInvite(true); }}>Invite member</Button> : null}
        </>
      }
    >
      <DataTable<TeamMember> id="seller.team" label="Team members" columns={columns} data={q.data.members} getRowId={(m) => m.userId} rowLabel={(m) => m.name}
        state={state} mode="client" fetching={q.isFetching} empty="No members yet." onExport={exportDataset} pollExport={pollExport} />
      <h2 className="m-0 text-lg font-semibold">What each role can and can’t do</h2>
      <RoleCards roles={q.data.roles} yours={q.data.yourRoleKey} />
      <Dialog open={invite} onOpenChange={setInvite} title="Invite a team member" description="They get an email to join with their own AfroTransact account. Give each person the least access they need.">
        <form
          className="flex flex-col gap-3"
          onSubmit={(e) => {
            e.preventDefault();
            const p = InviteFormSchema.safeParse(form);
            if (!p.success) return setErrors(Object.fromEntries(p.error.issues.map((i) => [String(i.path[0]), i.message])));
            setErrors({});
            inviteM.mutate();
          }}
        >
          <FormErrors errors={errors} ids={{ email: 'invite-email', roleKey: 'invite-role' }} />
          <TextField id="invite-email" label="Email" type="email" autoComplete="email" value={form.email} error={errors.email} onChange={(e) => setForm({ ...form, email: e.target.value })} />
          <Select id="invite-role" label="Role" options={roleOptions} value={form.roleKey} error={errors.roleKey} onChange={(e) => setForm({ ...form, roleKey: e.target.value })} />
          <Button type="submit" loading={inviteM.isPending}>Send invitation</Button>
        </form>
      </Dialog>
      <Dialog
        open={pending !== null}
        onOpenChange={(open) => (open ? undefined : setPending(null))}
        title={pending?.kind === 'revoke' ? 'Withdraw this invitation?' : `Remove ${pending?.member.name ?? 'this member'}?`}
        description={
          pending?.kind === 'revoke'
            ? `The link sent to ${pending.member.email ?? pending.member.name} stops working. You can invite them again later.`
            : 'They lose access to this organisation straight away. Their past work stays in the records.'
        }
      >
        <div className="flex justify-end gap-2">
          <Button variant="neutral" onClick={() => setPending(null)}>Cancel</Button>
          <Button variant="dark" loading={removeM.isPending} onClick={() => pending && removeM.mutate(pending)}>
            {pending?.kind === 'revoke' ? 'Withdraw invitation' : 'Remove member'}
          </Button>
        </div>
      </Dialog>
    </ModulePage>
  );
}
