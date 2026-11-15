import { type AccessLogEntry, type AccessPermission, type AccessPerson, type AccessRole } from '@afrotransact/api-client';

/* Pure helpers of Settings > Roles and permissions. Nothing here knows a permission by name: labels come from the key's own words and
   the catalogue's description, and groups come from the catalogue's module, so a permission added by an identity migration (for
   example delivery:read and delivery:manage) appears with no change in the console. */

const WORD_SPLIT = /[:.\-_]+/;
const cap = (s: string): string => (s ? s.charAt(0).toUpperCase() + s.slice(1) : s);

/** "refunds:issue" -> "Refunds: issue"; "delivery:dispatch:read" -> "Delivery: dispatch read"; "content:site.view" -> "Content: site view". */
export function permissionLabel(key: string): string {
  const [head = '', ...rest] = key.split(':');
  const first = head.split(WORD_SPLIT).filter(Boolean).join(' ');
  const tail = rest.join(' ').split(WORD_SPLIT).filter(Boolean).join(' ');
  return tail ? `${cap(first)}: ${tail}` : cap(first);
}

/** A catalogue module as a heading: "fulfilment" -> "Fulfilment", "site-config" -> "Site config". */
export const moduleLabel = (module: string): string => cap(module.split(WORD_SPLIT).filter(Boolean).join(' '));

export interface PermissionGroup {
  module: string;
  label: string;
  items: AccessPermission[];
}

/** The catalogue grouped by module (module name order), each group's permissions by key. A key the catalogue does not know goes in "Other". */
export function groupPermissions(catalogue: readonly AccessPermission[], extraKeys: readonly string[] = []): PermissionGroup[] {
  const known = new Set(catalogue.map((p) => p.key));
  const all: AccessPermission[] = [...catalogue, ...extraKeys.filter((k) => !known.has(k)).map((key) => ({ key, module: 'other', description: '' }))];
  const by = new Map<string, AccessPermission[]>();
  all.forEach((p) => by.set(p.module, [...(by.get(p.module) ?? []), p]));
  return [...by.entries()]
    .map(([module, items]) => ({ module, label: moduleLabel(module), items: [...items].sort((a, b) => a.key.localeCompare(b.key)) }))
    .sort((a, b) => (a.module === 'other' ? 1 : b.module === 'other' ? -1 : a.label.localeCompare(b.label)));
}

/** What a search matches: the plain label, the description, the key and the module. */
export const permissionMatches = (p: AccessPermission, q: string): boolean => {
  const needle = q.trim().toLowerCase();
  return !needle || `${permissionLabel(p.key)} ${p.description} ${p.key} ${p.module}`.toLowerCase().includes(needle);
};

/** Where one of a person's permissions comes from: the roles that hold it (by name) and whether it was also granted directly. */
export interface PermissionSource {
  roles: string[];
  direct: boolean;
}

export function sourceOf(person: Pick<AccessPerson, 'roles' | 'directGrants'>, rolesByKey: ReadonlyMap<string, AccessRole>, permission: string): PermissionSource {
  return {
    roles: person.roles.filter((r) => rolesByKey.get(r.key)?.permissions.includes(permission)).map((r) => r.name),
    direct: person.directGrants.includes(permission)
  };
}

/** The roles of every portal by key, for naming where a permission comes from. */
export function indexRoles(lists: readonly (readonly AccessRole[] | undefined)[]): Map<string, AccessRole> {
  return new Map(lists.flatMap((l) => l ?? []).map((r) => [r.key, r]));
}

/** What a change log entry changed, in words: a permission's label when the subject is a permission, otherwise the role's name. */
export function logSubject(e: Pick<AccessLogEntry, 'subject' | 'role' | 'roleName'>): string {
  if (e.subject.includes(':')) return permissionLabel(e.subject);
  return e.roleName || e.role || '';
}
