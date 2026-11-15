'use client';

import { ACCESS_PORTALS, type AccessModuleLevel, type AccessPortal, isApiError, staffAccessKeys, staffAccessQueries } from '@afrotransact/api-client';
import { Select, TextField, Toolbar, useToast } from '@afrotransact/ui';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useMemo, useState } from 'react';

import { Notice } from '@/components/ui/Notice';
import { ErrorState, problemMessage, Spinner } from '@/components/ui/States';
import { type MessageKey, useT } from '@/i18n/t';
import { staffApi } from '@/lib/api';

import { ReasonDialog } from './ReasonDialog';
import { useAccessNav } from './useAccessNav';

const LEVEL_KEY: Record<AccessModuleLevel, MessageKey> = {
  none: 'st.acc.mx.level.none',
  view: 'st.acc.mx.level.view',
  edit: 'st.acc.mx.level.edit',
  approve: 'st.acc.mx.level.approve',
  full: 'st.acc.mx.level.full'
};
const MEANING_KEY: Record<AccessModuleLevel, MessageKey> = {
  none: 'st.acc.mx.meaning.none',
  view: 'st.acc.mx.meaning.view',
  edit: 'st.acc.mx.meaning.edit',
  approve: 'st.acc.mx.meaning.approve',
  full: 'st.acc.mx.meaning.full'
};
const PORTAL_KEY: Record<AccessPortal, MessageKey> = {
  platform: 'st.acc.portal.platform',
  seller: 'st.acc.portal.seller',
  provider: 'st.acc.portal.provider',
  account: 'st.acc.portal.account'
};

interface Pending {
  module: string;
  moduleLabel: string;
  role: string;
  roleName: string;
  from: AccessModuleLevel;
  to: AccessModuleLevel;
}

/**
 * Module access: what each role may do in each part of the console, as a flat grid (modules down, roles across) of levels: none,
 * view, edit, approve, full. Staff with access:admin change a cell with a confirmation and a required reason; staff with only
 * access:read see the same grid as text. Super Admin always keeps at least Edit on Users & roles and Access control. On a phone one
 * role shows at a time. Changes reach the console within about a minute.
 */
export function ModuleMatrixPanel() {
  const t = useT();
  const api = staffApi();
  const qc = useQueryClient();
  const toast = useToast();
  const nav = useAccessNav();
  const portal: AccessPortal = (ACCESS_PORTALS as readonly string[]).includes(nav.mportal ?? '') ? (nav.mportal as AccessPortal) : 'platform';
  const q = useQuery(staffAccessQueries.modules(api.access, portal));
  const [search, setSearch] = useState('');
  const [picked, setPicked] = useState('');
  const [ask, setAsk] = useState<Pending | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [narrow, setNarrow] = useState(false);
  const data = q.data;

  // On a phone one role at a time keeps every row readable: it starts on the first role unless "All roles" was chosen.
  useEffect(() => {
    if (typeof window.matchMedia !== 'function') return;
    const m = window.matchMedia('(max-width: 40rem)');
    const sync = () => setNarrow(m.matches);
    sync();
    m.addEventListener('change', sync);
    return () => m.removeEventListener('change', sync);
  }, []);
  useEffect(() => setPicked(''), [portal]);
  const role = picked === 'all' ? '' : picked || (narrow ? (data?.roles[0]?.key ?? '') : '');

  const change = useMutation({
    mutationFn: (p: Pending & { reason: string }) => api.access.setModuleLevel(portal, p.module, p.role, p.to, p.reason),
    retry: false,
    onSuccess: (_done, p) => {
      void qc.invalidateQueries({ queryKey: staffAccessKeys.all });
      toast.show(t('st.acc.mx.toast', { role: p.roleName, module: p.moduleLabel, level: t(LEVEL_KEY[p.to]) }));
      setAsk(null);
      setError(null);
    },
    onError: (e) => setError(isApiError(e) && e.fieldErrors.reason ? e.fieldErrors.reason : problemMessage(e))
  });

  const roles = useMemo(() => (data ? data.roles.filter((r) => !role || r.key === role) : []), [data, role]);
  const modules = useMemo(() => {
    const needle = search.trim().toLowerCase();
    return (data?.modules ?? []).filter((m) => !needle || m.label.toLowerCase().includes(needle));
  }, [data, search]);
  const lockedMin = (module: string, roleKey: string) => Boolean(data?.locked.some((l) => l.module === module && l.role === roleKey));
  const rank = (l: AccessModuleLevel) => (data?.levels ?? []).indexOf(l);

  return (
    <div className="flex flex-col gap-3" data-testid="module-matrix">
      <p className="m-0 max-w-[80ch] text-sm text-muted">{t('st.acc.mx.intro')}</p>
      <Toolbar data-testid="matrix-filters">
        <Select
          label={t('st.acc.mx.portal')}
          value={portal}
          onChange={(e) => nav.go({ mportal: e.currentTarget.value === 'platform' ? null : e.currentTarget.value })}
          options={ACCESS_PORTALS.map((p) => ({ value: p, label: t(PORTAL_KEY[p]) }))}
        />
        <Select label={t('st.acc.mx.role')} value={role || 'all'} onChange={(e) => setPicked(e.currentTarget.value)} options={[{ value: 'all', label: t('st.acc.mx.allRoles') }, ...(data?.roles ?? []).map((r) => ({ value: r.key, label: r.name }))]} />
        <TextField label={t('st.acc.mx.search')} type="search" value={search} onChange={(e) => setSearch(e.currentTarget.value)} placeholder={t('st.acc.mx.searchPlaceholder')} />
      </Toolbar>
      {data && !data.canAdmin ? (
        <Notice tone="info" icon="eye" title={t('st.acc.readOnly.title')}>
          {t('st.acc.readOnly.body')}
        </Notice>
      ) : null}
      {q.isPending ? (
        <Spinner />
      ) : q.isError ? (
        <ErrorState error={q.error} onRetry={() => void q.refetch()} />
      ) : data && data.modules.length === 0 ? (
        <p className="m-0 text-sm text-muted">{t('st.acc.mx.noneForPortal')}</p>
      ) : modules.length === 0 ? (
        <p className="m-0 text-sm text-muted">{t('st.acc.mx.empty')}</p>
      ) : (
        // A scrollable region must be reachable by keyboard (axe: scrollable-region-focusable), so a region with tabIndex is right here.
        // eslint-disable-next-line jsx-a11y/no-noninteractive-tabindex
        <div className="overflow-x-auto" role="region" aria-label={t('st.acc.mx.region')} tabIndex={0} data-testid="matrix-scroll">
          <table className="w-max min-w-full border-collapse text-sm" data-testid="matrix-table">
            <thead>
              <tr className="border-b border-border-strong">
                <th scope="col" className="sticky left-0 z-[1] min-w-48 bg-surface py-2 pe-3 text-start font-semibold">
                  {t('st.acc.mx.col.module')}
                </th>
                {roles.map((r) => (
                  <th key={r.key} scope="col" className="min-w-36 px-1.5 py-2 text-start font-semibold">
                    {r.name}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {modules.map((m) => (
                <tr key={m.key} className="border-b border-border" data-testid={`matrix-row-${m.key}`}>
                  <th scope="row" className="sticky left-0 z-[1] bg-surface py-1 pe-3 text-start font-semibold">
                    {m.label}
                  </th>
                  {roles.map((r) => {
                    const level = m.levels[r.key] ?? 'none';
                    const locked = lockedMin(m.key, r.key);
                    return (
                      <td key={r.key} className="px-1.5 py-1 align-middle">
                        {data?.canAdmin ? (
                          <select
                            aria-label={t('st.acc.mx.cell', { module: m.label, role: r.name })}
                            data-testid={`cell-${m.key}-${r.key}`}
                            className="h-9 w-full min-w-32 cursor-pointer rounded-md border border-border-strong bg-surface px-2 text-sm pointer-coarse:h-11"
                            value={level}
                            disabled={change.isPending}
                            onChange={(e) => {
                              setError(null);
                              setAsk({ module: m.key, moduleLabel: m.label, role: r.key, roleName: r.name, from: level, to: e.currentTarget.value as AccessModuleLevel });
                            }}
                          >
                            {data.levels.map((l) => (
                              <option key={l} value={l} disabled={locked && rank(l) < rank('edit')}>
                                {t(LEVEL_KEY[l])}
                              </option>
                            ))}
                          </select>
                        ) : (
                          <span className="flex h-9 items-center" data-testid={`cell-${m.key}-${r.key}`}>
                            {t(LEVEL_KEY[level])}
                          </span>
                        )}
                      </td>
                    );
                  })}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {data && data.locked.length ? <p className="m-0 text-xs text-muted">{t('st.acc.mx.locked')}</p> : null}
      <p className="m-0 text-xs text-muted">{t('st.acc.mx.delay')}</p>
      <ReasonDialog
        open={ask !== null}
        onOpenChange={(o) => {
          if (!o) {
            setAsk(null);
            setError(null);
          }
        }}
        title={ask ? t('st.acc.mx.confirm.title', { role: ask.roleName, module: ask.moduleLabel }) : ''}
        confirmLabel={t('st.acc.mx.confirm.ok')}
        danger={ask ? rank(ask.to) < rank(ask.from) : false}
        pending={change.isPending}
        error={error}
        onConfirm={(reason) => ask && change.mutate({ ...ask, reason })}
      >
        {ask ? (
          <>
            <p className="m-0">{t('st.acc.mx.confirm.change', { from: t(LEVEL_KEY[ask.from]), to: t(LEVEL_KEY[ask.to]) })}</p>
            <p className="mt-2 mb-0">{t(MEANING_KEY[ask.to])}</p>
          </>
        ) : null}
      </ReasonDialog>
    </div>
  );
}
