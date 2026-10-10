/* AfroTransact booking engine — one source of truth for provider availability, prices,
   bookings, cancellation/reschedule rules and the money split. Used by the web shop,
   the customer app and the Provider Hub. Persists to localStorage and syncs across tabs. */
(function () {
  const KEY = 'at.bookings.v2';
  const H = 36e5, MIN = 6e4, DAY = 864e5;
  const DOW = ['sun', 'mon', 'tue', 'wed', 'thu', 'fri', 'sat'];
  const DOW_LABEL = { mon: 'Monday', tue: 'Tuesday', wed: 'Wednesday', thu: 'Thursday', fri: 'Friday', sat: 'Saturday', sun: 'Sunday' };
  const POLICIES = {
    flexible: { label: 'Flexible', free: 24, rows: [[24, 1]], text: 'Free cancellation or reschedule up to 24 hours before. After that the deposit is kept.' },
    moderate: { label: 'Moderate', free: 48, rows: [[48, 1], [24, 0.5]], text: 'Free up to 48 hours before. 24–48 hours: half the deposit is refunded. Under 24 hours: deposit kept.' },
    strict: { label: 'Strict', free: 168, rows: [[168, 1]], text: 'Free up to 7 days before. After that the deposit is kept.' }
  };
  const DEF_CUSTOM = { free: 48, partial: true, partialFrom: 24, partialPct: 50, noShow: 'deposit', reschedHrs: 24, maxResched: 2 };
  const hrsLabel = (h) => (h >= 168 && h % 24 === 0 ? (h / 24 === 7 ? '7 days' : h / 24 + ' days') : h >= 48 && h % 24 === 0 ? h / 24 + ' days' : h + ' hours');
  function policyFor(p) {
    if (p.policy !== 'custom') { const b = POLICIES[p.policy] || POLICIES.moderate; return { key: p.policy, label: b.label, free: b.free, rows: b.rows, text: b.text, noShow: 'deposit', reschedHrs: b.free, maxResched: 2 }; }
    const c = { ...DEF_CUSTOM, ...(p.customPolicy || {}) }; const rows = [[c.free, 1]]; if (c.partial && c.partialFrom < c.free && c.partialPct > 0) rows.push([c.partialFrom, c.partialPct / 100]);
    const text = 'Free cancellation up to ' + hrsLabel(c.free) + ' before.' + (rows.length > 1 ? ' ' + hrsLabel(c.partialFrom) + '–' + hrsLabel(c.free) + ' before: ' + c.partialPct + '% of the deposit back.' : '') + ' Later than that the deposit is kept.' + (c.noShow === 'full' ? ' No-shows are charged the full price.' : '') + ' Reschedule free up to ' + hrsLabel(c.reschedHrs) + ' before (max ' + c.maxResched + ').';
    return { key: 'custom', label: 'Custom', free: c.free, rows, text, noShow: c.noShow, reschedHrs: c.reschedHrs, maxResched: c.maxResched };
  }
  const snapOf = (b) => b.policySnap || (() => { const x = POLICIES[b.policy] || POLICIES.moderate; return { key: b.policy, label: x.label, free: x.free, rows: x.rows, text: x.text, noShow: 'deposit', reschedHrs: x.free, maxResched: 2 }; })();
  const STATUS = {
    requested: ['Awaiting provider', 'warn'], confirmed: ['Confirmed', 'ok'], reschedule_requested: ['New time requested', 'warn'], reschedule_proposed: ['Provider proposed a new time', 'warn'],
    completed: ['Completed', 'ok'], no_show: ['No-show', 'bad'], declined: ['Declined by provider', 'bad'], expired: ['Expired · not confirmed', 'muted'],
    cancelled_customer: ['Cancelled by customer', 'muted'], cancelled_provider: ['Cancelled by provider', 'bad']
  };
  const ACTIVE = ['requested', 'confirmed', 'reschedule_requested', 'reschedule_proposed'];
  const r2 = (x) => Math.round(x * 100) / 100;
  const usd = (x) => (x < 0 ? '−$' : '$') + Math.abs(x).toFixed(2);
  const pad = (n) => String(n).padStart(2, '0');
  const isoDay = (t) => { const d = new Date(t); return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate()); };
  const dayStart = (iso) => { const [y, m, d] = iso.split('-').map(Number); return new Date(y, m - 1, d).getTime(); };
  const hm = (s) => { const [h, m] = s.split(':').map(Number); return h * 60 + m; };
  const fmtTime = (t) => new Date(t).toLocaleTimeString('en-US', { hour: 'numeric', minute: '2-digit' });
  const fmtDay = (t) => new Date(t).toLocaleDateString('en-US', { weekday: 'short', month: 'short', day: 'numeric' });
  const fmtWhen = (t) => fmtDay(t) + ' · ' + fmtTime(t);
  const wk = (a, b, br) => ({ on: true, ranges: br ? [[a, br[0]], [br[1], b]] : [[a, b]] });
  const OFF = { on: false, ranges: [['09:00', '17:00']] };

  function seedProviders() {
    const P = (id, name, kind, city, tz, rating, reviews, license, desc, services, weekly, rules) => ({ id, name, kind, city, tz, rating, reviews, license, desc, services, weekly, blackouts: [], profile: { tagline: rules.tagline || desc, bio: rules.bio || desc, languages: rules.langs || 'English', mode: rules.mode || 'Studio', years: rules.years || 5, highlights: rules.hl || [] }, customPolicy: { ...DEF_CUSTOM }, policy: rules.policy || 'moderate', instant: !!rules.instant, autoReschedule: rules.autoReschedule !== false, lead: rules.lead || 12, horizon: rules.horizon || 30, buffer: rules.buffer || 15, step: rules.step || 30, maxPerDay: rules.maxPerDay || 6, strikes: 0 });
    const S = (id, name, dur, price, deposit, desc) => ({ id, name, dur, price, deposit, desc: desc || '', active: true });
    return [
      P('s1', "Aminata's Braids", 'Hair & beauty', 'Houston, TX', 'America/Chicago', 4.9, 312, 'TX cosmetology licence verified', 'Knotless, Fulani and micro braids. Studio or home visits within 20 miles.',
        [S('s1a', 'Knotless box braids · medium', 300, 180, 40, 'Hair included · mid-back length'), S('s1b', 'Fulani braids', 240, 160, 40), S('s1c', 'Cornrows · 8–10 rows', 120, 75, 20), S('s1d', 'Braid takedown & wash', 90, 55, 15)],
        { mon: OFF, tue: wk('10:00', '18:00'), wed: wk('10:00', '18:00'), thu: wk('10:00', '20:00', ['14:00', '14:30']), fri: wk('09:00', '20:00'), sat: wk('08:00', '18:00'), sun: wk('12:00', '17:00') }, { policy: 'moderate', buffer: 30, maxPerDay: 3, lead: 12, tagline: 'Neat, long-lasting braids from a Dakar-trained stylist', bio: 'I’ve been braiding for 14 years — first in Dakar, now in Houston. I use pre-stretched, itch-free hair and finish every style with a hot-water seal. Studio near Westheimer, or home visits within 20 miles.', langs: 'English, French, Wolof', mode: 'Studio or home visits', years: 14, hl: ['Hair included', 'Kids welcome', 'Free parking'] }),
      P('s2', 'Jollof & Co. Catering', 'Catering', 'Dallas, TX', 'America/Chicago', 4.8, 140, 'Food handler + county caterer permit verified', 'Owambe and naming-ceremony trays for 20–200 guests.',
        [S('s2a', 'Party trays · 25 guests', 240, 420, 100, 'Jollof, fried rice, chicken, small chops'), S('s2b', 'Party trays · 50 guests', 300, 780, 200), S('s2c', 'Tasting consultation', 60, 40, 40)],
        { mon: OFF, tue: OFF, wed: wk('10:00', '18:00'), thu: wk('10:00', '18:00'), fri: wk('10:00', '21:00'), sat: wk('08:00', '22:00'), sun: wk('10:00', '20:00') }, { policy: 'strict', buffer: 60, maxPerDay: 2, lead: 72, horizon: 90 }),
      P('s3', 'Adire Tailors', 'Tailoring', 'Brooklyn, NY', 'America/New_York', 4.7, 98, 'Business registration verified', 'Agbada, kaftans and aso-ebi sewn to measure; video fittings.',
        [S('s3a', 'Measurement & fitting', 45, 30, 30), S('s3b', 'Agbada · made to measure', 60, 260, 80), S('s3c', 'Kaftan · made to measure', 60, 140, 40)],
        { mon: wk('10:00', '19:00'), tue: wk('10:00', '19:00'), wed: wk('10:00', '19:00'), thu: wk('10:00', '19:00'), fri: wk('10:00', '17:00'), sat: wk('09:00', '15:00'), sun: OFF }, { policy: 'flexible', instant: true, buffer: 15, maxPerDay: 8 }),
      P('s4', 'DJ Kwame Sounds', 'Events & DJ', 'Atlanta, GA', 'America/New_York', 4.9, 76, 'Insured · business verified', 'Afrobeats, amapiano, highlife and bhangra sets with MC.',
        [S('s4a', '4-hour set with MC', 240, 650, 150), S('s4b', '6-hour set with MC + lights', 360, 980, 250)],
        { mon: OFF, tue: OFF, wed: OFF, thu: wk('17:00', '23:30'), fri: wk('16:00', '23:30'), sat: wk('12:00', '23:30'), sun: wk('12:00', '22:00') }, { policy: 'strict', buffer: 90, maxPerDay: 1, lead: 72, horizon: 120 }),
      P('s6', 'Diaspora Docs', 'Immigration, tax & legal', 'Silver Spring, MD', 'America/New_York', 4.8, 201, 'Attorney bar no. verified · IRS PTIN verified', 'Immigration filings with a licensed attorney; ITIN and tax prep.',
        [S('s6a', 'Immigration consultation · video', 45, 120, 40), S('s6b', 'ITIN application prep', 60, 150, 40)],
        { mon: wk('09:00', '17:00', ['12:00', '13:00']), tue: wk('09:00', '17:00', ['12:00', '13:00']), wed: wk('09:00', '17:00', ['12:00', '13:00']), thu: wk('09:00', '17:00', ['12:00', '13:00']), fri: wk('09:00', '13:00'), sat: OFF, sun: OFF }, { policy: 'flexible', instant: true, buffer: 15, maxPerDay: 8, lead: 4 }),
      P('s8', 'Naija Handyman', 'Home services', 'Houston, TX', 'America/Chicago', 4.6, 57, 'Insured · background-checked', 'Mounting, assembly, minor plumbing and electrical.',
        [S('s8a', 'TV mounting', 90, 85, 20), S('s8b', 'Furniture assembly · 2 hrs', 120, 110, 20), S('s8c', 'Minor plumbing visit', 60, 95, 25)],
        { mon: wk('08:00', '18:00'), tue: wk('08:00', '18:00'), wed: wk('08:00', '18:00'), thu: wk('08:00', '18:00'), fri: wk('08:00', '18:00'), sat: wk('09:00', '14:00'), sun: OFF }, { policy: 'flexible', buffer: 30, maxPerDay: 5, lead: 6 })
    ];
  }

  function nextOpen(p, sv, fromDays, hour) {
    for (let i = fromDays; i < fromDays + 21; i++) { const t = new Date(); t.setHours(0, 0, 0, 0); t.setDate(t.getDate() + i); const w = p.weekly[DOW[t.getDay()]]; if (w && w.on) { const st = hm(w.ranges[0][0]); const h = Math.max(st, hour * 60); if (h + sv.dur <= hm(w.ranges[w.ranges.length - 1][1])) return t.getTime() + h * MIN; } }
    return Date.now() + fromDays * DAY;
  }

  function seed() {
    const providers = seedProviders(); const p1 = providers[0]; const now = Date.now();
    const mk = (id, sv, start, cust, status, extraLedger, hist) => ({ id, pid: p1.id, sid: sv.id, svcName: sv.name, dur: sv.dur, price: sv.price, deposit: sv.deposit, start, end: start + sv.dur * MIN, customer: cust, status, created: now - 2 * H, policy: p1.policy, reschedules: 0, notes: '', pay: 'Visa •• 4242', ledger: extraLedger, history: hist });
    const A = { name: 'Adaeze Nwosu', email: 'adaeze@example.com', phone: '(713) 555-0142' };
    const B = { name: 'Folake Bello', email: 'folake.b@example.com', phone: '(832) 555-0190' };
    const C = { name: 'Chioma Eze', email: 'chioma.e@example.com', phone: '(281) 555-0133' };
    const t1 = nextOpen(p1, p1.services[0], 3, 10), t2 = nextOpen(p1, p1.services[2], 1, 14), t3 = now - 2 * DAY;
    return {
      providers,
      bookings: [
        { ...mk('BK-24018', p1.services[0], t1, A, 'requested', [{ t: 'auth', amt: 40, at: now - 30 * MIN }], [{ at: now - 30 * MIN, by: 'customer', a: 'Requested · $40.00 deposit authorised' }]), created: now - 30 * MIN },
        mk('BK-24011', p1.services[2], t2, B, 'confirmed', [{ t: 'auth', amt: 20, at: now - DAY }, { t: 'capture', amt: 20, at: now - 20 * H }], [{ at: now - DAY, by: 'customer', a: 'Requested' }, { at: now - 20 * H, by: 'provider', a: 'Confirmed · deposit captured' }]),
        mk('BK-23990', p1.services[1], t3, C, 'completed', [{ t: 'auth', amt: 40, at: t3 - 5 * DAY }, { t: 'capture', amt: 40, at: t3 - 5 * DAY + H }, { t: 'charge', amt: 120, at: t3 + 4 * H }], [{ at: t3 - 5 * DAY, by: 'customer', a: 'Requested' }, { at: t3 - 5 * DAY + H, by: 'provider', a: 'Confirmed' }, { at: t3 + 4 * H, by: 'provider', a: 'Marked complete · balance $120.00 charged' }])
      ],
      seq: 24019
    };
  }

  let mem = null; const subs = new Set();
  function load() {
    if (mem) return mem;
    try { mem = JSON.parse(localStorage.getItem(KEY) || 'null'); } catch (e) { mem = null; }
    if (!mem || !mem.providers) { mem = seed(); persist(); }
    mem.providers.forEach((p) => { if (!p.profile) p.profile = { tagline: p.desc, bio: p.desc, languages: 'English', mode: 'Studio', years: 5, highlights: [] }; if (!p.customPolicy) p.customPolicy = { ...DEF_CUSTOM }; const pr = p.profile; if (!p.plan) p.plan = p.id === 's1' ? 'Growth' : 'Starter';
      if (!pr.design) pr.design = { layout: p.id === 's1' ? 'gallery' : 'classic', accent: p.id === 's1' ? 'gold' : 'ink', header: 'cover', buttons: 'pill' };
      if (pr.galleryCount == null) pr.galleryCount = p.id === 's1' ? 8 : 4; if (!pr.captions) pr.captions = {};
      if (!pr.posts) pr.posts = p.id === 's1' ? [{ id: 'po1', title: 'Knotless braids that last 8 weeks', body: 'Pre-stretched, itch-free hair and a hot-water seal on every install. Book the medium size for a mid-back look in about 5 hours.', sid: 's1a', status: 'Published' }, { id: 'po2', title: 'Back-to-school cornrows for kids', body: 'Gentle, neat rows in under 2 hours. Kids’ chair, cartoons and snacks included.', sid: 's1c', status: 'Published' }] : [];
      if (!pr.socials) pr.socials = p.id === 's1' ? [{ net: 'instagram', url: 'https://instagram.com/aminatasbraids', status: 'Approved' }, { net: 'tiktok', url: 'https://tiktok.com/@aminatasbraids', status: 'Approved' }, { net: 'facebook', url: 'https://facebook.com/aminatasbraids', status: 'Pending approval' }] : []; });
    sweep(); return mem;
  }
  function persist() { try { localStorage.setItem(KEY, JSON.stringify(mem)); } catch (e) { /* ignore */ } }
  function emit() { persist(); subs.forEach((f) => { try { f(); } catch (e) { /* ignore */ } }); }
  window.addEventListener('storage', (e) => { if (e.key === KEY) { mem = null; load(); subs.forEach((f) => { try { f(); } catch (er) { /* ignore */ } }); } });
  function sweep() {
    const now = Date.now(); let ch = false;
    mem.bookings.forEach((b) => {
      if (b.status === 'requested' && now - b.created > 4 * H) { b.status = 'expired'; b.ledger.push({ t: 'release', amt: b.deposit, at: now }); b.history.push({ at: now, by: 'system', a: 'Provider didn’t confirm within 4 hours · authorisation released' }); ch = true; }
      if (['confirmed'].includes(b.status) && now > b.end + 48 * H) { b.status = 'completed'; const bal = r2(b.price - b.deposit); if (bal > 0) b.ledger.push({ t: 'charge', amt: bal, at: now }); b.history.push({ at: now, by: 'system', a: 'Auto-completed 48 h after the appointment · balance charged' }); ch = true; }
    });
    if (ch) persist();
  }

  const fees = () => { let pct = 10, sp = 2.9, sf = 0.3; try { const f = JSON.parse(localStorage.getItem('at.fees.v1') || 'null'); if (f) { if (f.commission && f.commission.Services != null) pct = Number(f.commission.Services); if (f.stripePct != null) sp = Number(f.stripePct); if (f.stripeFixed != null) sf = Number(f.stripeFixed); } } catch (e) { /* ignore */ } return { pct, stripePct: sp, stripeFixed: sf }; };
  const provider = (pid) => load().providers.find((p) => p.id === pid);
  const service = (pid, sid) => { const p = provider(pid); return p && p.services.find((s) => s.id === sid); };

  function busy(pid, excludeId) {
    return load().bookings.filter((b) => b.pid === pid && b.id !== excludeId && ACTIVE.includes(b.status)).flatMap((b) => { const out = [[b.start, b.end]]; if (b.proposed) out.push([b.proposed, b.proposed + b.dur * MIN]); return out; });
  }
  function slots(pid, sid, iso, excludeId) {
    const p = provider(pid); const sv = service(pid, sid); if (!p || !sv) return [];
    const ds = dayStart(iso); const w = p.weekly[DOW[new Date(ds).getDay()]]; if (!w || !w.on || p.blackouts.includes(iso)) return [];
    const now = Date.now(); if (ds > now + p.horizon * DAY) return [];
    const taken = busy(pid, excludeId); const buf = p.buffer * MIN;
    const sameDay = taken.filter(([a]) => isoDay(a) === iso).length; if (sameDay >= p.maxPerDay) return [];
    const out = [];
    w.ranges.forEach(([a, b]) => { for (let m = hm(a); m + sv.dur <= hm(b); m += p.step) { const st = ds + m * MIN, en = st + sv.dur * MIN; if (st < now + p.lead * H) continue; if (taken.some(([x, y]) => st < y + buf && en + buf > x)) continue; out.push({ start: st, label: fmtTime(st) }); } });
    return out;
  }
  function days(pid, sid, n, excludeId) {
    const out = []; const t = new Date(); t.setHours(0, 0, 0, 0);
    for (let i = 0; i < (n || 14); i++) { const d = new Date(t); d.setDate(t.getDate() + i); const iso = isoDay(d.getTime()); const c = slots(pid, sid, iso, excludeId).length; out.push({ iso, dow: d.toLocaleDateString('en-US', { weekday: 'short' }), dnum: d.getDate(), mon: d.toLocaleDateString('en-US', { month: 'short' }), count: c }); }
    return out;
  }

  function refundShare(b, now) {
    if (b.status === 'requested') return 1;
    const hrs = (b.start - (now || Date.now())) / H; const pol = snapOf(b);
    for (const [h, share] of pol.rows) if (hrs >= h) return share;
    return 0;
  }
  function canReschedule(b, now) { if (!['requested', 'confirmed'].includes(b.status)) return false; const pol = snapOf(b); return (b.start - (now || Date.now())) / H >= pol.reschedHrs && (b.reschedules || 0) < pol.maxResched; }

  function settle(b) {
    const F = fees(); const sum = (t) => r2(b.ledger.filter((x) => x.t === t).reduce((a, x) => a + x.amt, 0));
    const captured = sum('capture'), charged = sum('charge'), refunded = sum('refund'), held = b.ledger.some((x) => x.t === 'auth') && !b.ledger.some((x) => ['capture', 'release'].includes(x.t)) ? b.deposit : 0;
    const gross = r2(captured + charged - refunded);
    const charges = b.ledger.filter((x) => x.t === 'capture' || x.t === 'charge');
    const stripe = r2(charges.reduce((a, x) => a + x.amt * F.stripePct / 100 + F.stripeFixed, 0));
    const commission = r2(gross * F.pct / 100); const penalty = sum('provider_fee');
    return { held, captured, charged, refunded, gross, stripe, commission, penalty, net: r2(gross - commission - stripe - penalty), platform: r2(commission + penalty), pct: F.pct, balanceDue: ACTIVE.includes(b.status) ? r2(b.price - b.deposit) : 0 };
  }

  function log(b, by, a) { b.history.push({ at: Date.now(), by, a }); }
  function find(id) { return load().bookings.find((b) => b.id === id); }

  function create(o) {
    const st = load(); const p = provider(o.pid); const sv = service(o.pid, o.sid);
    if (!p || !sv) return { ok: false, msg: 'This service is no longer available.' };
    if (!slots(o.pid, o.sid, isoDay(o.start)).some((x) => x.start === o.start)) return { ok: false, msg: 'That time was just taken. Pick another slot.' };
    const now = Date.now(); const id = 'BK-' + (st.seq++);
    const b = { id, pid: p.id, sid: sv.id, svcName: sv.name, dur: sv.dur, price: sv.price, deposit: sv.deposit, start: o.start, end: o.start + sv.dur * MIN, customer: o.customer, status: p.instant ? 'confirmed' : 'requested', created: now, policy: p.policy, policySnap: policyFor(p), reschedules: 0, notes: o.notes || '', pay: o.pay || 'Visa •• 4242', channel: o.channel || 'web', ledger: [], history: [] };
    if (sv.deposit > 0) b.ledger.push({ t: 'auth', amt: sv.deposit, at: now });
    log(b, 'customer', 'Requested via ' + (b.channel === 'app' ? 'app' : 'web') + (sv.deposit ? ' · ' + usd(sv.deposit) + ' deposit authorised on ' + b.pay : ''));
    if (p.instant) { if (sv.deposit) b.ledger.push({ t: 'capture', amt: sv.deposit, at: now }); log(b, 'system', 'Instant booking · confirmed automatically'); }
    st.bookings.unshift(b); emit(); return { ok: true, booking: b };
  }

  function act(id, action, arg, by) {
    const b = find(id); if (!b) return { ok: false, msg: 'Booking not found.' };
    const now = Date.now(); const p = provider(b.pid);
    const refund = (amt, why) => { if (amt <= 0) return; const cap = b.ledger.some((x) => x.t === 'capture'); b.ledger.push({ t: cap ? 'refund' : 'release', amt: r2(amt), at: now }); log(b, 'system', (cap ? 'Refunded ' : 'Released ') + usd(amt) + ' to ' + b.pay + (why ? ' · ' + why : '')); };
    const capture = () => { if (b.deposit && !b.ledger.some((x) => x.t === 'capture')) b.ledger.push({ t: 'capture', amt: b.deposit, at: now }); };
    switch (action) {
      case 'confirm': if (b.status !== 'requested') return { ok: false, msg: 'Only new requests can be confirmed.' }; capture(); b.status = 'confirmed'; log(b, 'provider', 'Confirmed · deposit captured'); break;
      case 'decline': if (b.status !== 'requested') return { ok: false, msg: 'Only new requests can be declined.' }; b.status = 'declined'; log(b, 'provider', 'Declined' + (arg ? ' · “' + arg + '”' : '')); refund(b.deposit, 'full refund'); break;
      case 'cancel': {
        if (!ACTIVE.includes(b.status)) return { ok: false, msg: 'This booking can’t be cancelled.' };
        if (by === 'provider') { const late = b.status !== 'requested' && b.start - now < 24 * H; b.status = 'cancelled_provider'; log(b, 'provider', 'Cancelled' + (arg ? ' · “' + arg + '”' : '')); refund(b.deposit, 'full refund'); if (late) { b.ledger.push({ t: 'provider_fee', amt: 15, at: now }); p.strikes = (p.strikes || 0) + 1; log(b, 'system', 'Late provider cancellation · $15.00 fee and 1 reliability strike'); } }
        else { const share = refundShare(b, now); const wasReq = b.status === 'requested'; b.status = 'cancelled_customer'; log(b, 'customer', 'Cancelled' + (arg ? ' · “' + arg + '”' : '')); if (!wasReq) capture(); refund(r2(b.deposit * share), share === 1 ? 'full refund' : share > 0 ? Math.round(share * 100) + '% per ' + snapOf(b).label + ' policy' : ''); if (share < 1 && b.deposit) log(b, 'system', usd(r2(b.deposit * (1 - share))) + ' kept per ' + snapOf(b).label + ' cancellation policy'); }
        break; }
      case 'reschedule': {
        if (by === 'customer') {
          if (!canReschedule(b, now)) return { ok: false, msg: 'Free changes close ' + hrsLabel(snapOf(b).reschedHrs) + ' before the appointment' + ((b.reschedules || 0) >= snapOf(b).maxResched ? ' (or you’ve used your reschedules)' : '') + '. Cancel instead, or message the provider.' };
          if (!slots(b.pid, b.sid, isoDay(arg), b.id).some((x) => x.start === arg)) return { ok: false, msg: 'That time isn’t free any more.' };
          if (p.autoReschedule || b.status === 'requested') { const old = b.start; b.start = arg; b.end = arg + b.dur * MIN; b.reschedules = (b.reschedules || 0) + 1; log(b, 'customer', 'Moved from ' + fmtWhen(old) + ' to ' + fmtWhen(arg)); }
          else { b.proposed = arg; b.prevStatus = b.status; b.status = 'reschedule_requested'; log(b, 'customer', 'Asked to move to ' + fmtWhen(arg)); }
        } else {
          if (!['requested', 'confirmed'].includes(b.status)) return { ok: false, msg: 'This booking can’t be moved.' };
          if (!slots(b.pid, b.sid, isoDay(arg), b.id).some((x) => x.start === arg)) return { ok: false, msg: 'That time isn’t inside your availability.' };
          capture(); b.proposed = arg; b.prevStatus = 'confirmed'; b.status = 'reschedule_proposed'; log(b, 'provider', 'Proposed a new time · ' + fmtWhen(arg));
        }
        break; }
      case 'accept': { if (!['reschedule_requested', 'reschedule_proposed'].includes(b.status)) return { ok: false, msg: 'Nothing to accept.' }; const old = b.start; b.start = b.proposed; b.end = b.start + b.dur * MIN; b.proposed = null; b.status = 'confirmed'; capture(); b.reschedules = (b.reschedules || 0) + 1; log(b, by, 'Accepted new time · moved from ' + fmtWhen(old) + ' to ' + fmtWhen(b.start)); break; }
      case 'reject': {
        if (b.status === 'reschedule_requested') { b.status = b.prevStatus || 'confirmed'; b.proposed = null; log(b, 'provider', 'Kept the original time'); }
        else if (b.status === 'reschedule_proposed') { b.status = 'cancelled_provider'; b.proposed = null; log(b, 'customer', 'Declined the new time'); refund(b.deposit, 'full refund — provider couldn’t keep the original time'); }
        else return { ok: false, msg: 'Nothing to decline.' };
        break; }
      case 'complete': { if (b.status !== 'confirmed') return { ok: false, msg: 'Only confirmed bookings can be completed.' }; capture(); const bal = r2(b.price - b.deposit); b.status = 'completed'; if (bal > 0) b.ledger.push({ t: 'charge', amt: bal, at: now }); log(b, 'provider', 'Marked complete' + (bal > 0 ? ' · balance ' + usd(bal) + ' charged to ' + b.pay : '')); break; }
      case 'noshow': { if (b.status !== 'confirmed' || now < b.start) return { ok: false, msg: 'You can mark a no-show once the start time has passed.' }; capture(); b.status = 'no_show'; if (snapOf(b).noShow === 'full') { const bal = r2(b.price - b.deposit); if (bal > 0) b.ledger.push({ t: 'charge', amt: bal, at: now }); log(b, 'provider', 'Marked no-show · full price charged per policy'); } else log(b, 'provider', 'Marked no-show · deposit kept, no balance charged'); break; }
      default: return { ok: false, msg: 'Unknown action.' };
    }
    emit(); return { ok: true, booking: b };
  }

  function updateProvider(pid, patch) { const p = provider(pid); if (!p) return; Object.assign(p, typeof patch === 'function' ? patch(p) : patch); emit(); }
  function statusInfo(b) { const [label, tone] = STATUS[b.status] || [b.status, 'muted']; return { label, tone }; }
  function forCustomer(email) { return load().bookings.filter((b) => b.customer && b.customer.email === email).sort((a, b) => (ACTIVE.includes(b.status) - ACTIVE.includes(a.status)) || a.start - b.start); }
  function forProvider(pid) { return load().bookings.filter((b) => b.pid === pid); }
  function reset() { mem = seed(); emit(); }
  function subscribe(f) { subs.add(f); return () => subs.delete(f); }

  const ACCENTS = { gold: ['var(--at-gold-500)', 'var(--at-ink-1000)', 'var(--at-gold-50)'], green: ['var(--at-green-700)', 'var(--at-ink-0)', 'var(--at-green-50)'], ink: ['var(--at-ink-1000)', 'var(--at-ink-0)', 'var(--at-ink-50)'], clay: ['var(--at-color-danger)', 'var(--at-ink-0)', 'var(--at-color-danger-bg)'] };
  const SOCIALS = { instagram: ['Instagram', 'ph ph-instagram-logo'], tiktok: ['TikTok', 'ph ph-tiktok-logo'], facebook: ['Facebook', 'ph ph-facebook-logo'], youtube: ['YouTube', 'ph ph-youtube-logo'], whatsapp: ['WhatsApp catalogue', 'ph ph-whatsapp-logo'], website: ['Website', 'ph ph-globe'] };
  const PLAN_PHOTOS = { Starter: 6, Growth: 12, Pro: 24 };
  window.ATBook = { ACCENTS, SOCIALS, PLAN_PHOTOS, KEY, POLICIES, DEF_CUSTOM, policyFor, snapOf, hrsLabel, STATUS, ACTIVE, DOW_LABEL, load, providers: () => load().providers, provider, service, slots, days, create, act, settle, refundShare, canReschedule, updateProvider, statusInfo, forCustomer, forProvider, subscribe, reset, usd, fmtTime, fmtDay, fmtWhen, isoDay, fees };
})();
