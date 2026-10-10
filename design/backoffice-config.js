/* Platform configuration, extended RBAC, sheet actions, onboarding schema and application dossiers.
   Loaded after backoffice-data.js; extends window.ATBO. Production: config-service (versioned, audited),
   OpenFGA for RBAC, seller-onboarding-service for dossiers. */
window.__atCfgBoot = function boot() {
  const BO = window.ATBO; if (!BO) { setTimeout(boot, 25); return; } if (BO.__configReady) return;
  let SEED = 97; const rnd = () => (SEED = (SEED * 16807) % 2147483647) / 2147483647; const pick = (a) => a[Math.floor(rnd() * a.length)];
  const day = (n) => { const d = new Date(2026, 9, 9); d.setDate(d.getDate() - n); return d.toISOString().slice(0, 10); };
  const gen = (n, f) => Array.from({ length: n }, (_, i) => f(i));
  const ST_ = { Compliant: 'good', 'Action needed': 'warn', 'Restricted soon': 'bad', Restricted: 'bad', Verifying: 'brand', Enabled: 'good', Disabled: 'bad', 'On route': 'brand', Unassigned: 'warn', Planned: 'info', Degraded: 'warn', Expired: 'info', Retired: 'info', Failing: 'bad', On: 'good', Off: 'info', Rollout: 'brand', 'Staff only': 'warn', Revoked: 'bad', 'In review': 'brand', Verified: 'good', 'Needs attention': 'warn', Received: 'info' };
  const baseTone = BO.tone; const tone = (v) => (ST_[v] ? BO.TONE[ST_[v]] : baseTone(v)); BO.tone = tone;
  const tag = (v) => { const t = tone(v); return { text: String(v), bg: t[0], fg: t[1], pad: '2px 8px', weight: 'var(--at-weight-bold)', size: 'var(--at-text-xs)' }; };
  const strong = (v) => ({ text: String(v), weight: 'var(--at-weight-semibold)' });
  const mono = (v) => ({ text: String(v), size: 'var(--at-text-xs)' });
  const C = (id, header, fmt, extra) => ({ id, header, accessor: (r) => r[id], format: fmt ? (v) => fmt(v) : undefined, ...(extra || {}) });
  const EQ = { filterFn: 'equalsString' };
  Object.assign(BO.STATUS_TONE || {}, {});

  // ---------- platform roles & modules (21 modules) ----------
  const P = BO.PORTALS.platform;
  P.roles = [['superAdmin', 'Super Admin'], ['ops', 'Operations Manager'], ['trust', 'Trust & Safety'], ['finance', 'Finance'], ['support', 'Customer Support'], ['catalog', 'Catalog Manager'], ['marketing', 'Marketing'], ['retail', 'Retail Buyer (1P)'], ['fulfil', 'Fulfilment Ops'], ['productOwner', 'Product Owner'], ['developer', 'Developer'], ['driver', 'Driver'], ['auditor', 'Auditor (read-only)']];
  P.modules = ['dash', 'apps', 'orgs', 'catalog', 'orders', 'disputes', 'payouts', 'retail', 'fulfil', 'marketing', 'notif', 'rbac', 'audit', 'fees', 'plansCfg', 'coupons', 'pickup', 'markets', 'storeCfg', 'flags', 'dev', 'inventory', 'shipping', 'dispatch', 'compliance', 'access'];
  P.matrix = {
    superAdmin: 'FFFFFFFFFFFFFFFFFFFFFFFFFF', ops: 'VAAEVAVVEVV-VVVVEVV--VEEE-', trust: 'VAAAVA------V-----------A-', finance: 'VVV-VEA-----VFAV-----V--E-',
    support: 'VVVVEEV-V------VV-----VVV-', catalog: 'V-VFV--V----------V-------', marketing: 'V-VV-----FE----F--V-------', retail: 'V--EV--FE-------V----E----',
    fulfil: 'V---VV-VF-------FV---FEF--', productOwner: 'VVVVVVVVVVVVVEFFFFFEVVFVVV', developer: 'V---V---V-E-VVVVVVVFF-F-VF', auditor: 'VVVVVVVVVVVVVVVVVVVVVVVVVV'
  };
  P.matrix.driver = '-'.repeat(23) + 'V' + '--';
  P.modules.push('siteCfg'); Object.keys(P.matrix).forEach((r) => { P.matrix[r] += ({ superAdmin: 'F', marketing: 'E', ops: 'V', support: 'V', trust: 'V' })[r] || '-'; });
  try { const ov = JSON.parse(localStorage.getItem('at.rbac.v1') || 'null'); if (ov) Object.entries(ov).forEach(([r, m]) => { if (P.matrix[r] && m.length === P.modules.length) P.matrix[r] = m; }); } catch (e) { /* ignore */ }
  Object.values(P.matrix).forEach((m) => { if (m.length !== P.modules.length) console.warn('RBAC matrix length mismatch', m); });
  Object.assign(BO.CAN, { access: 'Edit module access, capabilities and user grants', compliance: 'Stripe requirements, reminders, payout holds', sCompliance: 'Answer Stripe requirements', pCompliance: 'Answer Stripe requirements', inventory: 'Receive, adjust, restock; view ledger & reservations', shipping: 'Carriers, webhooks, status mapping', dispatch: 'Routes, drivers, internal delivery', plansCfg: 'Plan prices, trials, limits, commission overrides', coupons: 'Create, pause, expire coupons', pickup: 'Pickup points, delivery zones, cut-offs', markets: 'Activate countries and states', storeCfg: 'Storefront builder limits and theme rules', flags: 'Feature flags and rollouts', dev: 'API keys, webhooks, AI model routing' });

  // ---------- seed data for config tables ----------
  const D = BO.DATA;
  D.coupons = [['WELCOME10', '%', 10, 'First order', 25, 5000, 1834], ['DETTY15', '%', 15, 'Category: Party', 50, 2000, 412], ['FREESHIP', 'Free shipping', 0, 'Fulfilled by AfroTransact', 35, 0, 2210], ['DIWALI5', '$', 5, 'Community: Indian', 30, 3000, 977], ['MAMATEE20', '%', 20, "Seller: Mama Tee's Pantry", 40, 500, 63], ['BRAIDS25', '$', 25, 'Services: Hair & beauty', 150, 300, 41], ['SORRY10', '$', 10, 'Support goodwill (single use)', 0, 1, 1]]
    .map(([code, type, value, scope, min, limit, used], i) => ({ id: 'CPN-' + (100 + i), code, type, value, scope, min, limit, used, starts: day(30 - i * 4), ends: day(-30 + i * 3), funding: i === 4 ? 'Seller-funded' : 'AfroTransact-funded', status: i === 6 ? 'Expired' : i === 3 ? 'Paused' : 'Active' }));
  D.pickups = [['Houston FC — Will Call', 'Houston, TX', 'FC counter', 'Mon–Sat 8–8'], ['Bellaire Blvd Locker Bank', 'Houston, TX', 'Locker (24 bays)', '24/7'], ['Edison FC — Will Call', 'Edison, NJ', 'FC counter', 'Mon–Sat 8–8'], ['Oak Tree Rd Partner Store', 'Iselin, NJ', 'Partner store', 'Daily 10–9'], ['Atlanta FC — Will Call', 'Atlanta, GA', 'FC counter', 'Mon–Sat 8–6'], ['Little Haiti Partner Store', 'Miami, FL', 'Partner store', 'Daily 9–8'], ['Cedar-Riverside Locker', 'Minneapolis, MN', 'Locker (16 bays)', '24/7']]
    .map(([name, city, type, hours], i) => ({ id: 'PU-' + (10 + i), name, city, type, hours, sameDay: i % 2 === 0 ? 'Yes' : 'No', cutoff: '2:00 pm', status: i === 6 ? 'Paused' : 'Active' }));
  D.flags = [['checkout.express_wallets', 'Apple Pay / Google Pay / Link in checkout', 100, 'On'], ['lola.agent_actions', 'Lola can add to cart and book', 100, 'On'], ['lola.voice', 'Voice input for Lola', 25, 'Rollout'], ['search.semantic_hybrid', 'Vector + BM25 hybrid search', 50, 'Rollout'], ['storefront.custom_domains', 'Sellers can map their own domain', 0, 'Off'], ['bookings.waitlist', 'Waitlist when providers are full', 10, 'Rollout'], ['ads.display_banners', 'Third-party display banners', 100, 'On'], ['market.uk_preview', 'UK storefront preview for staff', 0, 'Staff only']]
    .map(([key, desc, pct, status], i) => ({ id: 'FF-' + i, key, desc, pct, env: 'prod', owner: pick(['Growth', 'Checkout', 'Search', 'Lola', 'Seller']), status }));
  D.webhooks = [['https://hooks.mamateespantry.com/at', 'order.placed, shipment.shipped', 'Seller'], ['https://erp.afrotransact.internal/retail', 'inventory.*, po.*', 'Internal'], ['https://api.partner-3pl.com/afro', 'fba.inbound.*', 'Partner'], ['https://bi.afrotransact.internal/events', '*', 'Internal']]
    .map(([url, events, owner], i) => ({ id: 'WH-' + (40 + i), url, events, owner, success: (97 + rnd() * 3).toFixed(1) + '%', last: pick(['2 s ago', '14 s ago', '1 min ago']), status: i === 2 ? 'Failing' : 'Active' }));
  D.apiKeys = [['Mama Tee’s inventory sync', 'Seller', 'listings:write inventory:write'], ['3PL partner', 'Partner', 'fba:read fba:write'], ['Lola MCP server', 'Internal', 'catalog:read cart:write orders:read'], ['BI warehouse export', 'Internal', 'orders:read payouts:read']]
    .map(([name, owner, scopes], i) => ({ id: 'KEY-' + (700 + i), name, owner, scopes, prefix: 'at_live_' + Math.floor(rnd() * 1e6).toString(36) + '…', created: day(40 + i * 10), lastUsed: pick(['just now', '3 min ago', '1 h ago']), status: 'Active' }));
  D.aiRoutes = [['lola.intent', 'Classify shopper intent', 'openai/gpt-4.1-mini', 'google/gemini-2.0-flash', 400, 0.2], ['lola.plan', 'Plan carts and multi-step actions', 'anthropic/claude-sonnet-4', 'openai/gpt-4.1', 2000, 0.4], ['listing.writer', 'Draft seller listings', 'anthropic/claude-haiku-4', 'meta-llama/llama-3.3-70b', 900, 0.6], ['trust.triage', 'Application and listing risk summaries', 'anthropic/claude-sonnet-4', 'openai/gpt-4.1', 1500, 0.1], ['translate.listing', 'Listing translation (fr, pt, es, hi, am, sw)', 'google/gemini-2.0-flash', 'openai/gpt-4.1-mini', 1200, 0.2]]
    .map(([route, purpose, primary, fallback, maxTokens, temp], i) => ({ id: 'AI-' + i, route, purpose, primary, fallback, maxTokens, temp, budget: pick(['$300/mo', '$800/mo', '$150/mo']), status: 'Active' }));
  D.plansCfg = (window.ATPlans ? [...window.ATPlans.CATALOG.seller.map((p) => [p, 'Seller']), ...window.ATPlans.CATALOG.provider.map((p) => [p, 'Provider'])] : []).map(([p, audience], i) => { const Q = (iv) => window.ATPlans.quote(p, iv).total; return { id: 'PLN-' + i, name: p.name, audience, price: p.month, quarterly: Q('quarter'), annual: Q('year'), trial: p.trial, commAdj: 0, users: p.features.find((f) => /user|practitioner|staff/i.test(f)) || '', features: p.features.join(' · '), stripePrice: 'price_' + p.id + '_{month,quarter,year}', status: 'Active' }; });

  D.carriers = [['EasyPost', 'Aggregator', 'USPS, UPS, FedEx, DHL eCommerce', 'tracker.updated', 'pre_transit→ready · in_transit→in_transit · out_for_delivery→out_for_delivery · delivered→delivered · failure→delivery_failed · return_to_sender→returned'], ['Shippo', 'Aggregator', 'USPS, UPS, FedEx', 'track_updated', 'PRE_TRANSIT→ready · TRANSIT→in_transit · DELIVERED→delivered · FAILURE→delivery_failed · RETURNED→returned'], ['UPS', 'Carrier', 'Ground, 2nd Day Air, Next Day Air', 'Track Alert', 'M→ready · I→in_transit · O→out_for_delivery · D→delivered · X→delivery_failed'], ['FedEx', 'Carrier', 'Ground, Home Delivery, Express', 'Advanced Integrated Visibility', 'PU→picked_up · IT→in_transit · OD→out_for_delivery · DL→delivered · DE→delivery_failed'], ['USPS', 'Carrier', 'Ground Advantage, Priority Mail', 'IV-MTR feed', 'Accepted→picked_up · In Transit→in_transit · Out for Delivery→out_for_delivery · Delivered→delivered · Notice Left→delivery_failed'], ['DHL Express', 'Carrier', 'International (Lagos, Accra, Nairobi)', 'Push API', 'PU→picked_up · PL→in_transit · WC→out_for_delivery · OK→delivered · NH→delivery_failed'], ['AfroTransact Delivery', 'Internal', 'Same-day and next-day in Houston, NJ, Atlanta', 'driver-app events', 'picked_up · out_for_delivery · delivered (photo + OTP) · delivery_failed (reason)']]
    .map(([name, type, services, events, mapping], i) => ({ id: 'CAR-' + (10 + i), name, type, services, events, mapping, webhook: 'https://api.afrotransact.com/v1/webhooks/carriers/' + name.toLowerCase().replace(/[^a-z]+/g, '-'), signing: i === 6 ? 'mTLS (internal)' : 'HMAC-SHA256', last: pick(['3 s ago', '12 s ago', '41 s ago']), status: i === 4 ? 'Degraded' : 'Active' }));
  D.routes = [['RT-HOU-AM', 'Chinedu O.', 'Ford Transit · TX-4K21', 'Houston SW', 9, 9, '08:00', 'Completed'], ['RT-HOU-PM', 'Tunde A.', 'Ford Transit · TX-7P10', 'Houston SW', 8, 3, '13:00', 'On route'], ['RT-EDI-AM', 'Priya K.', 'Ram ProMaster · NJ-2Z88', 'Edison / Iselin', 11, 6, '08:30', 'On route'], ['RT-ATL-PM', 'Kwame B.', 'Nissan NV · GA-9H31', 'Atlanta East', 7, 0, '14:00', 'Planned'], ['RT-HOU-EV', 'Unassigned', '—', 'Houston Alief', 6, 0, '17:00', 'Unassigned']]
    .map(([id, driver, vehicle, zone, stops, done, start, status]) => ({ id, driver, vehicle, zone, stops, done, start, status, pickups: Math.ceil(stops / 3) + ' (sellers + FC)', proof: 'Photo + customer OTP' }));

  // ---------- Stripe Connect compliance ----------
  const SP = BO.PORTALS.seller, PP = BO.PORTALS.provider;
  if (!SP.modules.includes('sCompliance')) { SP.modules.push('sCompliance'); const sx = { owner: 'F', admin: 'F', catalog: '-', fulfil: '-', finance: 'E', marketing: '-', support: '-' }; Object.keys(SP.matrix).forEach((r) => { SP.matrix[r] += sx[r] || '-'; }); }
  if (!PP.modules.includes('pCompliance')) { PP.modules.push('pCompliance'); const px = { owner: 'F', manager: 'E', practitioner: '-', finance: 'E' }; Object.keys(PP.matrix).forEach((r) => { PP.matrix[r] += px[r] || '-'; }); }
  // Provider Hub tools embedded in the provider portal + per-role can / can't
  Object.assign(BO.MOD, {
    pCalendar: { label: 'Calendar & schedule', icon: 'ph ph-calendar-dots', desc: 'Day and week calendar of every appointment, block time, reschedule and cancel. Synced live with the shop and app.', kind: 'embed', tab: 'calendar' },
    pAvail: { label: 'Working hours', icon: 'ph ph-clock', desc: 'Weekly hours, breaks, time off and booking rules customers can book against.', kind: 'embed', tab: 'availability' },
    pServices: { label: 'Services & prices', icon: 'ph ph-scissors', desc: 'Services, durations, prices and deposits that flow to the shop and app.', kind: 'embed', tab: 'services' },
    pPolicy: { label: 'Cancellation policy', icon: 'ph ph-shield-check', desc: 'Cancellation, late and no-show rules shown before customers book.', kind: 'embed', tab: 'policy' },
    pPage: { label: 'Public page', icon: 'ph ph-paint-brush', desc: 'Design your page: layout, colours, gallery, featured work and social links.', kind: 'embed', tab: 'profile' }
  });
  (() => { const old = PP.modules.slice(); const lv = {}; Object.entries(PP.matrix).forEach(([r, str]) => { lv[r] = {}; old.forEach((k, ix) => { lv[r][k] = str[ix] || '-'; }); });
    const add = { owner: { pCalendar: 'F', pPolicy: 'F', pPage: 'F' }, manager: { pCalendar: 'F', pAvail: 'F', pServices: 'F', pPolicy: 'E', pPage: 'F' }, practitioner: { pCalendar: 'E', pAvail: 'E', pServices: 'V', pPolicy: 'V', pPage: '-' }, finance: { pCalendar: 'V', pAvail: '-', pServices: 'V', pPolicy: 'V', pPage: '-' } };
    const order = ['pHome', 'pCalendar', 'pBookings', 'pAvail', 'pServices', 'pPolicy', 'pPage', 'pReviews', 'pPayouts', 'pBilling', 'pTeam'].concat(old.filter((k) => !['pHome', 'pCalendar', 'pBookings', 'pAvail', 'pServices', 'pPolicy', 'pPage', 'pReviews', 'pPayouts', 'pBilling', 'pTeam'].includes(k)));
    PP.modules = order; Object.keys(PP.matrix).forEach((r) => { PP.matrix[r] = order.map((k) => (add[r] && add[r][k]) || lv[r][k] || '-').join(''); }); })();
  BO.CAN_DETAIL = {
    pHome: 'today’s appointments and alerts', pCalendar: 'the appointment calendar — block time, reschedule, cancel', pBookings: 'booking requests — accept, decline, mark no-show', pAvail: 'working hours, breaks and time off', pServices: 'services, prices and deposits', pPolicy: 'the cancellation and no-show policy', pPage: 'the public page design, gallery and social links', pReviews: 'reviews and replies', pPayouts: 'payouts and balances', pBilling: 'the Provider Hub plan and invoices', pTeam: 'team members and their roles', pCompliance: 'Stripe verification requirements',
    sHome: 'the store overview', sOrders: 'orders, shipping labels and refunds', sListings: 'product listings', sInventory: 'stock levels', sAds: 'ad campaigns', sStorefront: 'the storefront design', sPayouts: 'payouts and balances', sBilling: 'the plan and invoices', sTeam: 'team members and their roles', sCompliance: 'Stripe verification requirements'
  };
  BO.ROLE_NOTE = { practitioner: 'Practitioners only see and change their own calendar, hours and bookings — never other staff’s.', finance: 'Finance can see money but cannot change bookings, services or the page.', manager: 'Managers run day-to-day operations but cannot change the plan, payout bank account or the owner.', owner: 'The owner can do everything, including transferring ownership and closing the business.' };
  const ST9 = [['AL', 'AL'], ['CA', 'CA'], ['FL', 'FL'], ['GA', 'GA'], ['IL', 'IL'], ['MD', 'MD'], ['MN', 'MN'], ['NJ', 'NJ'], ['NY', 'NY'], ['TX', 'TX'], ['VA', 'VA'], ['WA', 'WA']];
  BO.REQ = {
    'company.tax_id': { label: 'Company tax ID (EIN)', who: 'Business', why: 'Stripe must match the business name to the IRS record before payouts.', fields: [['ein', 'EIN', 'text', null, 3, { ph: '12-3456789', mode: 'numeric', max: 10 }]], api: 'accounts.update { company: { tax_id } }' },
    'company.verification.document': { label: 'Business verification document', who: 'Business', why: 'The legal name or EIN couldn’t be verified automatically.', fields: [['docType', 'Document type', 'select', ['IRS letter 147C', 'IRS SS-4 confirmation (CP 575)', 'Articles of organization', 'State business licence'], 3], ['docFile', 'Upload document', 'file', null, 6]], api: 'files.create (purpose: account_requirement) → accounts.update { company: { verification: { document } } }' },
    'company.ownership_declaration': { label: 'Ownership declaration', who: 'Business', why: 'Confirm the owners listed are complete and correct (FinCEN beneficial ownership).', fields: [['declare', 'I confirm the owners and directors provided are up to date and correct', 'check', null, 6], ['declarer', 'Your full name', 'text', null, 3]], api: 'accounts.update { company: { ownership_declaration: { date, ip, user_agent } } }' },
    'business_profile.url': { label: 'Business website or product description', who: 'Business', why: 'Card networks require a public description of what you sell.', fields: [['url', 'Website or social profile URL', 'url', null, 6, { ph: 'https://' }]], api: 'accounts.update { business_profile: { url } }' },
    'external_account': { label: 'Payout bank account', who: 'Business', why: 'Your bank rejected the last payout (account closed).', fields: [['routing', 'Routing number', 'text', null, 3, { mode: 'numeric', max: 9 }], ['account', 'Account number', 'password', null, 3, { mode: 'numeric', max: 17 }]], api: 'accounts.createExternalAccount (bank_account token)' },
    'individual.id_number': { label: 'Full SSN', who: 'Owner', why: 'The last 4 digits couldn’t be matched; Stripe needs the full number.', fields: [['ssn', 'Full Social Security number', 'password', null, 3, { mode: 'numeric', max: 11, ph: '123-45-6789' }]], api: 'accounts.update { individual: { id_number } } (via Stripe.js token)' },
    'representative.verification.document': { label: 'Representative photo ID', who: 'Representative', why: 'The ID uploaded was blurry or expired.', fields: [['idFront', 'ID front', 'file', null, 3], ['idBack', 'ID back', 'file', null, 3]], api: 'files.create → persons.update { verification: { document: { front, back } } }' },
    'representative.verification.additional_document': { label: 'Proof of address', who: 'Representative', why: 'The home address couldn’t be verified. Upload a utility bill or bank statement under 90 days old.', fields: [['poaType', 'Document', 'select', ['Utility bill', 'Bank statement', 'Lease agreement', 'Government letter'], 3], ['poaFile', 'Upload', 'file', null, 3]], api: 'files.create → persons.update { verification: { additional_document } }' },
    'representative.dob': { label: 'Representative date of birth', who: 'Representative', why: 'Needed to complete identity verification.', fields: [['dob', 'Date of birth', 'date', null, 3]], api: 'persons.update { dob }' },
    'representative.relationship.title': { label: 'Representative job title', who: 'Representative', why: 'Stripe needs the title of the person who controls the account.', fields: [['title', 'Title', 'text', null, 3, { ph: 'CEO, Managing member…' }]], api: 'persons.update { relationship: { title } }' },
    'tos_acceptance': { label: 'Stripe Connected Account Agreement', who: 'Business', why: 'Updated Stripe terms must be accepted to keep accepting payments.', fields: [['tos', 'I accept the Stripe Connected Account Agreement', 'check', null, 6]], api: 'accounts.update { tos_acceptance: { date, ip } }' }
  };
  const acct = (id, name, kind, cur, past, ev, pend, days, payouts, charges, reason) => ({ id, name, kind, currently_due: cur, past_due: past, eventually_due: ev, pending_verification: pend, deadline: days == null ? '' : day(-days), payouts, charges, disabled_reason: reason || '', reminders: 0, events: [{ at: day(Math.max(0, 2 - (days || 0))) + ' 08:12', type: 'account.updated', detail: cur.length || past.length ? 'New requirements: ' + cur.concat(past).join(', ') : 'Requirements satisfied' }] });
  D.connect = [
    acct('acct_seller', "Ade's Kitchen", 'Seller', ['company.verification.document', 'representative.verification.additional_document'], [], ['company.ownership_declaration'], [], 6, 'Enabled', 'Enabled'),
    acct('acct_provider', "Ama's Braiding Studio", 'Provider', [], ['individual.id_number'], [], [], 0, 'Paused', 'Enabled', 'requirements.past_due'),
    acct('acct_1Pm8', "Mama Tee's Pantry", 'Seller', [], [], [], [], null, 'Enabled', 'Enabled'),
    acct('acct_1Pq2', 'Accra Kitchen Co.', 'Seller', ['tos_acceptance'], [], [], [], 12, 'Enabled', 'Enabled'),
    acct('acct_1Pr7', 'Addis Spice House', 'Seller', [], [], [], ['company.tax_id'], null, 'Enabled', 'Enabled'),
    acct('acct_1Ps4', 'Kwik Cargo Inc.', 'Provider', [], ['external_account', 'business_profile.url'], [], [], 0, 'Paused', 'Disabled', 'requirements.past_due'),
    acct('acct_1Pt9', 'Patel Grocers', 'Seller', ['representative.dob', 'representative.relationship.title'], [], [], [], 2, 'Enabled', 'Enabled'),
    acct('acct_1Pu1', 'Lagos Textile Hub', 'Seller', [], [], ['company.ownership_declaration'], [], null, 'Enabled', 'Enabled'),
    acct('acct_1Pv5', 'Jollof & Co. Catering', 'Provider', [], [], [], [], null, 'Enabled', 'Enabled'),
    acct('acct_1Pw3', 'Diaspora Docs', 'Provider', ['representative.verification.document'], [], [], [], 9, 'Enabled', 'Enabled')
  ];
  BO.complianceStatus = (a) => { if (a.past_due.length || a.disabled_reason) return 'Restricted'; if (a.currently_due.length) { const left = (new Date(a.deadline) - new Date(2026, 9, 9)) / 864e5; return left <= 3 ? 'Restricted soon' : 'Action needed'; } if (a.pending_verification.length) return 'Verifying'; return 'Compliant'; };
  D.connect.forEach((a) => { a.status = BO.complianceStatus(a); a.open = a.currently_due.length + a.past_due.length; });

  // ---------- module definitions ----------
  const money = (v) => ({ text: BO.usd(v) });
  Object.assign(BO.MOD, {
    plansCfg: { label: 'Plans & subscriptions', icon: 'ph ph-stack', desc: 'Seller and provider plans billed with Stripe Billing. Price changes create a new Stripe Price and apply at next renewal.', res: 'plansCfg', noun: 'plans', creatable: true,
      cols: [C('name', 'Plan', strong), C('audience', 'For', null, EQ), C('price', 'Monthly', money), C('quarterly', 'Quarterly (−5%)', money), C('annual', 'Annual (2 mo free)', money), C('trial', 'Trial days'), C('commAdj', 'Commission adj. (pts)'), C('users', 'Users'), C('status', 'Status', tag, EQ)], facets: [{ id: 'audience', label: 'Audience' }],
      form: [['name', 'Plan name', 'text'], ['audience', 'Audience', 'select', ['Seller', 'Provider']], ['price', 'Monthly price ($)', 'number'], ['trial', 'Trial days', 'number'], ['commAdj', 'Commission adjustment (percentage points)', 'number'], ['listings', 'Listing / service limit', 'number'], ['users', 'Team members', 'number'], ['features', 'Features', 'text']],
      bulk: [['Retire', { status: 'Retired' }, 'F'], ['Reactivate', { status: 'Active' }, 'F']] },
    coupons: { label: 'Coupons & promotions', icon: 'ph ph-ticket', desc: 'Discount codes with scope, limits and funding. Seller-funded coupons reduce that seller’s payout; platform-funded ones are booked as marketing expense.', res: 'coupons', noun: 'coupons', creatable: true,
      cols: [C('code', 'Code', strong), C('type', 'Type'), C('value', 'Value'), C('scope', 'Scope'), C('min', 'Min spend', money), C('used', 'Used'), C('ends', 'Ends'), C('status', 'Status', tag, EQ)], facets: [{ id: 'status', label: 'Status' }],
      form: [['code', 'Code', 'text'], ['type', 'Discount type', 'select', ['%', '$', 'Free shipping']], ['value', 'Value', 'number'], ['scope', 'Applies to', 'select', ['Sitewide', 'First order', 'Category: Groceries', 'Category: Fashion', 'Category: Party', 'Community: Indian', 'Fulfilled by AfroTransact', 'Services: Hair & beauty']], ['min', 'Minimum spend ($)', 'number'], ['limit', 'Total redemptions (0 = unlimited)', 'number'], ['starts', 'Starts', 'date'], ['ends', 'Ends', 'date'], ['funding', 'Funded by', 'select', ['AfroTransact-funded', 'Seller-funded']]],
      bulk: [['Pause', { status: 'Paused' }, 'E'], ['Activate', { status: 'Active' }, 'E'], ['Expire now', { status: 'Expired' }, 'E']] },
    pickup: { label: 'Pickup & delivery', icon: 'ph ph-map-trifold', desc: 'Pickup points, lockers and partner stores, plus same-day cut-offs. Shoppers see these as delivery options at checkout.', res: 'pickups', noun: 'pickup points', creatable: true,
      cols: [C('name', 'Location', strong), C('city', 'City', null, EQ), C('type', 'Type'), C('hours', 'Hours'), C('sameDay', 'Same day'), C('cutoff', 'Cut-off'), C('status', 'Status', tag, EQ)], facets: [{ id: 'city', label: 'City' }],
      form: [['name', 'Name', 'text'], ['city', 'City, state', 'text'], ['type', 'Type', 'select', ['FC counter', 'Locker (16 bays)', 'Locker (24 bays)', 'Partner store']], ['hours', 'Hours', 'text'], ['sameDay', 'Same-day pickup', 'select', ['Yes', 'No']], ['cutoff', 'Same-day cut-off', 'text']],
      bulk: [['Pause', { status: 'Paused' }, 'E'], ['Activate', { status: 'Active' }, 'E']] },
    markets: { label: 'Markets & locations', icon: 'ph ph-globe-hemisphere-west', desc: 'Turn AfroTransact on or off by country and by state or province, and choose what is allowed in each.', kind: 'markets' },
    siteCfg: { label: 'Site content & legal', icon: 'ph ph-scales', desc: 'Social links, company phone and contacts, and legal document versions. Published instantly to the shop, mobile app and legal pages — no deployment.', kind: 'settings' },
    storeCfg: { label: 'Storefront settings', icon: 'ph ph-paint-roller', desc: 'Rules every seller storefront and theme must follow.', kind: 'settings' },
    flags: { label: 'Feature flags', icon: 'ph ph-flag', desc: 'OpenFeature flags backed by Unleash. Deploy dark, release by percentage, kill instantly.', res: 'flags', noun: 'flags', creatable: true,
      cols: [C('key', 'Flag', mono), C('desc', 'What it does'), C('pct', 'Rollout %'), C('owner', 'Team', null, EQ), C('status', 'State', tag, EQ)], facets: [{ id: 'status', label: 'State' }],
      form: [['key', 'Flag key', 'text'], ['desc', 'Description', 'text'], ['pct', 'Rollout %', 'number'], ['owner', 'Owning team', 'select', ['Growth', 'Checkout', 'Search', 'Lola', 'Seller']], ['status', 'State', 'select', ['On', 'Off', 'Rollout', 'Staff only']]],
      bulk: [['Turn on', { status: 'On', pct: 100 }, 'F'], ['Kill switch', { status: 'Off', pct: 0 }, 'F']] },
    inventory: { label: 'Inventory', icon: 'ph ph-warehouse', desc: 'AfroTransact-owned stock by fulfilment centre. Every platform product on the storefront is sold from here; checkout reserves stock, shipping commits it, returns restock it — all through one versioned, locked ledger.', kind: 'inventory' },
    shipping: { label: 'Shipping integrations', icon: 'ph ph-truck', desc: 'Carriers and aggregators that push tracking webhooks. Each carrier event is mapped to an AfroTransact shipment status and applied with actor “Carrier webhook”.', res: 'carriers', noun: 'carriers', creatable: true, kind: 'shipping',
      cols: [C('name', 'Carrier', strong), C('type', 'Type', null, EQ), C('services', 'Services'), C('events', 'Webhook events', mono), C('last', 'Last event'), C('status', 'Status', tag, EQ)], facets: [{ id: 'type', label: 'Type' }],
      form: [['name', 'Name', 'text'], ['type', 'Type', 'select', ['Aggregator', 'Carrier', 'Internal']], ['services', 'Services', 'text'], ['webhook', 'Webhook URL', 'text'], ['events', 'Subscribed events', 'text'], ['mapping', 'Status mapping', 'text'], ['signing', 'Signature verification', 'select', ['HMAC-SHA256', 'mTLS (internal)', 'JWT']]],
      bulk: [['Pause', { status: 'Paused' }, 'E'], ['Activate', { status: 'Active' }, 'E']] },
    dispatch: { label: 'Delivery dispatch', icon: 'ph ph-path', desc: 'AfroTransact Delivery: internal drivers pick up from sellers and our warehouses and deliver to customers. Driver-app events update shipment status in real time.', res: 'routes', noun: 'routes', kind: 'dispatch', creatable: true,
      cols: [C('id', 'Route', mono), C('driver', 'Driver', strong), C('zone', 'Zone', null, EQ), C('stops', 'Stops'), C('done', 'Done'), C('start', 'Start'), C('status', 'Status', tag, EQ)], facets: [{ id: 'status', label: 'Status' }],
      form: [['driver', 'Driver', 'select', ['Chinedu O.', 'Tunde A.', 'Priya K.', 'Kwame B.', 'Unassigned']], ['vehicle', 'Vehicle', 'text'], ['zone', 'Zone', 'select', ['Houston SW', 'Houston Alief', 'Edison / Iselin', 'Atlanta East']], ['stops', 'Stops', 'number'], ['start', 'Start time', 'text']],
      bulk: [['Mark on route', { status: 'On route' }, 'E'], ['Complete', { status: 'Completed' }, 'E']] },
    compliance: { label: 'Stripe compliance', icon: 'ph ph-shield-warning', desc: 'Connected-account requirements synced from Stripe webhooks (account.updated, capability.updated, person.updated, payout.failed). We notify sellers on every change and before each deadline, pause payouts when requirements go past due, and hide listings if Stripe disables charges.', res: 'connect', noun: 'accounts',
      cols: [C('name', 'Account', strong), C('kind', 'Type', null, EQ), C('open', 'Open items'), C('deadline', 'Deadline'), C('payouts', 'Payouts'), C('charges', 'Charges'), C('status', 'Status', tag, EQ)], facets: [{ id: 'status', label: 'Status' }, { id: 'kind', label: 'Type' }] },
    sCompliance: { label: 'Account health', icon: 'ph ph-shield-check', desc: 'What Stripe needs from you to keep payments and payouts on. Answers go straight to Stripe through AfroTransact — no separate Stripe login needed.', res: 'connect', kind: 'compliance' },
    pCompliance: { label: 'Account health', icon: 'ph ph-shield-check', desc: 'What Stripe needs from you to keep bookings and payouts on.', res: 'connect', kind: 'compliance' },
    access: { label: 'Access control', icon: 'ph ph-key', desc: 'Grant access by role, by capability, or directly to a person. Changes apply instantly across the backoffice, driver app and scanners, and are logged.', kind: 'access' },
    dev: { label: 'Developers', icon: 'ph ph-code', desc: 'API keys, webhooks and Lola’s AI model routing (OpenRouter).', kind: 'devhub' }
  });
  Object.assign(BO.STATUS_TONE_EXTRA = {}, {});
  const ST = { Expired: 'info', Retired: 'info', Failing: 'bad', On: 'good', Off: 'info', Rollout: 'brand', 'Staff only': 'warn', Revoked: 'bad' };

  // ---------- settings forms (generic renderer) ----------
  BO.SETTINGS = {
    siteCfg: (() => { const A = window.ATSite; if (!A) return []; const d = A.toSettings(); const g = A.get();
      return [
        { title: 'Social links (shown in shop footer, mobile Help and legal pages)', fields: g.socials.flatMap((x) => [['so_' + x.net + '_on', A.NETS[x.net][0] + ' — show', 'toggle', d['so_' + x.net + '_on']], ['so_' + x.net + '_url', A.NETS[x.net][0] + ' — profile URL', 'text', d['so_' + x.net + '_url']]]) },
        { title: 'Company contacts', fields: [['ct_phone', 'Customer service phone', 'text', d.ct_phone], ['ct_privacy', 'Privacy email', 'text', d.ct_privacy], ['ct_accessibility', 'Accessibility email', 'text', d.ct_accessibility], ['ct_legal', 'Legal email', 'text', d.ct_legal], ['ct_address', 'Registered address', 'text', d.ct_address]] },
        { title: 'Legal documents — bumping a version archives the previous one', fields: A.DOC_KEYS.flatMap((k) => [['lg_' + k + '_v', A.t('d.' + k, 'en') + ' — version', 'text', d['lg_' + k + '_v']], ['lg_' + k + '_eff', A.t('d.' + k, 'en') + ' — effective date (YYYY-MM-DD)', 'text', d['lg_' + k + '_eff']]]).concat([['draftBanner', 'Show “draft for counsel review” banner', 'toggle', d.draftBanner]]) }
      ]; })(),
    storeCfg: [
      { title: 'Themes', fields: [['minContrastText', 'Minimum contrast for text', 'select', '4.5', ['4.5', '7.0']], ['minContrastUi', 'Minimum contrast for buttons & edges', 'select', '3.0', ['3.0', '4.5']], ['allowFonts', 'Sellers may pick fonts', 'toggle', false], ['lockCheckout', 'Checkout, prices and header stay platform-styled', 'toggle', true]] },
      { title: 'Layout', fields: [['maxSections', 'Maximum sections per storefront', 'number', 8], ['maxFeatured', 'Featured products per section', 'number', 12], ['allowVideo', 'Hero video', 'toggle', true], ['customDomains', 'Custom domains (Pro plan)', 'toggle', false]] },
      { title: 'Moderation', fields: [['aiCopyCheck', 'AI check of storefront copy before publish', 'toggle', true], ['imageScan', 'Image moderation on upload', 'toggle', true], ['republishReview', 'Human review when a suspended seller republishes', 'toggle', true]] }
    ],
    devGeneral: [
      { title: 'API', fields: [['rateLimit', 'Default rate limit (requests/min per key)', 'number', 600], ['webhookRetries', 'Webhook retries (exponential backoff)', 'number', 8], ['signing', 'Webhook signing', 'select', 'HMAC-SHA256', ['HMAC-SHA256', 'Ed25519']], ['sandbox', 'Sandbox environment for partners', 'toggle', true]] },
      { title: 'Lola & AI', fields: [['aiMonthlyBudget', 'Platform AI budget ($/month)', 'number', 2500], ['piiRedaction', 'Redact PII before any model call', 'toggle', true], ['confirmSpend', 'Lola must confirm before checkout or booking', 'toggle', true], ['logPrompts', 'Store prompts for evals (30 days)', 'toggle', true]] }
    ]
  };

  // ---------- markets ----------
  const US = [['AL', 'Alabama'], ['AK', 'Alaska'], ['AZ', 'Arizona'], ['AR', 'Arkansas'], ['CA', 'California'], ['CO', 'Colorado'], ['CT', 'Connecticut'], ['DE', 'Delaware'], ['DC', 'District of Columbia'], ['FL', 'Florida'], ['GA', 'Georgia'], ['HI', 'Hawaii'], ['ID', 'Idaho'], ['IL', 'Illinois'], ['IN', 'Indiana'], ['IA', 'Iowa'], ['KS', 'Kansas'], ['KY', 'Kentucky'], ['LA', 'Louisiana'], ['ME', 'Maine'], ['MD', 'Maryland'], ['MA', 'Massachusetts'], ['MI', 'Michigan'], ['MN', 'Minnesota'], ['MS', 'Mississippi'], ['MO', 'Missouri'], ['MT', 'Montana'], ['NE', 'Nebraska'], ['NV', 'Nevada'], ['NH', 'New Hampshire'], ['NJ', 'New Jersey'], ['NM', 'New Mexico'], ['NY', 'New York'], ['NC', 'North Carolina'], ['ND', 'North Dakota'], ['OH', 'Ohio'], ['OK', 'Oklahoma'], ['OR', 'Oregon'], ['PA', 'Pennsylvania'], ['RI', 'Rhode Island'], ['SC', 'South Carolina'], ['SD', 'South Dakota'], ['TN', 'Tennessee'], ['TX', 'Texas'], ['UT', 'Utah'], ['VT', 'Vermont'], ['VA', 'Virginia'], ['WA', 'Washington'], ['WV', 'West Virginia'], ['WI', 'Wisconsin'], ['WY', 'Wyoming']];
  const LIVE = ['TX', 'NY', 'NJ', 'GA', 'MD', 'DC', 'VA', 'FL', 'MN', 'IL', 'CA', 'MA', 'PA', 'NC', 'OH', 'WA'];
  const region = (code, name, on) => ({ code, name, marketplace: on, services: on && code !== 'CA', food: on, pickup: ['TX', 'NJ', 'GA', 'FL', 'MN'].includes(code), taxReg: on });
  BO.MARKETS = [
    { code: 'US', name: 'United States', currency: 'USD', status: 'Live', regionLabel: 'States', regions: US.map(([c, n]) => region(c, n, LIVE.includes(c))) },
    { code: 'CA', name: 'Canada', currency: 'CAD', status: 'Coming soon', regionLabel: 'Provinces', regions: [['ON', 'Ontario'], ['QC', 'Quebec'], ['BC', 'British Columbia'], ['AB', 'Alberta'], ['MB', 'Manitoba'], ['SK', 'Saskatchewan'], ['NS', 'Nova Scotia'], ['NB', 'New Brunswick']].map(([c, n]) => region(c, n, false)) },
    { code: 'GB', name: 'United Kingdom', currency: 'GBP', status: 'Planned', regionLabel: 'Nations', regions: [['ENG', 'England'], ['SCT', 'Scotland'], ['WLS', 'Wales'], ['NIR', 'Northern Ireland']].map(([c, n]) => region(c, n, false)) }
  ];
  BO.MKEY = 'at.markets.v1';

  // ---------- onboarding schema ----------
  BO.ENTITIES = [['sole', 'Sole proprietor', 'You sell under your own name or a DBA. We collect your SSN or ITIN.'], ['smllc', 'Single-member LLC', 'One owner. Uses an EIN (or the owner’s SSN if disregarded).'], ['mmllc', 'Multi-member LLC', 'Two or more members. EIN plus every owner with 25%+.'], ['corp', 'Corporation (C or S)', 'EIN, state of incorporation, officers and 25%+ owners.'], ['partnership', 'Partnership', 'EIN and every general partner.'], ['nonprofit', 'Nonprofit', 'EIN and IRS determination letter (501(c)(3)).']];
  BO.SERVICE_LICENCES = { 'Hair & beauty': 'State cosmetology / natural-hair-care licence', Catering: 'Food handler permit + county caterer permit', 'Shipping to Africa': 'FMC Ocean Transportation Intermediary licence', 'Immigration, tax & legal': 'Bar number (attorney) or IRS PTIN / EA', Translation: 'ATA certification (for certified translations)', 'Home services': 'State contractor licence where required + insurance', 'Events & DJ': 'Business registration + general liability insurance', Tailoring: 'Business registration' };
  BO.PRODUCT_CATS = ['Groceries', 'Prepared food', 'Fashion', 'Beauty', 'Home & Art', 'Books', 'Health', 'Party'];

  // ---------- application dossier ----------
  const hash = (s) => [...s].reduce((a, c) => (a * 31 + c.charCodeAt(0)) >>> 0, 7);
  BO.appDossier = (app) => {
    if (app.detail) return app.detail;
    const h = hash(app.id); const sole = app.kind === 'Provider' && h % 2 === 0;
    const entity = sole ? 'sole' : ['smllc', 'mmllc', 'corp'][h % 3];
    const owners = entity === 'sole' || entity === 'smllc' ? [{ name: ['Ama Owusu', 'Adewale Ade', 'Kwame Asante', 'Hanna Tesfaye'][h % 4], title: 'Owner', pct: 100, dob: '198' + (h % 9) + '-••-••', ssn: '•••-••-' + (1000 + (h % 8999)), idv: 'Verified', control: true }] :
      [{ name: 'Adewale Ade', title: 'CEO', pct: 60, dob: '1981-••-••', ssn: '•••-••-6789', idv: 'Verified', control: true }, { name: 'Funmi Ade', title: 'COO', pct: 40, dob: '1984-••-••', ssn: '•••-••-1142', idv: 'Verified', control: false }];
    return {
      entity, entityLabel: (BO.ENTITIES.find((e) => e[0] === entity) || ['', ''])[1], offering: app.kind === 'Provider' ? 'Services' : 'Products',
      business: [['Legal name', app.name], ['DBA', app.kind === 'Seller' ? app.name.replace(/ (LLC|Inc\.)$/, '') : '—'], [entity === 'sole' ? 'SSN' : 'EIN', entity === 'sole' ? '•••-••-' + (1000 + (h % 8999)) : '••-•••' + (1000 + (h % 8999))], ['Formation', entity === 'sole' ? '—' : app.state + ' · ' + day(900 + (h % 900))], ['Business address', ['9800 Westheimer Rd, Houston, TX 77042', '455 Edgewood Ave SE, Atlanta, GA 30312', '1430 Oak Tree Rd, Iselin, NJ 08830'][h % 3]], ['Phone', '+1 (' + (700 + (h % 200)) + ') 555-01' + (10 + (h % 89))], ['Email', 'owner@' + app.name.toLowerCase().replace(/[^a-z]+/g, '') + '.com'], ['Website', h % 2 ? 'https://' + app.name.toLowerCase().replace(/[^a-z]+/g, '') + '.com' : '—'], ['NAICS', app.kind === 'Provider' ? '812111 Personal care' : '445298 Specialty food']],
      owners,
      bank: [['Bank', ['Chase', 'Bank of America', 'Wells Fargo'][h % 3]], ['Account', 'Checking ••' + (1000 + (h % 8999))], ['Routing', '•••••' + (1000 + (h % 8999))], ['Holder', app.name], ['Verification', 'Instant (Financial Connections) · name match 98%']],
      plan: [['Plan', app.plan], ['Trial ends', day(-30)], ['Billing', 'Visa ••' + (1000 + (h % 8999)) + ' · Stripe customer cus_' + h.toString(36)]],
      categories: app.kind === 'Provider' ? ['Hair & beauty'] : ['Groceries', 'Prepared food'],
      permits: (app.checks || []).filter(([l]) => /licen|permit|FMC|insur/i.test(l)).map(([label, ok, note]) => ({ label, number: ok ? 'No. ' + (100000 + (h % 899999)) : 'Submitted', status: ok ? 'Verified' : 'Needs attention', note })),
      docs: [['Identity (KYC) — ' + owners[0].name, 'Stripe Connect', 'Verified'], [entity === 'sole' ? 'W-9 (SSN)' : 'W-9 (EIN)', 'Uploaded PDF', 'Verified'], ...(entity === 'sole' ? [] : [['Articles of organization', 'Uploaded PDF', 'Verified']]), ['Bank statement (last 3 months)', 'Uploaded PDF', 'Received'], ...(app.kind === 'Provider' ? [['Licence certificate', 'Uploaded JPG', (app.checks || []).some(([, ok]) => !ok) ? 'Needs attention' : 'Verified'], ['Insurance certificate', 'Uploaded PDF', 'Verified']] : [['Food establishment permit', 'Uploaded PDF', 'Verified']])].map(([name, src, status]) => ({ name, src, status })),
      signals: [['Sign-up IP', 'US · ' + app.state + ' · residential', 'good'], ['Email domain age', h % 3 ? '4 years' : '11 days', h % 3 ? 'good' : 'warn'], ['Phone', 'Mobile · carrier verified', 'good'], ['Duplicate accounts', 'None found (device, bank, TIN)', 'good'], ['Prior AfroTransact history', h % 4 ? 'New' : 'Former buyer since 2024', 'info'], ['Sanctions / PEP', 'Clear', 'good']],
      timeline: [[app.submitted + ' 09:14', 'Application submitted'], [app.submitted + ' 09:15', 'Stripe Connect KYC: all owners verified · IRS TIN match passed'], [app.submitted + ' 09:16', 'OFAC / PEP screening clear'], [app.submitted + ' 09:20', 'IRS TIN match ' + ((app.checks || []).find(([l]) => /TIN/.test(l)) ? 'completed' : 'not required')], [app.submitted + ' 09:22', 'Lola risk triage: ' + app.risk]]
    };
  };
  BO.EXTRA_TONE = ST;
  BO.__configReady = true;
};
window.__atCfgBoot();
// Re-apply if backoffice-data.js re-initialises ATBO (helmet scripts can re-run on hot reload)
setInterval(() => { if (window.ATBO && !window.ATBO.__configReady) window.__atCfgBoot(); }, 150);
