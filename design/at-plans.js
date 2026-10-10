/* AfroTransact subscription plans (Stripe Billing) + client billing profile.
   Production: GET /v1/billing/catalog (Stripe Products/Prices, one Price per interval) and
   GET /v1/orgs/:id/billing (Subscription, PaymentMethods, Invoices). Prototype: localStorage. */
(function () {
  if (window.ATPlans) return;
  const KEY = 'at.billing.v1';
  const CATALOG = {
    seller: [
      { id: 'seller_essentials', name: 'Seller Essentials', month: 0, tag: 'Start selling', trial: 0, features: ['Up to 50 listings', '15% referral fee', 'Basic storefront', '1 user'] },
      { id: 'seller_growth', name: 'Seller Growth', month: 39, tag: 'Most popular', trial: 30, features: ['Up to 1,000 listings', '8–12% referral fee by category', 'Storefront themes and AI listing writer', '3 users'] },
      { id: 'seller_pro', name: 'Seller Pro', month: 129, tag: 'For established brands', trial: 14, features: ['Unlimited listings', '6–10% referral fee', 'Fulfilled by AfroTransact priority, ad credits, API', '10 users'] }
    ],
    provider: [
      { id: 'provider_solo', name: 'Provider Solo', month: 19, tag: 'Independent pros', trial: 30, features: ['1 practitioner', '10% booking fee', 'Online booking and deposits', 'Public page with 6 photos'] },
      { id: 'provider_studio', name: 'Provider Studio', month: 49, tag: 'Most popular', trial: 30, features: ['Up to 5 practitioners', '8% booking fee', 'Waitlist and reminders by SMS', 'Public page with 12 photos'] },
      { id: 'provider_business', name: 'Provider Business', month: 99, tag: 'Multi-location', trial: 14, features: ['Unlimited staff', '6% booking fee', 'Multiple locations and API', 'Public page with 24 photos'] }
    ]
  };
  const INTERVALS = [
    { id: 'month', label: 'Monthly', months: 1, every: 'every month', adverb: 'monthly', save: '' },
    { id: 'quarter', label: 'Quarterly', months: 3, every: 'every 3 months', adverb: 'quarterly', save: 'Save 5%' },
    { id: 'year', label: 'Annually', months: 12, every: 'every year', adverb: 'annually', save: '2 months free' }
  ];
  const usd = (n) => '$' + (Math.round(n * 100) / 100).toLocaleString('en-US', { minimumFractionDigits: n % 1 ? 2 : 0, maximumFractionDigits: 2 });
  const quote = (plan, iv) => {
    const I = INTERVALS.find((x) => x.id === iv) || INTERVALS[0]; const m = plan.month;
    const total = I.id === 'year' ? m * 10 : I.id === 'quarter' ? Math.round(m * 3 * 0.95 * 100) / 100 : m;
    const full = m * I.months; const save = Math.round((full - total) * 100) / 100;
    return { total, perMonth: Math.round((total / I.months) * 100) / 100, save, I,
      priceLabel: m === 0 ? 'Free' : usd(total / I.months) + '/mo', billedLabel: m === 0 ? 'No subscription fee' : 'Billed ' + usd(total) + ' ' + I.every, saveLabel: save > 0 ? 'Save ' + usd(save) + ' vs monthly' : '' };
  };
  const BRANDS = { visa: ['Visa', '#1A1F71', 'visa'], mastercard: ['Mastercard', '#111111', 'mastercard'], amex: ['American Express', '#1F72CD', 'americanexpress'], discover: ['Discover', '#231F20', 'discover'] };
  const brandOf = (num) => { const n = String(num || '').replace(/\D/g, ''); if (/^4/.test(n)) return 'visa'; if (/^(5[1-5]|2[2-7])/.test(n)) return 'mastercard'; if (/^3[47]/.test(n)) return 'amex'; if (/^6(011|5)/.test(n)) return 'discover'; return ''; };
  const luhn = (num) => { const n = String(num || '').replace(/\D/g, ''); if (n.length < 13) return false; let s = 0; n.split('').reverse().forEach((d, i) => { let x = +d; if (i % 2) { x *= 2; if (x > 9) x -= 9; } s += x; }); return s % 10 === 0; };
  const addDays = (iso, n) => { const d = new Date(iso + 'T12:00:00'); d.setDate(d.getDate() + n); return d.toISOString().slice(0, 10); };
  const addMonths = (iso, n) => { const d = new Date(iso + 'T12:00:00'); d.setMonth(d.getMonth() + n); return d.toISOString().slice(0, 10); };
  const fmt = (iso) => new Date(iso + 'T12:00:00').toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric' });
  const TODAY = '2026-10-10';
  const DEF = {
    seller: { plan: 'seller_growth', interval: 'quarter', started: '2026-04-10', trialEnds: null, periodEnd: '2027-01-10', cancelAt: null,
      pms: [{ id: 'pm_s1', type: 'card', brand: 'visa', last4: '4242', exp: '08/28', name: 'Titilayo Adebayo', def: true }, { id: 'pm_s2', type: 'bank', bank: 'Chase', kind: 'Business checking', last4: '4417', verified: true, def: false, payout: true }],
      invoices: [['in_s3', '2026-10-10', 'Jul 10 – Oct 10, 2026', 111.15, 'Paid', 'Visa ••4242'], ['in_s2', '2026-07-10', 'Apr 10 – Jul 10, 2026', 111.15, 'Paid', 'Visa ••4242'], ['in_s1', '2026-04-10', 'Apr 10, 2026 trial end', 0, 'Paid', '—']] },
    provider: { plan: 'provider_studio', interval: 'month', started: '2026-09-20', trialEnds: '2026-10-20', periodEnd: '2026-10-20', cancelAt: null,
      pms: [{ id: 'pm_p1', type: 'bank', bank: 'Navy Federal Credit Union', kind: 'Checking', last4: '0931', verified: true, def: true, payout: true }, { id: 'pm_p2', type: 'card', brand: 'mastercard', last4: '5100', exp: '11/27', name: 'Ama Owusu', def: false }],
      invoices: [] }
  };
  const subs = new Set(); const emit = () => subs.forEach((f) => { try { f(); } catch (e) { /* ignore */ } });
  window.addEventListener('storage', (e) => { if (e.key === KEY) emit(); });
  const all = () => { try { return { ...DEF, ...(JSON.parse(localStorage.getItem(KEY) || '{}')) }; } catch (e) { return DEF; } };
  const put = (portal, next) => { const a = all(); a[portal] = next; try { localStorage.setItem(KEY, JSON.stringify(a)); } catch (e) { /* ignore */ } emit(); };
  const PAYOUT_RULES = { holdDays: 7, minRequest: 25, perDay: 1, eta: '1–2 business days after approval' };
  const WKEY = 'at.wallet.v1';
  const WDEF = {
    seller: { pending: [['AT-1052-7741', 186.40, '2026-10-14', 'Delivered Oct 7'], ['AT-1053-1180', 92.10, '2026-10-16', 'Delivered Oct 9'], ['AT-1054-3302', 248.75, null, 'Out for delivery']], available: 1284.55, requests: [['po_s3', '2026-10-03', 940.20, 'Paid', 'Chase ••4417'], ['po_s2', '2026-09-26', 1122.00, 'Paid', 'Chase ••4417']] },
    provider: { pending: [['BK-5512', 228.00, '2026-10-15', 'Completed Oct 8'], ['BK-5516', 152.00, '2026-10-17', 'Completed Oct 10'], ['BK-5519', 40.00, null, 'Deposit · appointment Oct 14']], available: 612.80, requests: [['po_p2', '2026-10-02', 480.00, 'Paid', 'Navy Federal ••0931']] }
  };
  const wall = () => { try { return { ...WDEF, ...(JSON.parse(localStorage.getItem(WKEY) || '{}')) }; } catch (e) { return WDEF; } };
  const wput = (portal, v) => { const a = wall(); a[portal] = v; try { localStorage.setItem(WKEY, JSON.stringify(a)); } catch (e) { /* ignore */ } emit(); };
  const API = {
    PAYOUT_RULES,
    wallet(portal) { return wall()[portal] || WDEF[portal]; },
    payoutAccount(portal) { const b = API.get(portal); return b.pms.find((x) => x.type === 'bank' && x.payout) || b.pms.find((x) => x.type === 'bank') || null; },
    requestPayout(portal, amt) {
      const w = API.wallet(portal); const acct = API.payoutAccount(portal); const a = Math.round(amt * 100) / 100;
      if (!acct) return { ok: false, error: 'Add a bank account to receive payouts.' };
      if (!(a >= PAYOUT_RULES.minRequest)) return { ok: false, error: 'The minimum payout is ' + usd(PAYOUT_RULES.minRequest) + '.' };
      if (a > w.available) return { ok: false, error: 'You can request up to ' + usd(w.available) + '.' };
      if (w.requests.some((r) => r[1] === TODAY && r[3] !== 'Paid')) return { ok: false, error: 'You already have a payout request today. You can request again tomorrow.' };
      const id = 'po_' + Date.now().toString(36);
      wput(portal, { ...w, available: Math.round((w.available - a) * 100) / 100, requests: [[id, TODAY, a, 'Requested', acct.bank + ' ••' + acct.last4], ...w.requests] });
      return { ok: true, id, acct };
    },
    CATALOG, INTERVALS, BRANDS, usd, quote, brandOf, luhn, fmt, TODAY,
    subscribe(f) { subs.add(f); return () => subs.delete(f); },
    plans(portal) { return CATALOG[portal] || CATALOG.seller; },
    plan(id) { return [...CATALOG.seller, ...CATALOG.provider].find((p) => p.id === id || p.name === id); },
    get(portal) { return all()[portal] || DEF[portal]; },
    pmLabel(pm) { return pm.type === 'card' ? BRANDS[pm.brand][0] + ' ••' + pm.last4 : pm.bank + ' ••' + pm.last4; },
    glyph(brand) { return 'url("https://cdn.jsdelivr.net/npm/simple-icons@9.21.0/icons/' + BRANDS[brand][2] + '.svg")'; },
    changePlan(portal, planId, interval) { const b = API.get(portal); const p = API.plan(planId); put(portal, { ...b, plan: p.id, interval: interval || b.interval, cancelAt: null }); },
    setInterval(portal, iv) { const b = API.get(portal); put(portal, { ...b, nextInterval: iv === b.interval ? null : iv }); },
    cancel(portal, undo) { const b = API.get(portal); put(portal, { ...b, cancelAt: undo ? null : (b.trialEnds || b.periodEnd) }); },
    addPm(portal, pm, makeDefault) { const b = API.get(portal); const id = 'pm_' + Date.now().toString(36); const pms = b.pms.map((x) => (makeDefault ? { ...x, def: false } : x)).concat([{ ...pm, id, def: makeDefault || !b.pms.length }]); put(portal, { ...b, pms }); return id; },
    setDefault(portal, id) { const b = API.get(portal); put(portal, { ...b, pms: b.pms.map((x) => ({ ...x, def: x.id === id })) }); },
    removePm(portal, id) { const b = API.get(portal); const x = b.pms.find((p) => p.id === id); if (!x || x.def) return false; put(portal, { ...b, pms: b.pms.filter((p) => p.id !== id) }); return true; },
    startFromOnboarding(portal, planId, interval, pm) {
      const p = API.plan(planId); const trialEnds = p.trial ? addDays(TODAY, p.trial) : null; const start = trialEnds || TODAY;
      const pms = pm ? [{ ...pm, id: 'pm_ob', def: true }] : [];
      put(portal, { plan: p.id, interval, started: TODAY, trialEnds, periodEnd: p.month ? addMonths(start, 0) : null, cancelAt: null, pms, invoices: [] });
    },
    summary(portal) {
      const b = API.get(portal); const p = API.plan(b.plan); const q = quote(p, b.interval); const def = b.pms.find((x) => x.def);
      const nextDate = b.trialEnds && b.trialEnds > TODAY ? b.trialEnds : b.periodEnd;
      const nq = b.nextInterval ? quote(p, b.nextInterval) : null;
      return { b, p, q, def, nextDate, switchLabel: nq ? 'Switches to ' + nq.I.adverb + ' billing (' + nq.billedLabel.replace('Billed ', '') + ') on ' + fmt(nextDate) : '', nextLabel: p.month === 0 ? 'No charges' : b.cancelAt ? 'Ends ' + fmt(b.cancelAt) + ' — no further charges' : (b.trialEnds && b.trialEnds > TODAY ? 'Free trial ends ' + fmt(b.trialEnds) + ' · first charge ' + usd(q.total) : 'Next charge ' + usd(q.total) + ' on ' + fmt(nextDate)) + (def ? ' to ' + API.pmLabel(def) : '') };
    }
  };
  window.ATPlans = API;
})();
