/* AfroTransact inventory + order-flow core (shared by Shop, Backoffice and Driver app).
   Inventory: versioned rows + append-only ledger, mutated only inside an exclusive Web Lock
   (cross-tab mutex) with optimistic version checks and all-or-nothing multi-line reservations.
   Production equivalent: inventory-service on Postgres — SELECT … FOR UPDATE on (sku, node) rows
   ordered by key to avoid deadlocks, @Version optimistic locking, reservation rows with TTL,
   idempotency keys, and an outbox event per ledger entry. */
(function () {
  if (window.ATInv) return;
  const KEY = 'at.inventory.v2';
  const FCS = ['Houston FC', 'Edison FC', 'Atlanta FC', 'Los Angeles FC'];
  const SKUS = [
    ['AT-JOL-6', 'p2', 'AfroTransact Brand Jollof Seasoning, 6 oz', [420, 260, 180, 40]],
    ['AT-BAS-10', 'p11', 'AfroTransact Brand Aged Basmati Rice, 10 lb', [120, 300, 90, 60]],
    ['AT-BSP-8', 'p16', 'AfroTransact Brand African Black Soap, 8 oz', [200, 150, 320, 130]],
    ['AT-SHI-12', 'p3', 'Shito Black Pepper Sauce, Hot, 12 oz (resale)', [30, 80, 18, 12]]
  ];
  const now = () => new Date().toISOString().replace('T', ' ').slice(0, 19);
  const seed = () => {
    const rows = []; const ledger = []; let n = 1;
    SKUS.forEach(([sku, pid, name, qty]) => FCS.forEach((fc, i) => {
      rows.push({ sku, pid, name, fc, onHand: qty[i], reserved: 0, inbound: i === 1 ? 120 : 0, damaged: 0, version: 1 });
      ledger.push({ id: 'L' + n++, at: '2026-10-01 06:00:00', sku, fc, type: 'RECEIVE', dOnHand: qty[i], dReserved: 0, ref: 'PO-1P-30' + i, actor: 'system', version: 1 });
    }));
    return { rows, ledger, reservations: {}, seq: n, rev: 1 };
  };
  const read = () => { try { const r = localStorage.getItem(KEY); if (r) return JSON.parse(r); } catch (e) { /* storage blocked */ } const s = seed(); write(s); return s; };
  let memo = null;
  const write = (s) => { memo = s; try { localStorage.setItem(KEY, JSON.stringify(s)); } catch (e) { /* storage blocked */ } };
  const bc = typeof BroadcastChannel !== 'undefined' ? new BroadcastChannel('at-inventory') : null;
  const listeners = new Set();
  const notify = () => { listeners.forEach((fn) => fn(read())); };
  if (bc) bc.onmessage = () => notify();
  window.addEventListener('storage', (e) => { if (e.key === KEY) notify(); });

  // Exclusive critical section: Web Locks across tabs, promise-chain fallback within a tab.
  let chain = Promise.resolve();
  const withLock = (fn) => {
    if (navigator.locks && navigator.locks.request) return navigator.locks.request('at-inventory', { mode: 'exclusive' }, async () => fn());
    const p = chain.then(() => fn()); chain = p.catch(() => {}); return p;
  };
  const tx = async (actor, mutate) => withLock(async () => {
    const s = read(); const out = mutate(s); s.rev += 1; write(s);
    if (bc) bc.postMessage({ rev: s.rev }); notify(); return out;
  });
  const row = (s, sku, fc) => s.rows.find((r) => r.sku === sku && r.fc === fc);
  const entry = (s, r, type, dOnHand, dReserved, ref, actor) => { r.onHand += dOnHand; r.reserved += dReserved; r.version += 1; s.ledger.unshift({ id: 'L' + s.seq++, at: now(), sku: r.sku, fc: r.fc, type, dOnHand, dReserved, ref, actor, version: r.version }); if (s.ledger.length > 400) s.ledger.length = 400; };
  class InventoryError extends Error { constructor(code, msg) { super(msg); this.code = code; } }

  const api = {
    FCS, snapshot: () => read(), subscribe: (fn) => { listeners.add(fn); return () => listeners.delete(fn); },
    available: (s, sku, fc) => { const r = row(s, sku, fc); return r ? r.onHand - r.reserved : 0; },
    availableByPid: (pid) => { const s = read(); return s.rows.filter((r) => r.pid === pid).reduce((a, r) => a + Math.max(0, r.onHand - r.reserved), 0); },
    /* All-or-nothing reservation. lines: [{ pid|sku, qty, fc? }]. Idempotent per orderId. */
    reserve: (orderId, lines, actor) => tx(actor, (s) => {
      if (s.reservations[orderId]) return s.reservations[orderId];
      const plan = [];
      for (const l of lines) {
        const cands = s.rows.filter((r) => (l.sku ? r.sku === l.sku : r.pid === l.pid) && (!l.fc || r.fc === l.fc)).sort((a, b) => (b.onHand - b.reserved) - (a.onHand - a.reserved));
        let need = l.qty;
        for (const r of cands) { const take = Math.min(need, r.onHand - r.reserved); if (take > 0) { plan.push({ r, qty: take, ver: r.version }); need -= take; } if (!need) break; }
        if (need > 0) throw new InventoryError('INSUFFICIENT_STOCK', 'Only ' + (l.qty - need) + ' available for ' + (cands[0] ? cands[0].name : l.pid));
      }
      plan.forEach((p) => { if (p.r.version !== p.ver) throw new InventoryError('VERSION_CONFLICT', 'Stock changed, retry'); });
      plan.forEach((p) => entry(s, p.r, 'RESERVE', 0, p.qty, orderId, actor || 'checkout'));
      s.reservations[orderId] = { orderId, lines: plan.map((p) => ({ sku: p.r.sku, fc: p.r.fc, qty: p.qty })), status: 'HELD', at: now() };
      return s.reservations[orderId];
    }),
    commit: (orderId, actor) => tx(actor, (s) => { const res = s.reservations[orderId]; if (!res || res.status !== 'HELD') return res; res.lines.forEach((l) => entry(s, row(s, l.sku, l.fc), 'SHIP', -l.qty, -l.qty, orderId, actor || 'fulfilment')); res.status = 'SHIPPED'; return res; }),
    release: (orderId, actor) => tx(actor, (s) => { const res = s.reservations[orderId]; if (!res || res.status !== 'HELD') return res; res.lines.forEach((l) => entry(s, row(s, l.sku, l.fc), 'RELEASE', 0, -l.qty, orderId, actor || 'checkout')); res.status = 'RELEASED'; return res; }),
    receive: (sku, fc, qty, ref, actor) => tx(actor, (s) => { const r = row(s, sku, fc); entry(s, r, 'RECEIVE', qty, 0, ref, actor); r.inbound = Math.max(0, r.inbound - qty); return r; }),
    adjust: (sku, fc, delta, reason, actor) => tx(actor, (s) => { const r = row(s, sku, fc); if (r.onHand + delta < r.reserved) throw new InventoryError('BELOW_RESERVED', 'Adjustment would leave less than the ' + r.reserved + ' units already reserved'); entry(s, r, 'ADJUST', delta, 0, reason, actor); return r; }),
    returnItem: (sku, fc, qty, sellable, ref, actor) => tx(actor, (s) => { const r = row(s, sku, fc); if (sellable) entry(s, r, 'RETURN_RESTOCK', qty, 0, ref, actor); else { r.damaged += qty; r.version += 1; s.ledger.unshift({ id: 'L' + s.seq++, at: now(), sku, fc, type: 'RETURN_DAMAGED', dOnHand: 0, dReserved: 0, ref, actor, version: r.version }); } return r; }),
    reconcile: (s) => s.rows.map((r) => { const lg = s.ledger.filter((l) => l.sku === r.sku && l.fc === r.fc); const truncated = s.ledger.length >= 400; const oh = lg.reduce((a, l) => a + l.dOnHand, 0); const rs = lg.reduce((a, l) => a + l.dReserved, 0); return { sku: r.sku, fc: r.fc, ok: truncated || (oh === r.onHand && rs === r.reserved && r.reserved >= 0 && r.onHand >= r.reserved) }; }),
    reset: () => withLock(async () => { const s = seed(); write(s); if (bc) bc.postMessage({ rev: s.rev }); notify(); }),
    InventoryError
  };
  window.ATInv = api;

  /* ---------------- Order / shipment state machine ---------------- */
  const S = ['placed', 'confirmed', 'packed', 'ready', 'picked_up', 'in_transit', 'out_for_delivery', 'delivered', 'delivery_failed', 'returned', 'cancelled'];
  const LABEL = { placed: 'Placed', confirmed: 'Confirmed', packed: 'Packed', ready: 'Ready for pickup', picked_up: 'Picked up', in_transit: 'In transit', out_for_delivery: 'Out for delivery', delivered: 'Delivered', delivery_failed: 'Delivery failed', returned: 'Returned', cancelled: 'Cancelled' };
  // [from, to, actors for SELLER-fulfilled, actors for PLATFORM-fulfilled (1P or FBA)]
  const T = [
    ['placed', 'confirmed', ['seller'], ['admin', 'system']],
    ['confirmed', 'packed', ['seller'], ['admin']],
    ['packed', 'ready', ['seller'], ['admin']],
    ['ready', 'picked_up', ['courier', 'carrier'], ['courier', 'carrier', 'admin']],
    ['picked_up', 'in_transit', ['courier', 'carrier'], ['courier', 'carrier', 'admin']],
    ['in_transit', 'out_for_delivery', ['courier', 'carrier'], ['courier', 'carrier', 'admin']],
    ['out_for_delivery', 'delivered', ['courier', 'carrier', 'admin'], ['courier', 'carrier', 'admin']],
    ['out_for_delivery', 'delivery_failed', ['courier', 'carrier', 'admin'], ['courier', 'carrier', 'admin']],
    ['in_transit', 'delivered', ['carrier', 'admin'], ['carrier', 'admin']],
    ['delivery_failed', 'out_for_delivery', ['courier', 'admin'], ['courier', 'admin']],
    ['delivery_failed', 'returned', ['admin', 'carrier'], ['admin', 'carrier']],
    ['delivered', 'returned', ['admin'], ['admin']],
    ['placed', 'cancelled', ['seller', 'admin'], ['admin']],
    ['confirmed', 'cancelled', ['seller', 'admin'], ['admin']],
    ['packed', 'cancelled', ['seller', 'admin'], ['admin']]
  ];
  const ACTOR = { seller: 'Seller', admin: 'Platform admin', courier: 'AfroTransact Delivery', carrier: 'Carrier webhook', system: 'System' };
  window.ATFlow = {
    S, LABEL, T, ACTOR, MAIN: ['placed', 'confirmed', 'packed', 'ready', 'picked_up', 'in_transit', 'out_for_delivery', 'delivered'],
    allowed: (ff, from, to, actor) => { const t = T.find((x) => x[0] === from && x[1] === to); return !!t && (ff === 'seller' ? t[2] : t[3]).includes(actor); },
    nextFrom: (from) => T.filter((x) => x[0] === from).map((x) => x[1]),
    whoCan: (ff, from, to) => { const t = T.find((x) => x[0] === from && x[1] === to); return t ? (ff === 'seller' ? t[2] : t[3]).map((a) => ACTOR[a]).join(', ') : 'nobody'; }
  };
})();
