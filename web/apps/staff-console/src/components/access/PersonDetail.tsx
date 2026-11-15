'use client';

import { ACCESS_PORTALS, type AccessPerson, isApiError, staffAccessKeys, staffAccessQueries } from '@afrotransact/api-client';
import { Button, Select, TextField, Toolbar, useToast } from '@afrotransact/ui';
import { CaretLeft } from '@phosphor-icons/react/ssr';
import { useMutation, useQueries, useQuery, useQueryClient } from '@tanstack/react-query';
import { useMemo, useState } from 'react';

import { Notice } from '@/components/ui/Notice';
import { ErrorState, problemMessage, Spinner } from '@/components/ui/States';
import { useT } from '@/i18n/t';
import { groupPermissions, indexRoles, logSubject, permissionLabel, permissionMatches, sourceOf } from '@/lib/access';
import { staffApi } from '@/lib/api';
import { dateTime } from '@/lib/format';
import { useStaff } from '@/lib/session';

import { PermissionList } from './PermissionList';
import { ReasonDialog } from './ReasonDialog';
import { useAccessNav } from './useAccessNav';

/** One person: their roles, what they can do now (role permissions plus direct grants, each saying where it comes from), and their changes. */
export function PersonDetail({ id }: { id: string }) {
  const t = useT();
  const api = staffApi();
  const nav = useAccessNav();
  const person = useQuery(staffAccessQueries.person(api.access, id));
  const back = (
    <button type="button" onClick={() => nav.go({ person: null })} className="inline-flex min-h-11 cursor-pointer items-center gap-1 self-start border-0 bg-transparent p-0 text-sm text-link underline">
      <CaretLeft size={14} aria-hidden className="rtl:rotate-180" /> {t('st.acc.people.back')}
    </button>
  );
  if (person.isPending) return <Spinner />;
  if (person.isError) {
    return (
      <div className="flex flex-col gap-3">
        {back}
        <ErrorState error={person.error} onRetry={() => void person.refetch()} />
      </div>
    );
  }
  return (
    <div className="flex flex-col gap-6" data-testid="person-detail">
      <div className="flex flex-col gap-1">
        {back}
        <h2 className="m-0 text-2xl font-bold">{person.data.name}</h2>
        <p className="m-0 text-muted">{person.data.email}</p>
        <p className="m-0 text-sm text-muted">
          {person.data.roles.length ? t('st.acc.person.roles', { roles: person.data.roles.map((r) => r.name).join(', ') }) : t('st.acc.person.noRoles')}
          {person.data.enabled ? '' : ` · ${t('st.acc.people.disabled')}`}
        </p>
      </div>
      <Effective person={person.data} />
      <PersonLog person={person.data} />
    </div>
  );
}

function Effective({ person }: { person: AccessPerson }) {
  const t = useT();
  const api = staffApi();
  const qc = useQueryClient();
  const toast = useToast();
  const { can, hasPermission } = useStaff();
  const canAdmin = can('rbac', 'EDIT') && hasPermission('access:admin');
  const catalogue = useQuery(staffAccessQueries.permissions(api.access));
  const roleLists = useQueries({ queries: ACCESS_PORTALS.map((p) => staffAccessQueries.roles(api.access, p)) });
  const rolesByKey = useMemo(() => indexRoles(roleLists.map((r) => r.data?.items)), [roleLists]);
  const [search, setSearch] = useState('');
  const [grant, setGrant] = useState('');
  const [ask, setAsk] = useState<{ permission: string; on: boolean } | null>(null);
  const [error, setError] = useState<string | null>(null);

  const change = useMutation({
    mutationFn: (v: { permission: string; on: boolean; reason: string }) => api.access.setPersonPermission(person.id, v.permission, v.on, v.reason),
    retry: false,
    onSuccess: (done, v) => {
      qc.setQueryData(staffAccessKeys.person(person.id), done);
      void qc.invalidateQueries({ queryKey: staffAccessKeys.all });
      toast.show(t(v.on ? 'st.acc.toast.granted' : 'st.acc.toast.revoked', { permission: permissionLabel(v.permission), name: person.name }));
      setAsk(null);
      setError(null);
      setGrant('');
    },
    onError: (e) => setError(isApiError(e) && e.fieldErrors.reason ? e.fieldErrors.reason : problemMessage(e))
  });

  const groups = useMemo(() => {
    if (!catalogue.data) return [];
    const keys = new Set(person.effectivePermissions);
    return groupPermissions(catalogue.data.items, person.effectivePermissions)
      .map((g) => ({ ...g, items: g.items.filter((p) => keys.has(p.key) && permissionMatches(p, search)) }))
      .filter((g) => g.items.length > 0);
  }, [catalogue.data, person.effectivePermissions, search]);
  const grantable = useMemo(
    () => (catalogue.data?.items ?? []).filter((p) => !person.directGrants.includes(p.key)).map((p) => ({ value: p.key, label: permissionLabel(p.key) })),
    [catalogue.data, person.directGrants]
  );
  const askPermission = ask ? (catalogue.data?.items.find((p) => p.key === ask.permission) ?? null) : null;

  return (
    <section aria-labelledby="person-effective" className="flex flex-col gap-3">
      <h3 id="person-effective" className="m-0 text-md font-bold">{t('st.acc.person.canDo', { name: person.name })}</h3>
      <p className="m-0 text-sm text-muted">{t('st.acc.person.canDoHelp', { count: person.effectivePermissions.length })}</p>
      <Toolbar>
        <TextField label={t('st.acc.filter.search')} type="search" value={search} onChange={(e) => setSearch(e.currentTarget.value)} placeholder={t('st.acc.filter.searchPlaceholder')} />
      </Toolbar>
      {catalogue.isPending ? (
        <Spinner />
      ) : (
        <PermissionList
          groups={groups}
          empty={t('st.acc.person.none')}
          control={(p) => {
            const src = sourceOf(person, rolesByKey, p.key);
            const parts = [...(src.roles.length ? [t('st.acc.source.role', { roles: src.roles.join(', ') })] : []), ...(src.direct ? [t('st.acc.source.direct')] : [])];
            return (
              <span className="flex items-center gap-3">
                <span className="text-end text-sm text-muted" data-testid={`source-${p.key}`}>{parts.join(' · ') || t('st.acc.source.org')}</span>
                {canAdmin && src.direct ? (
                  <Button size="sm" variant="secondary" onClick={() => { setError(null); setAsk({ permission: p.key, on: false }); }}>
                    {t('st.acc.revoke')}
                  </Button>
                ) : null}
              </span>
            );
          }}
        />
      )}
      {canAdmin ? (
        <Toolbar data-testid="grant-direct">
          <Select className="w-full sm:w-80" label={t('st.acc.grant.label')} value={grant} onChange={(e) => setGrant(e.currentTarget.value)} options={[{ value: '', label: t('st.acc.grant.choose') }, ...grantable]} />
          <Button variant="secondary" disabled={!grant} onClick={() => { setError(null); setAsk({ permission: grant, on: true }); }}>
            {t('st.acc.grant.button')}
          </Button>
        </Toolbar>
      ) : (
        <Notice tone="info" icon="eye" title={t('st.acc.readOnly.title')}>
          {t('st.acc.readOnly.body')}
        </Notice>
      )}
      <ReasonDialog
        open={ask !== null}
        onOpenChange={(o) => {
          if (!o) {
            setAsk(null);
            setError(null);
          }
        }}
        title={ask ? t(ask.on ? 'st.acc.confirm.grantTitle' : 'st.acc.confirm.revokeTitle', { permission: permissionLabel(ask.permission), name: person.name }) : ''}
        confirmLabel={ask?.on ? t('st.acc.grant.button') : t('st.acc.revoke')}
        danger={ask?.on === false}
        pending={change.isPending}
        error={error}
        onConfirm={(reason) => ask && change.mutate({ permission: ask.permission, on: ask.on, reason })}
      >
        {ask ? (
          <>
            <p className="m-0">{askPermission?.description || permissionLabel(ask.permission)}</p>
            <p className="mt-2 mb-0">{t(ask.on ? 'st.acc.confirm.grantBody' : 'st.acc.confirm.revokeBody', { name: person.name })}</p>
          </>
        ) : null}
      </ReasonDialog>
    </section>
  );
}

/** The person's own recent changes (the full filterable log is its own tab). */
function PersonLog({ person }: { person: AccessPerson }) {
  const t = useT();
  const api = staffApi();
  const nav = useAccessNav();
  const q = useQuery(staffAccessQueries.log(api.access, { userId: person.id, size: 5 }));
  return (
    <section aria-labelledby="person-log" className="flex flex-col gap-2">
      <h3 id="person-log" className="m-0 text-md font-bold">{t('st.acc.person.changes', { name: person.name })}</h3>
      {q.isPending ? <Spinner center={false} /> : q.isError ? <p className="m-0 text-sm text-muted">{problemMessage(q.error)}</p> : q.data.items.length === 0 ? <p className="m-0 text-sm text-muted">{t('st.acc.log.none')}</p> : (
        <ul className="m-0 flex list-none flex-col p-0" data-testid="person-log">
          {q.data.items.map((e) => (
            <li key={e.id} className="flex flex-col border-b border-border py-2 first:border-t">
              <span className="text-sm"><strong>{e.actionLabel}</strong>{logSubject(e) ? `: ${logSubject(e)}` : ''}</span>
              <span className="text-xs text-muted">{t('st.acc.log.by', { name: e.actorName, when: dateTime(e.at) })}{e.detail ? ` · ${e.detail}` : ''}</span>
            </li>
          ))}
        </ul>
      )}
      <button type="button" onClick={() => nav.go({ view: 'log', log: person.id, person: null }, false)} className="cursor-pointer self-start border-0 bg-transparent p-0 text-sm text-link underline">
        {t('st.acc.log.seeAll')}
      </button>
    </section>
  );
}
