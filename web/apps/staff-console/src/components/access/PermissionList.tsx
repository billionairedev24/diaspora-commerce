'use client';

import { type AccessPermission } from '@afrotransact/api-client';
import { type ReactNode } from 'react';

import { useT } from '@/i18n/t';
import { type PermissionGroup, permissionLabel } from '@/lib/access';

/**
 * Permissions grouped by catalogue module: a heading with how many are on, then one flat row each with the plain label, the
 * catalogue's description, the key (small, for support conversations) and whatever `control` puts at the end (a switch, or where
 * the permission comes from). Nothing here names a permission: it shows what the catalogue holds.
 */
export function PermissionList({
  groups,
  control,
  isOn,
  empty
}: {
  groups: PermissionGroup[];
  control: (p: AccessPermission) => ReactNode;
  /** Counts the permissions that are on in a group's heading ("3 of 12"); omit for no count. */
  isOn?: (p: AccessPermission) => boolean;
  empty: string;
}) {
  const t = useT();
  if (groups.length === 0) return <p className="m-0 text-sm text-muted">{empty}</p>;
  return (
    <div className="flex flex-col gap-6" data-testid="permission-groups">
      {groups.map((g) => (
        <section key={g.module} aria-labelledby={`perm-group-${g.module}`} data-testid={`perm-group-${g.module}`} className="flex flex-col">
          <h3 id={`perm-group-${g.module}`} className="m-0 flex items-baseline justify-between gap-3 pb-2 text-md font-bold">
            <span>{g.label}</span>
            {isOn ? <span className="text-sm font-normal text-muted">{t('st.acc.group.count', { on: g.items.filter(isOn).length, total: g.items.length })}</span> : null}
          </h3>
          <ul className="m-0 flex list-none flex-col p-0">
            {g.items.map((p) => (
              <li key={p.key} data-testid={`perm-${p.key}`} className="flex min-h-14 items-center gap-4 border-b border-border py-2 first:border-t">
                <span className="flex min-w-0 flex-1 flex-col">
                  <span className="flex flex-wrap items-baseline gap-x-2">
                    <strong className="text-sm">{permissionLabel(p.key)}</strong>
                    <code className="text-2xs text-muted">{p.key}</code>
                  </span>
                  {p.description ? <span className="text-sm text-muted">{p.description}</span> : null}
                </span>
                <span className="flex flex-none items-center">{control(p)}</span>
              </li>
            ))}
          </ul>
        </section>
      ))}
    </div>
  );
}
