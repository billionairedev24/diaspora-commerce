'use client';

import { type AccessPermission, type AccessRole, isApiError, staffAccessKeys, staffAccessQueries } from '@afrotransact/api-client';
import { Select, TextField, Toolbar, useToast } from '@afrotransact/ui';
import { CaretLeft } from '@phosphor-icons/react/ssr';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useMemo, useState } from 'react';

import { Notice } from '@/components/ui/Notice';
import { ErrorState, problemMessage, Spinner } from '@/components/ui/States';
import { SwitchOnly } from '@/components/ui/Switch';
import { useT } from '@/i18n/t';
import { groupPermissions, permissionLabel, permissionMatches } from '@/lib/access';
import { staffApi } from '@/lib/api';

import { PermissionList } from './PermissionList';
import { ReasonDialog } from './ReasonDialog';
import { useAccessNav } from './useAccessNav';

type Show = 'all' | 'on' | 'off';

/** Who holds the role, by name, with the market a role is limited to. */
function Members({ role }: { role: AccessRole }) {
  const t = useT();
  const api = staffApi();
  const nav = useAccessNav();
  const q = useQuery(staffAccessQueries.members(api.access, role.key));
  if (q.isPending) return <Spinner center={false} />;
  if (q.isError) return <p className="m-0 text-sm text-muted">{problemMessage(q.error)}</p>;
  if (q.data.items.length === 0) return <p className="m-0 text-sm text-muted">{t('st.acc.members.none')}</p>;
  return (
    <>
      <ul className="m-0 flex list-none flex-col p-0" data-testid="role-members">
        {q.data.items.map((m) => (
          <li key={m.id} className="flex min-h-11 flex-wrap items-center gap-x-3 border-b border-border py-1 first:border-t">
            <button type="button" onClick={() => nav.go({ view: 'people', person: m.id, role: null }, false)} className="cursor-pointer border-0 bg-transparent p-0 text-sm font-semibold text-link underline">
              {m.name}
            </button>
            <span className="text-sm text-muted">{m.email}</span>
            {m.markets.length ? <span className="text-sm text-muted">{t('st.acc.members.markets', { markets: m.markets.join(', ') })}</span> : null}
            {!m.enabled ? <span className="text-sm text-warning">{t('st.acc.members.disabled')}</span> : null}
          </li>
        ))}
      </ul>
      {q.data.partial ? <p className="m-0 text-xs text-muted">{t('st.acc.members.partial')}</p> : null}
    </>
  );
}

/**
 * One role: who has it, and its permissions grouped by module, each one a switch. Turning one on or off asks for confirmation and a
 * reason, then changes it at identity; the new access reaches a person's sign-in on their next token (about 5 minutes). Staff who may
 * only look see the same screen with the switches off and a note.
 */
export function RoleDetail({ role, canAdmin }: { role: AccessRole; canAdmin: boolean }) {
  const t = useT();
  const api = staffApi();
  const qc = useQueryClient();
  const toast = useToast();
  const nav = useAccessNav();
  const catalogue = useQuery(staffAccessQueries.permissions(api.access));
  const [search, setSearch] = useState('');
  const [show, setShow] = useState<Show>('all');
  const [module, setModule] = useState('');
  const [ask, setAsk] = useState<{ permission: AccessPermission; on: boolean } | null>(null);
  const [error, setError] = useState<string | null>(null);
  const held = useMemo(() => new Set(role.permissions), [role.permissions]);

  const change = useMutation({
    mutationFn: (v: { key: string; on: boolean; reason: string }) => api.access.setRolePermission(role.key, v.key, v.on, v.reason),
    retry: false,
    onSuccess: (_done, v) => {
      void qc.invalidateQueries({ queryKey: staffAccessKeys.all });
      toast.show(t(v.on ? 'st.acc.toast.added' : 'st.acc.toast.removed', { permission: permissionLabel(v.key), role: role.name }));
      setAsk(null);
      setError(null);
    },
    onError: (e) => setError(isApiError(e) && e.fieldErrors.reason ? e.fieldErrors.reason : problemMessage(e))
  });

  const groups = useMemo(() => {
    if (!catalogue.data) return [];
    return groupPermissions(catalogue.data.items, role.permissions)
      .filter((g) => !module || g.module === module)
      .map((g) => ({ ...g, items: g.items.filter((p) => permissionMatches(p, search) && (show === 'all' || (show === 'on') === held.has(p.key))) }))
      .filter((g) => g.items.length > 0);
  }, [catalogue.data, role.permissions, module, search, show, held]);
  const modules = useMemo(() => (catalogue.data ? groupPermissions(catalogue.data.items, role.permissions).map((g) => ({ value: g.module, label: g.label })) : []), [catalogue.data, role.permissions]);

  return (
    <div className="flex flex-col gap-6" data-testid="role-detail">
      <div className="flex flex-col gap-1">
        <button type="button" onClick={() => nav.go({ role: null })} className="inline-flex min-h-11 cursor-pointer items-center gap-1 self-start border-0 bg-transparent p-0 text-sm text-link underline">
          <CaretLeft size={14} aria-hidden className="rtl:rotate-180" /> {t('st.acc.roles.back')}
        </button>
        <h2 className="m-0 text-2xl font-bold">{role.name}</h2>
        {role.description ? <p className="m-0 text-muted">{role.description}</p> : null}
        <p className="m-0 text-sm text-muted">{t('st.acc.role.summary', { count: role.permissionCount })}</p>
      </div>
      {!canAdmin ? (
        <Notice tone="info" icon="eye" title={t('st.acc.readOnly.title')}>
          {t('st.acc.readOnly.body')}
        </Notice>
      ) : null}
      <section aria-labelledby="role-members" className="flex flex-col gap-2">
        <h3 id="role-members" className="m-0 text-md font-bold">{t('st.acc.members.title')}</h3>
        <Members role={role} />
      </section>
      <section aria-labelledby="role-permissions" className="flex flex-col gap-3">
        <h3 id="role-permissions" className="m-0 text-md font-bold">{t('st.acc.permissions.title')}</h3>
        <Toolbar data-testid="permission-filters">
          <TextField label={t('st.acc.filter.search')} type="search" value={search} onChange={(e) => setSearch(e.currentTarget.value)} placeholder={t('st.acc.filter.searchPlaceholder')} />
          <Select
            label={t('st.acc.filter.show')}
            value={show}
            onChange={(e) => setShow(e.currentTarget.value as Show)}
            options={[
              { value: 'all', label: t('st.acc.filter.showAll') },
              { value: 'on', label: t('st.acc.filter.showOn') },
              { value: 'off', label: t('st.acc.filter.showOff') }
            ]}
          />
          <Select label={t('st.acc.filter.module')} value={module} onChange={(e) => setModule(e.currentTarget.value)} options={[{ value: '', label: t('st.acc.filter.allModules') }, ...modules]} />
        </Toolbar>
        {catalogue.isPending ? <Spinner /> : catalogue.isError ? <ErrorState error={catalogue.error} onRetry={() => void catalogue.refetch()} /> : (
          <PermissionList
            groups={groups}
            empty={t('st.acc.permissions.none')}
            isOn={(p) => held.has(p.key)}
            control={(p) => (
              <SwitchOnly
                ariaLabel={t('st.acc.switch.label', { permission: permissionLabel(p.key), role: role.name })}
                checked={held.has(p.key)}
                disabled={!canAdmin || change.isPending}
                onChange={(on) => {
                  setError(null);
                  setAsk({ permission: p, on });
                }}
              />
            )}
          />
        )}
      </section>
      <ReasonDialog
        open={ask !== null}
        onOpenChange={(o) => {
          if (!o) {
            setAsk(null);
            setError(null);
          }
        }}
        title={ask ? t(ask.on ? 'st.acc.confirm.addTitle' : 'st.acc.confirm.removeTitle', { permission: permissionLabel(ask.permission.key), role: role.name }) : ''}
        confirmLabel={ask?.on ? t('st.acc.confirm.add') : t('st.acc.confirm.remove')}
        danger={ask?.on === false}
        pending={change.isPending}
        error={error}
        onConfirm={(reason) => ask && change.mutate({ key: ask.permission.key, on: ask.on, reason })}
      >
        {ask ? (
          <>
            <p className="m-0">{ask.permission.description || permissionLabel(ask.permission.key)}</p>
            <p className="mt-2 mb-0">{t(ask.on ? 'st.acc.confirm.addBody' : 'st.acc.confirm.removeBody', { role: role.name })}</p>
          </>
        ) : null}
      </ReasonDialog>
    </div>
  );
}
