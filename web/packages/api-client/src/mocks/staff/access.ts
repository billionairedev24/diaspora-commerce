/* Settings > Roles and permissions in the mock staff BFF (gateway-bff-staff.yaml tag Access), mirroring internal/app/staff_access.go:
   the catalogue is data (access-catalogue.ts, generated from identity's migrations), a change needs a reason, access:admin and Edit
   on Users & roles, Super Admin keeps its two access permissions, and every change lands in the change log with names. */
import { http } from 'msw';
import { z } from 'zod';

import { ACCESS_PORTALS, type AccessLogEntry, type AccessModuleLevel, type AccessPerson, type AccessRole, type AccessRoleMember, type StaffInvitation } from '../../staff-access';
import { MOCK_OTHER_ROLES, MOCK_PERMISSIONS } from './access-catalogue';
import { allows } from './model';
import { type PreorderCtx, type PreorderHandlerDeps } from './preorders';
import { MATRIX_MODULES, ROLE_LABELS, ROLE_MATRIX, ROLE_PERMISSIONS } from './rbac-data';

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
  invitations: MockInvitation[];
  /** Platform module access: level by module key, by role key (starts from identity's seed, as generated in rbac-data.ts). */
  matrix: Record<string, Record<string, AccessModuleLevel>>;
  seq: number;
}

/** An invitation with its single-use token. The real token is kept by identity only as a hash; the mock keeps it so a test can open the link. */
interface MockInvitation extends Omit<StaffInvitation, 'status'> {
  token: string;
  state: 'PENDING' | 'ACCEPTED' | 'REVOKED';
}
const INVITATION_TTL_MS = 72 * 3_600_000;

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
const LOCKED_MODULES: Record<string, string[]> = { 'platform.super_admin': ['rbac', 'access'] };
const LEVEL_OF_CODE: Record<string, AccessModuleLevel> = { F: 'full', A: 'approve', E: 'edit', V: 'view', '-': 'none' };
/** Module names as identity's access_module seed words them. */
const MODULE_LABELS: Record<string, string> = {
  dash: 'Dashboard', orders: 'Orders', catalog: 'Listing moderation', inventory: 'Inventory', retail: 'AfroTransact Retail (1P)', dispatch: 'Delivery dispatch', apps: 'Applications', rbac: 'Users & roles',
  payouts: 'Payouts & ledger', fees: 'Fees & commissions', marketing: 'Ads & announcements', disputes: 'Disputes & returns', audit: 'Audit log', markets: 'Markets & locations', siteCfg: 'Site content & legal', dev: 'Developers'
};
const LEVELS: AccessModuleLevel[] = ['none', 'view', 'edit', 'approve', 'full'];
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
  const invitation = (n: number, first: string, last: string, email: string, roleKey: string, sentDaysAgo: number, reason: string): MockInvitation => {
    const sent = new Date(now.getTime() - sentDaysAgo * 86_400_000);
    return {
      id: `0b0b0b0b-0000-4000-8000-00000000000${n}`, name: `${first} ${last}`, firstName: first, lastName: last, email, roleKey, roleName: ROLE_LABELS[roleKey] ?? roleKey, reason,
      invitedByName: 'Ifeoma Okafor', createdAt: sent.toISOString(), lastSentAt: sent.toISOString(), expiresAt: new Date(sent.getTime() + INVITATION_TTL_MS).toISOString(), sendCount: 1,
      token: `mock-token-${n}`, state: 'PENDING'
    };
  };
  const invitations = [
    invitation(1, 'Tunde', 'Bakare', 'tunde@afrotransact.com', 'platform.customer_support', 1, 'Joins support in Lagos'),
    invitation(2, 'Chidi', 'Eze', 'chidi@afrotransact.com', 'platform.finance', 6, 'Covers payouts while Musa is away'),
    invitation(3, 'Ngozi', 'Adeyemi', 'ngozi@afrotransact.com', 'platform.marketing', 0, 'Runs the Lagos launch campaign'),
    invitation(4, 'Kwame', 'Asante', 'kwame@afrotransact.com', 'platform.catalog_manager', 0, 'Moderates new listings')
  ];
  const matrix: MockAccessState['matrix'] = {};
  Object.entries(ROLE_MATRIX).forEach(([role, codes]) => {
    MATRIX_MODULES.forEach((m, i) => {
      (matrix[m] ??= {})[role] = LEVEL_OF_CODE[codes[i] ?? '-'] ?? 'none';
    });
  });
  return { catalogue: MOCK_PERMISSIONS.map((p) => ({ ...p })), roles, people, log, invitations, matrix, seq: 3 };
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
const InviteBody = z.object({ firstName: z.string().default(''), lastName: z.string().default(''), email: z.string().default(''), roleKey: z.string().default(''), reason: z.string().default('') });
const TokenBody = z.object({ token: z.string().default('') });
const AcceptBody = z.object({ token: z.string().default(''), password: z.string().default(''), confirmPassword: z.string().default('') });
const statusOf = (i: MockInvitation, now: Date): 'PENDING' | 'ACCEPTED' | 'REVOKED' | 'EXPIRED' => (i.state === 'PENDING' && new Date(i.expiresAt).getTime() <= now.getTime() ? 'EXPIRED' : i.state);
const invitationView = (i: MockInvitation, now: Date): StaffInvitation => {
  const { token: _token, state: _state, ...rest } = i;
  return { ...rest, status: statusOf(i, now) === 'EXPIRED' ? 'EXPIRED' : 'PENDING' };
};
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
        const q = new URL(ctx.request.url).searchParams;
        const page = Number(q.get('page') ?? 0);
        const size = Number(q.get('size') ?? 50);
        const holders = s.people.filter((x) => x.roles.includes(p.role ?? ''));
        const items: AccessRoleMember[] = holders.slice(page * size, page * size + size).map((x) => ({ id: x.id, name: x.name, email: x.email, enabled: x.enabled, markets: x.roleMarkets[p.role ?? ''] ?? [] }));
        return { role: p.role, items, total: holders.length, page, size };
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
      `${A}/invitations`,
      route((ctx) => {
        const s = read(ctx);
        const q = new URL(ctx.request.url).searchParams;
        const page = Number(q.get('page') ?? 0);
        const size = Number(q.get('size') ?? 25);
        const open = s.invitations.filter((i) => i.state === 'PENDING').sort((a, b) => b.createdAt.localeCompare(a.createdAt));
        const items: StaffInvitation[] = open.slice(page * size, page * size + size).map((i) => invitationView(i, ctx.now));
        return { items, total: open.length, page, size, canAdmin: canAdmin(ctx) };
      })
    ),
    http.post(
      `${A}/invitations`,
      route(async (ctx) => {
        const s = state(ctx);
        if (!allows(ctx.roles, 'rbac', 'EDIT')) throw forbidden('Your roles can’t change users & roles.');
        if (!ctx.permissions.includes('access:admin')) throw forbidden('This needs the access:admin permission.');
        const b = await json(ctx.request, InviteBody);
        const errors: Record<string, string> = {};
        if (!b.firstName.trim()) errors.firstName = 'Enter a name of up to 80 characters';
        if (!b.lastName.trim()) errors.lastName = 'Enter a name of up to 80 characters';
        if (!/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(b.email.trim())) errors.email = 'Enter a valid work email address';
        else if (!b.email.trim().toLowerCase().endsWith('@afrotransact.com')) errors.email = 'Staff are invited with a company email address';
        const role = s.roles[b.roleKey];
        if (!role || role.portal !== 'platform') errors.roleKey = 'Choose a staff role';
        if (b.reason.trim().length < 3 || b.reason.trim().length > 300) errors.reason = 'Give a reason of 3 to 300 characters';
        if (Object.keys(errors).length) throw problem(422, 'VALIDATION_FAILED', 'Validation failed', 'One or more fields are invalid.', errors);
        const email = b.email.trim().toLowerCase();
        if (s.people.some((p) => p.email.toLowerCase() === email)) throw problem(409, 'CONFLICT', 'Conflict', 'An account with this email already exists');
        s.invitations.filter((i) => i.state === 'PENDING' && i.email === email).forEach((i) => (i.state = 'REVOKED'));
        s.seq += 1;
        const now = ctx.now.toISOString();
        const inv: MockInvitation = {
          id: `0b0b0b0b-0000-4000-8000-${String(s.seq).padStart(12, '0')}`, name: `${b.firstName.trim()} ${b.lastName.trim()}`, firstName: b.firstName.trim(), lastName: b.lastName.trim(), email,
          roleKey: b.roleKey, roleName: role?.name ?? b.roleKey, reason: b.reason.trim(), invitedByName: ctx.action.actor, createdAt: now, lastSentAt: now,
          expiresAt: new Date(ctx.now.getTime() + INVITATION_TTL_MS).toISOString(), sendCount: 1, token: `mock-token-new-${s.seq}`, state: 'PENDING'
        };
        s.invitations.push(inv);
        note(s, ctx, { action: 'STAFF_INVITATION_CREATED', actionLabel: 'Staff invitation created', targetName: inv.name, role: inv.roleKey, roleName: inv.roleName, subject: email, detail: inv.reason });
        return new Response(JSON.stringify(invitationView(inv, ctx.now)), { status: 201, headers: { 'Content-Type': 'application/json' } });
      })
    ),
    http.post(
      `${A}/invitations/:id/resend`,
      route(async (ctx, p) => {
        const s = state(ctx);
        if (!allows(ctx.roles, 'rbac', 'EDIT')) throw forbidden('Your roles can’t change users & roles.');
        if (!ctx.permissions.includes('access:admin')) throw forbidden('This needs the access:admin permission.');
        const inv = s.invitations.find((i) => i.id === p.id);
        if (!inv) throw notFound('invitation');
        if (inv.state !== 'PENDING') throw problem(409, 'CONFLICT', 'Conflict', `This invitation is ${inv.state.toLowerCase()} and can't be sent again`);
        inv.token = `${inv.token.replace(/-again.*$/, '')}-again${inv.sendCount}`;
        inv.sendCount += 1;
        inv.lastSentAt = ctx.now.toISOString();
        inv.expiresAt = new Date(ctx.now.getTime() + INVITATION_TTL_MS).toISOString();
        note(s, ctx, { action: 'STAFF_INVITATION_RESENT', actionLabel: 'Staff invitation sent again', targetName: inv.name, role: inv.roleKey, roleName: inv.roleName, subject: inv.email, detail: 'sent again' });
        return invitationView(inv, ctx.now);
      })
    ),
    http.post(
      `${A}/invitations/:id/revoke`,
      route(async (ctx, p) => {
        const { s, reason } = await write(ctx);
        const inv = s.invitations.find((i) => i.id === p.id);
        if (!inv) throw notFound('invitation');
        if (inv.state !== 'PENDING') throw problem(409, 'CONFLICT', 'Conflict', `This invitation is ${inv.state.toLowerCase()} and can't be revoked`);
        inv.state = 'REVOKED';
        note(s, ctx, { action: 'STAFF_INVITATION_REVOKED', actionLabel: 'Staff invitation revoked', targetName: inv.name, role: inv.roleKey, roleName: inv.roleName, subject: inv.email, detail: reason });
        return new Response(null, { status: 204 });
      })
    ),
    http.post(
      `${W}/invitations/preview`,
      route(async (ctx) => {
        const s = state(ctx);
        const { token } = await json(ctx.request, TokenBody);
        const inv = s.invitations.find((i) => i.token === token);
        if (!inv) throw notFound('invitation');
        return { firstName: inv.firstName, lastName: inv.lastName, name: inv.name, email: inv.email, roleName: inv.roleName, status: statusOf(inv, ctx.now), expiresAt: inv.expiresAt };
      })
    ),
    http.post(
      `${W}/invitations/accept`,
      route(async (ctx) => {
        const s = state(ctx);
        const b = await json(ctx.request, AcceptBody);
        const inv = s.invitations.find((i) => i.token === b.token);
        if (!inv) throw notFound('invitation');
        const status = statusOf(inv, ctx.now);
        if (status !== 'PENDING') throw problem(422, 'VALIDATION_FAILED', 'Validation failed', status === 'EXPIRED' ? 'This invitation has expired. Ask for a new one' : status === 'REVOKED' ? 'This invitation was withdrawn. Ask for a new one' : 'This invitation was already used. Sign in instead');
        if (b.password.length < 12) throw problem(422, 'VALIDATION_FAILED', 'Validation failed', 'One or more fields are invalid.', { password: 'Use at least 12 characters' });
        if (b.password !== b.confirmPassword) throw problem(422, 'VALIDATION_FAILED', 'Validation failed', 'One or more fields are invalid.', { confirmPassword: 'The two passwords don’t match' });
        inv.state = 'ACCEPTED';
        s.seq += 1;
        s.people.push({ id: `stf-n${s.seq}`, name: inv.name, email: inv.email, enabled: true, principalType: 'STAFF', roles: [inv.roleKey], directGrants: [], roleMarkets: {} });
        note(s, ctx, { action: 'STAFF_INVITATION_ACCEPTED', actionLabel: 'Staff invitation accepted', targetName: inv.name, role: inv.roleKey, roleName: inv.roleName, subject: inv.email, detail: inv.reason });
        return new Response(JSON.stringify({ email: inv.email, roleName: inv.roleName }), { status: 201, headers: { 'Content-Type': 'application/json' } });
      })
    ),
    http.get(
      `${A}/modules`,
      route((ctx) => {
        const s = read(ctx);
        const portal = new URL(ctx.request.url).searchParams.get('portal') ?? 'platform';
        if (!(ACCESS_PORTALS as readonly string[]).includes(portal)) throw problem(422, 'VALIDATION_FAILED', 'Validation failed', 'One or more fields are invalid.', { portal: 'Choose platform, seller, provider or account' });
        const rolesOf = Object.values(s.roles).filter((r) => r.portal === portal && (portal !== 'platform' || s.matrix.dash?.[r.key] !== undefined));
        const modules = portal === 'platform' ? MATRIX_MODULES.map((m) => ({ key: m, label: MODULE_LABELS[m] ?? m, levels: { ...(s.matrix[m] ?? {}) } })) : [];
        const locked = portal === 'platform' ? Object.entries(LOCKED_MODULES).flatMap(([role, mods]) => mods.map((module) => ({ role, module, minimum: 'edit' as const }))) : [];
        return { portal, levels: LEVELS, roles: rolesOf.map((r) => ({ key: r.key, name: r.name })), modules, locked, canAdmin: canAdmin(ctx) };
      })
    ),
    http.put(
      `${A}/modules/:portal/:module/:role`,
      route(async (ctx, p) => {
        // The level is read from a copy: write() reads the reason from the body itself.
        const { level } = await json(ctx.request.clone(), z.object({ level: z.string().default('') }).passthrough());
        const { s, reason } = await write(ctx);
        const module = p.module ?? '';
        const role = p.role ?? '';
        if (!LEVELS.includes(level as AccessModuleLevel)) throw problem(422, 'VALIDATION_FAILED', 'Validation failed', 'One or more fields are invalid.', { level: 'Choose none, view, edit, approve or full' });
        if (p.portal !== 'platform' || !s.matrix[module] || !s.roles[role]) throw notFound(!s.roles[role] ? 'role' : 'module');
        if ((LOCKED_MODULES[role] ?? []).includes(module) && (level === 'none' || level === 'view')) throw problem(409, 'ACCESS_LOCKOUT', 'Can’t lower this', 'Super Admin keeps at least Edit on this module so the console can always be administered.');
        const before = s.matrix[module][role] ?? 'none';
        s.matrix[module][role] = level as AccessModuleLevel;
        note(s, ctx, { action: 'MODULE_ACCESS_CHANGED', actionLabel: 'Module access changed', role, roleName: s.roles[role]?.name ?? role, subject: `platform:${module}`, detail: `${before} → ${level}: ${reason}` });
        return { portal: 'platform', module, role, level };
      })
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
        return { items: rows.slice(page * size, page * size + size), page, size, total: rows.length };
      })
    )
  ];
}
