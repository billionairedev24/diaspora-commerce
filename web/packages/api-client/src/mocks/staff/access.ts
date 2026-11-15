/* Settings > Roles and permissions in the mock staff BFF (gateway-bff-staff.yaml tag Access), mirroring internal/app/staff_access.go:
   the catalogue is data (access-catalogue.ts, generated from identity's migrations), a change needs a reason, access:admin and Edit
   on Users & roles, Super Admin keeps its two access permissions, and every change lands in the change log with names. */
import { http } from 'msw';
import { z } from 'zod';

import { ACCESS_PORTALS, type AccessLogEntry, type AccessPerson, type AccessRole, type AccessRoleMember } from '../../staff-access';
import { MOCK_OTHER_ROLES, MOCK_PERMISSIONS } from './access-catalogue';
import { allows } from './model';
import { type PreorderCtx, type PreorderHandlerDeps } from './preorders';
import { ROLE_LABELS, ROLE_PERMISSIONS } from './rbac-data';

interface MockPerson {
  id: string;
  name: string;
  email: string;
  enabled: boolean;
  principalType: string;
  roles: string[];
  directGrants: string[];
  roleMarkets: Record<string, string[]>;
}

interface MockAccessState {
  catalogue: { key: string; module: string; description: string }[];
  roles: Record<string, { key: string; portal: string; name: string; description: string; permissions: string[] }>;
  people: MockPerson[];
  log: AccessLogEntry[];
  seq: number;
}

const ROLE_DESCRIPTIONS: Record<string, string> = {
  'platform.super_admin': 'Everything, including access control',
  'platform.operations_manager': 'Orders, applications, fulfilment and delivery operations',
  'platform.trust_safety': 'Application review, listing moderation, disputes and payout holds',
  'platform.finance': 'Payouts, ledger, fees and refunds',
  'platform.customer_support': 'Customer lookups, orders, refunds and returns',
  'platform.marketing': 'Ads, announcements, coupons and site content',
  'platform.catalog_manager': 'Listing moderation and the category tree',
  'platform.retail_buyer': 'AfroTransact Retail products, suppliers and purchase orders',
  'platform.fulfilment_ops': 'Fulfilment centres, stock and AfroTransact-fulfilled shipments',
  'platform.inventory_manager': 'Stock positions, receiving, adjustments and cycle counts',
  'platform.dispatcher': 'Routes, drivers and internal delivery',
  'platform.product_owner': 'Configuration and feature flags; can view access control',
  'platform.developer': 'Developers area, integrations and access control',
  'platform.auditor': 'Read-only access everywhere, including access control',
  'platform.driver': 'Uses the driver app'
};

const LOCKED: Record<string, string[]> = { 'platform.super_admin': ['access:admin', 'access:read'] };
const states = new WeakMap<object, MockAccessState>();

function seed(now: Date): MockAccessState {
  const ago = (days: number) => new Date(now.getTime() - days * 86_400_000).toISOString();
  const roles: MockAccessState['roles'] = {};
  Object.entries(ROLE_PERMISSIONS).forEach(([key, permissions]) => {
    roles[key] = { key, portal: 'platform', name: ROLE_LABELS[key] ?? key, description: ROLE_DESCRIPTIONS[key] ?? '', permissions: [...permissions] };
  });
  MOCK_OTHER_ROLES.forEach((r) => (roles[r.key] = { ...r, permissions: [...r.permissions] }));
  const person = (id: string, name: string, email: string, role: string, extra: Partial<MockPerson> = {}): MockPerson => ({
    id, name, email, enabled: true, principalType: 'STAFF', roles: [role], directGrants: [], roleMarkets: {}, ...extra
  });
  const people = [
    person('stf-1', 'Ifeoma Okafor', 'ifeoma@afrotransact.com', 'platform.super_admin'),
    person('stf-2', 'Sara Tesfaye', 'sara@afrotransact.com', 'platform.trust_safety'),
    person('stf-3', 'Daniel Mensah', 'daniel@afrotransact.com', 'platform.operations_manager', { directGrants: ['delivery:operate'] }),
    person('stf-4', 'Grace Adjei', 'grace@afrotransact.com', 'platform.customer_support', { roleMarkets: { 'platform.customer_support': ['GB', 'US'] } }),
    person('stf-5', 'Elena Petrova', 'elena@afrotransact.com', 'platform.developer'),
    person('stf-6', 'Musa Bello', 'musa@afrotransact.com', 'platform.finance'),
    person('stf-7', 'Kofi Mensah', 'kofi@afrotransact.com', 'platform.auditor'),
    person('usr-1', 'Amara Nwosu', 'amara@example.com', 'account.customer', { principalType: 'CUSTOMER' })
  ];
  const entry = (id: number, daysAgo: number, actor: string, action: string, over: Partial<AccessLogEntry>): AccessLogEntry => ({
    id: String(id), at: ago(daysAgo), actorName: actor, action, actionLabel: action.toLowerCase().replace(/_/g, ' ').replace(/^./, (c) => c.toUpperCase()), targetName: '', role: '', roleName: '', subject: '', detail: '', ...over
  });
  const log: AccessLogEntry[] = [
    entry(3, 2, 'Ifeoma Okafor', 'ROLE_PERMISSION_ADDED', { role: 'platform.finance', roleName: 'Finance', subject: 'refunds:issue', detail: 'Quarter close needs refunds' }),
    entry(2, 6, 'Ifeoma Okafor', 'PERMISSION_GRANTED', { targetName: 'Daniel Mensah', subject: 'delivery:operate', detail: 'Covers the driver desk' }),
    entry(1, 20, 'Ifeoma Okafor', 'ROLE_ASSIGNED', { targetName: 'Grace Adjei', role: 'platform.customer_support', roleName: 'Customer Support', subject: 'platform.customer_support', detail: 'Joined support' })
  ];
  return { catalogue: MOCK_PERMISSIONS.map((p) => ({ ...p })), roles, people, log, seq: 3 };
}

const state = (ctx: PreorderCtx): MockAccessState => {
  let s = states.get(ctx.db);
  if (!s) {
    s = seed(ctx.now);
    states.set(ctx.db, s);
  }
  return s;
};

/** Test hook: adds a permission to the catalogue, as an identity migration would (no code change in the console). */
export function addMockPermission(db: object, now: Date, p: { key: string; module: string; description: string }): void {
  let s = states.get(db);
  if (!s) {
    s = seed(now);
    states.set(db, s);
  }
  if (!s.catalogue.some((x) => x.key === p.key)) s.catalogue.push(p);
}

const ChangeBody = z.object({ reason: z.string().default('') });
const roleView = (r: MockAccessState['roles'][string]): AccessRole => ({ key: r.key, portal: r.portal, name: r.name, description: r.description, permissions: [...r.permissions].sort(), permissionCount: r.permissions.length });

function effective(s: MockAccessState, p: MockPerson): string[] {
  return [...new Set([...p.roles.flatMap((r) => s.roles[r]?.permissions ?? []), ...p.directGrants])].sort();
}
const personView = (s: MockAccessState, p: MockPerson): AccessPerson => ({
  id: p.id, name: p.name, email: p.email, enabled: p.enabled, principalType: p.principalType,
  roles: p.roles.map((key) => ({ key, name: s.roles[key]?.name ?? key })), directGrants: [...p.directGrants].sort(), effectivePermissions: effective(s, p), roleMarkets: p.roleMarkets
});

export function accessHandlers(W: string, deps: PreorderHandlerDeps) {
  const { route, json, problem } = deps;
  const A = `${W}/access`;
  const forbidden = (detail: string) => problem(403, 'FORBIDDEN', 'Forbidden', detail);
  const read = (ctx: PreorderCtx) => {
    if (!allows(ctx.roles, 'rbac')) throw forbidden('Your roles can’t open users & roles.');
    if (!ctx.permissions.includes('access:read')) throw forbidden('This needs the access:read permission.');
    return state(ctx);
  };
  const canAdmin = (ctx: PreorderCtx) => allows(ctx.roles, 'rbac', 'EDIT') && ctx.permissions.includes('access:admin');
  const write = async (ctx: PreorderCtx) => {
    if (!allows(ctx.roles, 'rbac', 'EDIT')) throw forbidden('Your roles can’t change users & roles.');
    if (!ctx.permissions.includes('access:admin')) throw forbidden('This needs the access:admin permission.');
    const { reason } = await json(ctx.request, ChangeBody);
    const text = reason.trim();
    if (text.length < 3 || text.length > 300) throw problem(422, 'VALIDATION_FAILED', 'Validation failed', 'One or more fields are invalid.', { reason: 'Give a reason of 3 to 300 characters' });
    return { s: state(ctx), reason: text };
  };
  const notFound = (what: string) => problem(404, 'NOT_FOUND', 'Not found', `No such ${what}.`);
  const note = (s: MockAccessState, ctx: PreorderCtx, e: Partial<AccessLogEntry>) => {
    s.seq += 1;
    s.log.unshift({ id: String(s.seq), at: ctx.now.toISOString(), actorName: ctx.action.actor, action: '', actionLabel: '', targetName: '', role: '', roleName: '', subject: '', detail: '', ...e });
  };
  return [
    http.get(`${A}/permissions`, route((ctx) => ({ items: [...read(ctx).catalogue].sort((a, b) => a.module.localeCompare(b.module) || a.key.localeCompare(b.key)) }))),
    http.get(
      `${A}/roles`,
      route((ctx) => {
        const s = read(ctx);
        const portal = new URL(ctx.request.url).searchParams.get('portal') ?? 'platform';
        if (!(ACCESS_PORTALS as readonly string[]).includes(portal)) throw problem(422, 'VALIDATION_FAILED', 'Validation failed', 'One or more fields are invalid.', { portal: 'Choose platform, seller, provider or account' });
        return { portal, items: Object.values(s.roles).filter((r) => r.portal === portal).map(roleView), canAdmin: canAdmin(ctx) };
      })
    ),
    http.get(
      `${A}/roles/:role/members`,
      route((ctx, p) => {
        const s = read(ctx);
        const items: AccessRoleMember[] = s.people.filter((x) => x.roles.includes(p.role ?? '')).map((x) => ({ id: x.id, name: x.name, email: x.email, enabled: x.enabled, markets: x.roleMarkets[p.role ?? ''] ?? [] }));
        return { role: p.role, items, partial: false };
      })
    ),
    ...(['put', 'delete'] as const).map((method) =>
      http[method](
        `${A}/roles/:role/permissions/:permission`,
        route(async (ctx, p) => {
          const { s, reason } = await write(ctx);
          const role = s.roles[p.role ?? ''];
          if (!role) throw notFound('role');
          const key = p.permission ?? '';
          if (!s.catalogue.some((c) => c.key === key)) throw problem(422, 'VALIDATION_FAILED', 'Validation failed', 'One or more fields are invalid.', { permissionKey: 'Not a permission in the catalogue' });
          if (method === 'delete' && LOCKED[role.key]?.includes(key)) throw problem(409, 'ACCESS_LOCKOUT', 'Can’t remove this', `Super Admin keeps ${key} so the console can always be administered.`);
          if (method === 'put' && !role.permissions.includes(key)) role.permissions.push(key);
          if (method === 'delete') role.permissions = role.permissions.filter((x) => x !== key);
          note(s, ctx, { action: method === 'put' ? 'ROLE_PERMISSION_ADDED' : 'ROLE_PERMISSION_REMOVED', actionLabel: method === 'put' ? 'Role permission added' : 'Role permission removed', role: role.key, roleName: role.name, subject: key, detail: reason });
          return roleView(role);
        })
      )
    ),
    http.get(
      `${A}/people`,
      route((ctx) => {
        const s = read(ctx);
        const q = new URL(ctx.request.url).searchParams;
        const needle = (q.get('q') ?? '').trim().toLowerCase();
        const page = Number(q.get('page') ?? 0);
        const size = Number(q.get('size') ?? 25);
        const rows = s.people.filter((x) => !needle || `${x.name} ${x.email}`.toLowerCase().includes(needle));
        return { items: rows.slice(page * size, page * size + size).map((x) => personView(s, x)), total: rows.length, page, size };
      })
    ),
    http.get(
      `${A}/people/:id`,
      route((ctx, p) => {
        const s = read(ctx);
        const who = s.people.find((x) => x.id === p.id);
        if (!who) throw notFound('person');
        return personView(s, who);
      })
    ),
    ...(['put', 'delete'] as const).map((method) =>
      http[method](
        `${A}/people/:id/permissions/:permission`,
        route(async (ctx, p) => {
          const { s, reason } = await write(ctx);
          const who = s.people.find((x) => x.id === p.id);
          if (!who) throw notFound('person');
          const key = p.permission ?? '';
          if (!s.catalogue.some((c) => c.key === key)) throw problem(422, 'VALIDATION_FAILED', 'Validation failed', 'One or more fields are invalid.', { permissionKey: 'Not a permission in the catalogue' });
          if (method === 'put' && !who.directGrants.includes(key)) who.directGrants.push(key);
          if (method === 'delete') who.directGrants = who.directGrants.filter((x) => x !== key);
          note(s, ctx, { action: method === 'put' ? 'PERMISSION_GRANTED' : 'PERMISSION_REVOKED', actionLabel: method === 'put' ? 'Permission granted' : 'Permission revoked', targetName: who.name, subject: key, detail: reason });
          return personView(s, who);
        })
      )
    ),
    http.get(
      `${A}/log`,
      route((ctx) => {
        const s = read(ctx);
        const q = new URL(ctx.request.url).searchParams;
        const who = s.people.find((x) => x.id === q.get('userId'));
        const role = q.get('role') ?? '';
        const perm = q.get('permission') ?? '';
        const rows = s.log.filter((e) => (!who || e.targetName === who.name) && (!role || e.role === role) && (!perm || e.subject === perm));
        const page = Number(q.get('page') ?? 0);
        const size = Number(q.get('size') ?? 50);
        return { items: rows.slice(page * size, page * size + size), page, size, partial: false };
      })
    )
  ];
}
