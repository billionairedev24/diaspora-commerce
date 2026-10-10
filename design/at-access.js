/* AfroTransact access control: permissions are capabilities, roles are bundles of capabilities,
   users hold roles plus optional direct grants. Shared by Backoffice (Access control) and the Driver app.
   Production: Spring Authorization Server issues scopes from this model; OpenFGA enforces object checks. */
(function () {
  if (window.ATAccess) return;
  const KEY = 'at.access.v1';
  const PERMS = [
    ['delivery:operate', 'Use the driver app', 'Delivery'], ['delivery:dispatch', 'Plan and assign routes', 'Delivery'],
    ['inventory:scan', 'Warehouse scanner (receive, pick, pack)', 'Warehouse'], ['inventory:adjust', 'Adjust stock and cycle count', 'Warehouse'],
    ['catalog:create', 'Create products and variants', 'Catalog'], ['catalog:approve', 'Approve listings', 'Catalog'], ['labels:print', 'Print barcode labels', 'Catalog'],
    ['orders:status.platform', 'Update status of AfroTransact-fulfilled shipments', 'Orders'], ['orders:status.delivered', 'Confirm delivery / failed delivery', 'Orders'], ['refunds:issue', 'Issue refunds', 'Orders'],
    ['connect:hold_payouts', 'Pause or release seller payouts', 'Payments'], ['config:edit', 'Edit platform configuration', 'Platform'], ['access:manage', 'Grant and revoke access', 'Platform']
  ];
  const ROLES = {
    superAdmin: ['Super Admin', PERMS.map((p) => p[0])], developer: ['Developer', ['access:manage', 'config:edit', 'labels:print']], productOwner: ['Product Owner', ['config:edit', 'catalog:approve']],
    ops: ['Operations Manager', ['delivery:dispatch', 'orders:status.platform', 'orders:status.delivered']], fulfil: ['Fulfilment Ops', ['inventory:scan', 'inventory:adjust', 'orders:status.platform', 'delivery:dispatch', 'labels:print']],
    retail: ['Retail Buyer (1P)', ['catalog:create', 'inventory:scan', 'labels:print']], catalog: ['Catalog Manager', ['catalog:create', 'catalog:approve', 'labels:print']], trust: ['Trust & Safety', ['catalog:approve', 'connect:hold_payouts']],
    finance: ['Finance', ['refunds:issue', 'connect:hold_payouts']], support: ['Customer Support', ['refunds:issue', 'orders:status.delivered']], driver: ['Driver', ['delivery:operate']]
  };
  const USERS = [
    ['u1', 'Tunde Adeyemi', 'tunde@afrotransact.com', 'drive2026', ['driver'], [], 'Houston FC'], ['u2', 'Chinedu Okafor', 'chinedu@afrotransact.com', 'drive2026', ['driver'], [], 'Houston FC'],
    ['u3', 'Daniel Mensah', 'daniel@afrotransact.com', 'ops2026', ['ops'], ['delivery:operate'], 'Houston FC'], ['u4', 'Kofi Boateng', 'kofi@afrotransact.com', 'fc2026', ['fulfil'], [], 'Edison FC'],
    ['u5', 'Sara Tesfaye', 'sara@afrotransact.com', 'trust2026', ['trust'], [], 'HQ'], ['u6', 'Elena Petrova', 'elena@afrotransact.com', 'dev2026', ['developer'], [], 'HQ'], ['u7', 'Ifeoma Okafor', 'ifeoma@afrotransact.com', 'admin2026', ['superAdmin'], [], 'HQ']
  ].map(([id, name, email, pw, roles, extra, site]) => ({ id, name, email, pw, roles, extra, site, active: true }));
  const fresh = () => ({ roles: Object.fromEntries(Object.entries(ROLES).map(([k, [label, perms]]) => [k, { label, perms: [...perms] }])), users: JSON.parse(JSON.stringify(USERS)), log: [] });
  const read = () => { try { const r = JSON.parse(localStorage.getItem(KEY) || 'null'); if (r && r.users) return r; } catch (e) { /* ignore */ } const f = fresh(); write(f); return f; };
  const bc = typeof BroadcastChannel !== 'undefined' ? new BroadcastChannel('at-access') : null; const ls = new Set();
  const write = (s) => { try { localStorage.setItem(KEY, JSON.stringify(s)); } catch (e) { /* ignore */ } if (bc) bc.postMessage(1); ls.forEach((f) => f(s)); };
  if (bc) bc.onmessage = () => ls.forEach((f) => f(read()));
  window.addEventListener('storage', (e) => { if (e.key === KEY) ls.forEach((f) => f(read())); });
  const effective = (s, u) => [...new Set(u.roles.flatMap((r) => (s.roles[r] ? s.roles[r].perms : [])).concat(u.extra))];
  const mutate = (actor, what, fn) => { const s = read(); fn(s); s.log.unshift({ at: new Date().toLocaleString(), actor: actor || 'system', what }); s.log = s.log.slice(0, 80); write(s); return s; };
  window.ATAccess = {
    PERMS, snapshot: read, subscribe: (f) => { ls.add(f); return () => ls.delete(f); },
    effective: (u) => effective(read(), u), can: (userId, perm) => { const s = read(); const u = s.users.find((x) => x.id === userId); return !!u && u.active && effective(s, u).includes(perm); },
    login: (email, pw) => { const s = read(); const u = s.users.find((x) => x.email.toLowerCase() === String(email).trim().toLowerCase()); if (!u || u.pw !== pw) return { ok: false, error: 'Email or password is incorrect.' }; if (!u.active) return { ok: false, error: 'This account is disabled. Contact your manager.' }; return { ok: true, user: { id: u.id, name: u.name, email: u.email, site: u.site }, perms: effective(s, u) }; },
    setRolePerm: (actor, role, perm, on) => mutate(actor, (on ? 'Granted ' : 'Revoked ') + perm + ' on role ' + role, (s) => { const r = s.roles[role]; r.perms = on ? [...new Set([...r.perms, perm])] : r.perms.filter((p) => p !== perm); }),
    setUserRole: (actor, uid, role, on) => mutate(actor, (on ? 'Assigned ' : 'Removed ') + 'role ' + role + ' for ' + uid, (s) => { const u = s.users.find((x) => x.id === uid); u.roles = on ? [...new Set([...u.roles, role])] : u.roles.filter((r) => r !== role); }),
    setUserPerm: (actor, uid, perm, on) => mutate(actor, (on ? 'Granted ' : 'Revoked ') + perm + ' directly to ' + uid, (s) => { const u = s.users.find((x) => x.id === uid); u.extra = on ? [...new Set([...u.extra, perm])] : u.extra.filter((p) => p !== perm); }),
    setActive: (actor, uid, on) => mutate(actor, (on ? 'Enabled ' : 'Disabled ') + uid, (s) => { s.users.find((x) => x.id === uid).active = on; }),
    addUser: (actor, u) => mutate(actor, 'Invited ' + u.email, (s) => { s.users.push({ id: 'u' + Date.now(), pw: 'welcome2026', extra: [], active: true, ...u }); }),
    reset: () => write(fresh())
  };
})();
