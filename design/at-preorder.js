/* AfroTransact pre-order store — shared by the Shop, Back office (Pre-orders module) and the Pre-Order spec.
   Location model is global: country → state/province/region → city. A pre-order targets one region and either
   the whole region or a list of its cities. Coverage switches (scopeOff) can turn off a country, a region or a city.
   One localStorage record: { windows, creatives, extra (orders placed outside the seed), settings }. */
(function () {
  const LS = 'at.preorder.v2', DAY = 86400000;
  const R = (name, cities) => ({ name, cities: cities.split('|') });
  const GEO = {
    US: { name: 'United States', regionLabel: 'State', currency: 'USD', regions: {
      AL: R('Alabama', 'Birmingham|Montgomery|Huntsville|Mobile|Tuscaloosa'), AK: R('Alaska', 'Anchorage|Fairbanks|Juneau'), AZ: R('Arizona', 'Phoenix|Tucson|Mesa|Chandler|Scottsdale|Flagstaff'),
      AR: R('Arkansas', 'Little Rock|Fayetteville|Fort Smith|Jonesboro'), CA: R('California', 'Los Angeles|San Diego|San Jose|San Francisco|Oakland|Sacramento|Fresno|Daly City'),
      CO: R('Colorado', 'Denver|Colorado Springs|Aurora|Fort Collins|Boulder'), CT: R('Connecticut', 'Hartford|New Haven|Bridgeport|Stamford'), DE: R('Delaware', 'Wilmington|Dover|Newark'),
      DC: R('District of Columbia', 'Washington'), FL: R('Florida', 'Miami|Orlando|Tampa|Jacksonville|Tallahassee|Fort Lauderdale'), GA: R('Georgia', 'Atlanta|Savannah|Augusta|Columbus|Macon|Athens'),
      HI: R('Hawaii', 'Honolulu|Hilo'), ID: R('Idaho', 'Boise|Idaho Falls|Pocatello'), IL: R('Illinois', 'Chicago|Aurora|Naperville|Rockford|Springfield|Peoria'),
      IN: R('Indiana', 'Indianapolis|Fort Wayne|Evansville|South Bend'), IA: R('Iowa', 'Des Moines|Cedar Rapids|Davenport|Iowa City'), KS: R('Kansas', 'Wichita|Overland Park|Kansas City|Topeka'),
      KY: R('Kentucky', 'Louisville|Lexington|Bowling Green'), LA: R('Louisiana', 'New Orleans|Baton Rouge|Shreveport|Lafayette'), ME: R('Maine', 'Portland|Lewiston|Bangor'),
      MD: R('Maryland', 'Baltimore|Silver Spring|Columbia|Germantown|Bowie'), MA: R('Massachusetts', 'Boston|Worcester|Springfield|Lowell|Cambridge'), MI: R('Michigan', 'Detroit|Grand Rapids|Lansing|Ann Arbor|Flint'),
      MN: R('Minnesota', 'Minneapolis|Saint Paul|Rochester|Duluth|Brooklyn Park'), MS: R('Mississippi', 'Jackson|Gulfport|Hattiesburg'), MO: R('Missouri', 'Kansas City|St. Louis|Springfield|Columbia'),
      MT: R('Montana', 'Billings|Missoula|Bozeman'), NE: R('Nebraska', 'Omaha|Lincoln'), NV: R('Nevada', 'Las Vegas|Henderson|Reno'), NH: R('New Hampshire', 'Manchester|Nashua|Concord'),
      NJ: R('New Jersey', 'Newark|Jersey City|Paterson|Edison|Trenton|Elizabeth'), NM: R('New Mexico', 'Albuquerque|Las Cruces|Santa Fe'), NY: R('New York', 'New York City|Bronx|Brooklyn|Buffalo|Rochester|Albany|Syracuse'),
      NC: R('North Carolina', 'Charlotte|Raleigh|Greensboro|Durham|Fayetteville'), ND: R('North Dakota', 'Fargo|Bismarck|Grand Forks'), OH: R('Ohio', 'Columbus|Cleveland|Cincinnati|Toledo|Akron|Dayton'),
      OK: R('Oklahoma', 'Oklahoma City|Tulsa|Norman'), OR: R('Oregon', 'Portland|Salem|Eugene'), PA: R('Pennsylvania', 'Philadelphia|Pittsburgh|Allentown|Harrisburg'), RI: R('Rhode Island', 'Providence|Warwick'),
      SC: R('South Carolina', 'Columbia|Charleston|Greenville'), SD: R('South Dakota', 'Sioux Falls|Rapid City'), TN: R('Tennessee', 'Nashville|Memphis|Knoxville|Chattanooga'),
      TX: R('Texas', 'Houston|Dallas|Austin|San Antonio|Fort Worth|El Paso|Lubbock|Amarillo|Midland|Odessa|Laredo|Corpus Christi|Waco|Tyler'),
      UT: R('Utah', 'Salt Lake City|Provo|Ogden'), VT: R('Vermont', 'Burlington|Montpelier'), VA: R('Virginia', 'Virginia Beach|Richmond|Arlington|Alexandria|Norfolk'),
      WA: R('Washington', 'Seattle|Spokane|Tacoma|Kent'), WV: R('West Virginia', 'Charleston|Huntington|Morgantown'), WI: R('Wisconsin', 'Milwaukee|Madison|Green Bay'), WY: R('Wyoming', 'Cheyenne|Casper') } },
    CA: { name: 'Canada', regionLabel: 'Province', currency: 'CAD', regions: {
      ON: R('Ontario', 'Toronto|Ottawa|Mississauga|Brampton|Hamilton'), QC: R('Quebec', 'Montreal|Quebec City|Gatineau'), BC: R('British Columbia', 'Vancouver|Surrey|Victoria'),
      AB: R('Alberta', 'Calgary|Edmonton'), MB: R('Manitoba', 'Winnipeg'), SK: R('Saskatchewan', 'Saskatoon|Regina'), NS: R('Nova Scotia', 'Halifax'), NB: R('New Brunswick', 'Moncton|Fredericton'),
      NL: R('Newfoundland and Labrador', "St. John's"), PE: R('Prince Edward Island', 'Charlottetown'), NT: R('Northwest Territories', 'Yellowknife'), YT: R('Yukon', 'Whitehorse'), NU: R('Nunavut', 'Iqaluit') } },
    GB: { name: 'United Kingdom', regionLabel: 'Nation', currency: 'GBP', regions: {
      ENG: R('England', 'London|Manchester|Birmingham|Leeds|Liverpool'), SCT: R('Scotland', 'Glasgow|Edinburgh|Aberdeen'), WLS: R('Wales', 'Cardiff|Swansea'), NIR: R('Northern Ireland', 'Belfast') } },
    NG: { name: 'Nigeria', regionLabel: 'State', currency: 'NGN', regions: {
      LA: R('Lagos', 'Lagos|Ikeja|Lekki'), FC: R('Federal Capital Territory', 'Abuja'), RI: R('Rivers', 'Port Harcourt'), OY: R('Oyo', 'Ibadan'), KN: R('Kano', 'Kano'), EN: R('Enugu', 'Enugu'), AN: R('Anambra', 'Onitsha|Awka') } },
    GH: { name: 'Ghana', regionLabel: 'Region', currency: 'GHS', regions: {
      AA: R('Greater Accra', 'Accra|Tema'), AH: R('Ashanti', 'Kumasi'), WR: R('Western', 'Takoradi'), NR: R('Northern', 'Tamale') } },
    KE: { name: 'Kenya', regionLabel: 'County', currency: 'KES', regions: { NBO: R('Nairobi', 'Nairobi|Westlands|Karen'), MSA: R('Mombasa', 'Mombasa'), KSM: R('Kisumu', 'Kisumu'), NKR: R('Nakuru', 'Nakuru|Naivasha') } },
    ZA: { name: 'South Africa', regionLabel: 'Province', currency: 'ZAR', regions: { GP: R('Gauteng', 'Johannesburg|Pretoria|Soweto'), WC: R('Western Cape', 'Cape Town|Stellenbosch'), KZN: R('KwaZulu-Natal', 'Durban|Pietermaritzburg') } },
    JM: { name: 'Jamaica', regionLabel: 'Parish', currency: 'JMD', regions: { KIN: R('Kingston', 'Kingston'), AND: R('Saint Andrew', 'Half Way Tree|Constant Spring'), JAM: R('Saint James', 'Montego Bay') } },
    IE: { name: 'Ireland', regionLabel: 'County', currency: 'EUR', regions: { D: R('Dublin', 'Dublin|Swords|Tallaght'), CO: R('Cork', 'Cork'), G: R('Galway', 'Galway') } },
  };
  const L = (country, region, cities) => ({ country, region, cities });
  const SEED = [
    { id: 'lbk-7', targets: ['US/TX/Lubbock'], name: 'Lubbock Pre-order #7', opens: '2026-10-06T09:00', closes: '2026-10-22T23:59', delivers: '2026-10-25', ribbon: 'Lubbock pre-order is open', headline: 'Fresh egusi, garri and palm oil, delivered to Lubbock on Sunday, Oct 25.', cats: ['Pantry', 'Spices', 'Snacks', 'Oils'], door: true, pickup: true, showCount: true, active: true, seedN: 142 },
    { id: 'ama-4', targets: ['US/TX/Amarillo'], name: 'Amarillo Pre-order #4', opens: '2026-09-26T09:00', closes: '2026-10-09T23:59', delivers: '2026-10-12', ribbon: 'Amarillo pre-order', headline: 'Amarillo deliveries go out Monday, Oct 12.', cats: ['Pantry', 'Spices'], door: true, pickup: false, showCount: false, active: true, seedN: 96 },
    { id: 'elp-3', targets: ['US/TX/El Paso'], name: 'El Paso Pre-order #3', opens: '2026-10-15T09:00', closes: '2026-10-30T23:59', delivers: '2026-11-03', ribbon: 'El Paso pre-order opens Oct 15', headline: 'El Paso, your next pre-order opens Thursday, Oct 15.', cats: ['Pantry', 'Spices', 'Snacks'], door: true, pickup: true, showCount: true, active: true, seedN: 0 },
    { id: 'mid-2', targets: ['US/TX/Midland', 'US/TX/Odessa'], name: 'Midland–Odessa Pre-order #2', opens: '2026-10-27T09:00', closes: '2026-11-07T23:59', delivers: '2026-11-11', ribbon: 'Midland–Odessa pre-order', headline: 'Midland and Odessa, one pre-order for both cities.', cats: ['Pantry'], door: true, pickup: false, showCount: true, active: true, draft: true, seedN: 0 },
    { id: 'tor-1', targets: ['CA/ON/Toronto', 'CA/ON/Mississauga', 'CA/ON/Brampton'], name: 'Greater Toronto Pre-order #1', opens: '2026-11-02T09:00', closes: '2026-11-14T23:59', delivers: '2026-11-18', ribbon: 'Toronto pre-order', headline: 'First AfroTransact pre-order for the Greater Toronto Area.', cats: ['Pantry', 'Spices'], door: true, pickup: true, showCount: true, active: true, seedN: 0 },
    { id: 'lbk-6', targets: ['US/TX/Lubbock'], name: 'Lubbock Pre-order #6', opens: '2026-09-08T09:00', closes: '2026-09-24T23:59', delivers: '2026-09-27', ribbon: 'Lubbock pre-order', headline: 'Lubbock #6', cats: ['Pantry'], door: true, pickup: true, showCount: true, active: true, seedN: 178 },
  ];
  const LEGACY = { lubbock: ['Lubbock'], amarillo: ['Amarillo'], elpaso: ['El Paso'], midland: ['Midland', 'Odessa'], laredo: ['Laredo'] };
  const A = (key, on) => { const [country, region, city] = key.split('/'); return { key, country, region, city: city || null, on: on !== false }; };
  const DEF_AREAS = ['US/TX/Lubbock', 'US/TX/Amarillo', 'US/TX/El Paso', 'US/TX/Midland', 'US/TX/Odessa', 'CA/ON/Toronto', 'CA/ON/Mississauga', 'CA/ON/Brampton'].map((k) => A(k)).concat([A('US/TX/Laredo', false)]);
  const DEF = { enabled: true, fallback: 'ship', areas: DEF_AREAS, placements: { ribbon: true, hero: true, badge: true, pdp: true, cart: true, family: true } };
  const subs = new Set();
  const T = (s) => new Date(s.length === 10 ? s + 'T12:00' : s).getTime();
  const fD = (t) => new Date(t).toLocaleDateString('en-US', { weekday: 'short', month: 'short', day: 'numeric' });
  const fS = (t) => new Date(t).toLocaleDateString('en-US', { month: 'short', day: 'numeric' });
  const fDT = (t) => fD(t) + ', ' + new Date(t).toLocaleTimeString('en-US', { hour: 'numeric', minute: '2-digit' });
  const norm = (w) => { if (w.targets) return w; const l = w.loc || L('US', 'TX', LEGACY[w.city] || [String(w.city || '')]); return { ...w, targets: l.cities.length ? l.cities.map((c) => l.country + '/' + l.region + '/' + c) : [l.country + '/' + l.region] }; };
  function load() {
    let d = null; try { d = JSON.parse(localStorage.getItem(LS) || 'null'); } catch (e) { /* blocked */ }
    d = d || {}; const st = d.settings || {};
    return { windows: (d.windows || SEED).map(norm), creatives: d.creatives || {}, extra: d.extra || [], settings: { ...DEF, ...st, areas: Array.isArray(st.areas) ? st.areas : DEF_AREAS, placements: { ...DEF.placements, ...(st.placements || {}) } } };
  }
  function save(patch) {
    const next = { ...load(), ...patch };
    try { localStorage.setItem(LS, JSON.stringify(next)); } catch (e) { return false; }
    subs.forEach((f) => { try { f(); } catch (e) { /* listener */ } });
    return true;
  }
  window.addEventListener('storage', (e) => { if (e.key === LS) subs.forEach((f) => { try { f(); } catch (x) { /* listener */ } }); });
  function status(w, now) {
    if (w.draft) return 'draft'; if (!w.active) return 'paused';
    if (now < T(w.opens)) return 'upcoming'; if (now <= T(w.closes)) return 'open';
    if (now < T(w.delivers) + DAY) return 'closed'; return 'delivered';
  }
  const countryOf = (region) => { for (const [c, v] of Object.entries(GEO)) if (v.regions[region]) return c; return 'US'; };
  const regionName = (c, r) => { const s = typeof gc !== 'undefined' && (gc.states[c] || []).find((x) => x.code === r); if (s) return s.name; return ((GEO[c] || {}).regions || {})[r] ? GEO[c].regions[r].name : r; };
  const countryName = (c) => { const x = typeof gc !== 'undefined' && (gc.countries || []).find((y) => y.code === c); return (x && x.name) || (GEO[c] || {}).name || c; };
  const areaLabel = (key) => { const [c, r, city] = String(key).split('/'); return city ? city + ', ' + r + (c !== 'US' ? ' · ' + countryName(c) : '') : 'All of ' + regionName(c, r) + (c !== 'US' ? ' · ' + countryName(c) : ''); };
  const locName = (targets) => { const t = Array.isArray(targets) ? targets : []; if (!t.length) return 'No area chosen'; const names = t.map(areaLabel); return names.length <= 3 ? names.join(' · ') : names.slice(0, 2).join(' · ') + ' + ' + (names.length - 2) + ' more'; };
  const slug = (w) => String(((w.targets || [])[0] || 'PRE').split('/').pop()).replace(/[^A-Za-z]/g, '').slice(0, 3).toUpperCase();
  const covers = (w, country, region, city) => (w.targets || []).some((k) => { const [c, r, x] = k.split('/'); return c === country && r === region && (!x || x.toLowerCase() === String(city).toLowerCase()); });
  const areaOn = (S, c, r, x) => { const A = S.areas || []; const ex = x && A.find((a) => a.country === c && a.region === r && a.city && a.city.toLowerCase() === String(x).toLowerCase()); if (ex) return ex.on; const reg = A.find((a) => a.country === c && a.region === r && !a.city); return !!(reg && reg.on); };
  const scopeOn = areaOn;
  function search(q, n) {
    const t = String(q || '').trim().toLowerCase(); if (t.length < 2) return [];
    const out = [];
    Object.entries(GEO).forEach(([c, C]) => {
      if (C.name.toLowerCase().includes(t)) out.push({ key: c, label: C.name, sub: 'Country', type: 'country', rank: C.name.toLowerCase().startsWith(t) ? 0 : 2 });
      Object.entries(C.regions).forEach(([r, R0]) => {
        if (R0.name.toLowerCase().includes(t) || r.toLowerCase() === t) out.push({ key: c + '/' + r, label: 'All of ' + R0.name, sub: C.regionLabel + ' · ' + C.name, type: 'region', rank: R0.name.toLowerCase().startsWith(t) || r.toLowerCase() === t ? 0 : 2 });
        R0.cities.forEach((x) => { if (x.toLowerCase().includes(t)) out.push({ key: c + '/' + r + '/' + x, label: x + ', ' + r, sub: R0.name + ' · ' + C.name, type: 'city', rank: x.toLowerCase().startsWith(t) ? 1 : 3 }); });
      });
    });
    return out.sort((a, b) => a.rank - b.rank || a.label.localeCompare(b.label)).slice(0, n || 8);
  }
  function parse(label) { const [city, region] = String(label || '').split(',').map((x) => x.trim()); return { city, region, country: countryOf(region) }; }
  function resolve(label, now) {
    now = now || Date.now();
    const { city, region, country } = parse(label); if (!city || !region) return null;
    const st = load(), S = st.settings;
    const cw = st.windows.filter((w) => !w.draft && covers(w, country, region, city));
    if (!cw.length) return null;
    let win = null, stage = null;
    for (const want of ['open', 'upcoming', 'closed']) { const f = cw.find((w) => status(w, now) === want); if (f) { win = f; stage = want; break; } }
    const on = !!win && S.enabled && areaOn(S, country, region, city);
    const out = { key: country + '/' + region + '/' + city, cityName: city, region, country, on, win, stage, settings: S, fallback: S.fallback };
    if (!win) return out;
    const target = stage === 'open' ? T(win.closes) : stage === 'upcoming' ? T(win.opens) : T(win.delivers);
    const diff = Math.max(0, target - now);
    const cd = { d: Math.floor(diff / DAY), h: Math.floor(diff % DAY / 3600000), m: Math.floor(diff % 3600000 / 60000), s: Math.floor(diff % 60000 / 1000) };
    const p2 = (n) => String(n).padStart(2, '0');
    const cre = st.creatives[win.id] || {};
    const count = Math.round(win.seedN * 0.96) + st.extra.filter((o) => o.win === win.id && o.status !== 'Cancelled').length;
    return { ...out, creatives: cre, count, cd, cdShort: `${cd.d}d ${p2(cd.h)}h ${p2(cd.m)}m ${p2(cd.s)}s`, cdParts: [[String(cd.d), 'days'], [p2(cd.h), 'hrs'], [p2(cd.m), 'min'], [p2(cd.s), 'sec']],
      opensT: fDT(T(win.opens)), closesT: fDT(T(win.closes)), deliversT: fD(T(win.delivers)), opensS: fS(T(win.opens)), closesS: fS(T(win.closes)), deliversS: fS(T(win.delivers)), deliversAt: T(win.delivers) };
  }
  /* ---- Full world place data: @countrystatecity/countries-browser (250 countries, 5,000+ states, 150,000+ cities).
     Loaded lazily from the CDN, one country / state at a time; falls back to the built-in GEO list when offline. ---- */
  const LIB = 'https://esm.sh/@countrystatecity/countries-browser';
  let libP = null; const gc = { countries: null, states: {}, cities: {}, err: false }; const geoSubs = new Set();
  const lib = () => libP || (libP = import(LIB).catch(() => { gc.err = true; return null; }));
  const ping = () => geoSubs.forEach((f) => { try { f(); } catch (e) { /* listener */ } });
  const LABEL = { US: 'State', CA: 'Province', GB: 'Nation', IE: 'County', KE: 'County', JM: 'Parish', ZA: 'Province', NG: 'State', GH: 'Region', AU: 'State', IN: 'State', MX: 'State', FR: 'Region', DE: 'State', IT: 'Region', ES: 'Province', BR: 'State' };
  const fbCountries = () => Object.entries(GEO).map(([code, c]) => ({ code, name: c.name, currency: c.currency, emoji: '' }));
  const fbStates = (cc) => Object.entries((GEO[cc] || { regions: {} }).regions).map(([code, r]) => ({ code, name: r.name }));
  const fbCities = (cc, sc) => (((GEO[cc] || {}).regions || {})[sc] || { cities: [] }).cities.slice();
  const byName = (a, b) => a.name.localeCompare(b.name);
  const loading = {};
  function loadCountries() { if (gc.countries) return Promise.resolve(gc.countries); if (loading.c) return loading.c; return (loading.c = lib().then((L) => (L ? L.getCountries() : null)).then((list) => { gc.countries = list && list.length ? list.map((c) => ({ code: c.iso2, name: c.name, currency: c.currency, emoji: c.emoji || '' })).sort(byName) : fbCountries().sort(byName); ping(); return gc.countries; }).catch(() => { gc.countries = fbCountries().sort(byName); ping(); return gc.countries; })); }
  function loadStates(cc) { if (gc.states[cc]) return Promise.resolve(gc.states[cc]); const k = 's' + cc; if (loading[k]) return loading[k]; return (loading[k] = lib().then((L) => (L ? L.getStatesOfCountry(cc) : null)).then((list) => { gc.states[cc] = list && list.length ? list.map((s) => ({ code: s.iso2 || s.state_code || String(s.id), name: s.name })).sort(byName) : fbStates(cc).sort(byName); ping(); return gc.states[cc]; }).catch(() => { gc.states[cc] = fbStates(cc).sort(byName); ping(); return gc.states[cc]; })); }
  function loadCities(cc, sc) { const k = cc + '/' + sc; if (gc.cities[k]) return Promise.resolve(gc.cities[k]); if (loading['c' + k]) return loading['c' + k]; return (loading['c' + k] = lib().then((L) => (L ? L.getCitiesOfState(cc, sc) : null)).then((list) => { const names = list && list.length ? [...new Set(list.map((c) => c.name))] : fbCities(cc, sc); gc.cities[k] = names.sort((a, b) => a.localeCompare(b)); ping(); return gc.cities[k]; }).catch(() => { gc.cities[k] = fbCities(cc, sc).sort(); ping(); return gc.cities[k]; })); }
  // Sync getters for render code: return the list if loaded, otherwise null and start loading.
  const geo = {
    countries: () => gc.countries || (loadCountries(), null),
    states: (cc) => (cc ? gc.states[cc] || (loadStates(cc), null) : []),
    cities: (cc, sc) => (cc && sc ? gc.cities[cc + '/' + sc] || (loadCities(cc, sc), null) : []),
    loadCountries, loadStates, loadCities,
    label: (cc) => LABEL[cc] || (GEO[cc] || {}).regionLabel || 'State / region',
    countryName: (cc) => ((gc.countries || []).find((c) => c.code === cc) || {}).name || (GEO[cc] || {}).name || cc,
    stateName: (cc, sc) => ((gc.states[cc] || []).find((s) => s.code === sc) || {}).name || regionName(cc, sc),
    offline: () => gc.err, subscribe: (f) => { geoSubs.add(f); return () => geoSubs.delete(f); },
  };
  /* ---- Channel attribution: every advertorial link carries ?src= (ig, fb, tt, wa, email, sms, app) plus optional cr= (creative) and po= (pre-order id).
     On-site placements call attr.set('hero' | 'ribbon' | 'pdp' | 'page'). Last touch wins; kept for the visit, and for 30 days when the shopper has accepted cookies. ---- */
  const AKEY = 'at.attr.v1';
  const SRC = { ig: 'Instagram', fb: 'Facebook', tt: 'TikTok', wa: 'WhatsApp', email: 'Email', sms: 'SMS', app: 'Mobile app', hero: 'Site · hero', ribbon: 'Site · ribbon', pdp: 'Site · product page', page: 'Site · pre-order page', agent: 'Agent (late)' };
  const consent = () => { try { const c = JSON.parse(localStorage.getItem('at.consent.v1') || 'null'); return !!(c && (c.marketing || c.analytics || c.all || c.accepted)); } catch (e) { return false; } };
  const attr = {
    SRC,
    capture() { try { const q = new URLSearchParams(window.location.search); const src = q.get('src'); if (src) attr.set(src, { cr: q.get('cr') || '', po: q.get('po') || '' }); } catch (e) { /* no-op */ } },
    set(src, extra) { const rec = { src, ...(extra || {}), at: Date.now() }; try { sessionStorage.setItem(AKEY, JSON.stringify(rec)); if (consent()) localStorage.setItem(AKEY, JSON.stringify(rec)); } catch (e) { /* blocked */ } },
    get() { let r = null; try { r = JSON.parse(sessionStorage.getItem(AKEY) || 'null') || JSON.parse(localStorage.getItem(AKEY) || 'null'); } catch (e) { /* blocked */ } if (r && Date.now() - r.at > 30 * DAY) r = null; return r ? { source: SRC[r.src] || r.src, tag: 'src=' + r.src + (r.cr ? '&cr=' + r.cr : '') + (r.po ? '&po=' + r.po : '') } : { source: 'Site · direct', tag: '' }; },
  };
  function openList(now) {
    now = now || Date.now(); const st = load(), S = st.settings; if (!S.enabled) return [];
    const zipOf = { Lubbock: '79401', Amarillo: '79101', 'El Paso': '79901', Midland: '79701', Odessa: '79761', Laredo: '78040' };
    return st.windows.filter((w) => !w.draft && w.active && ['open', 'upcoming'].includes(status(w, now)))
      .filter((w) => (w.targets || []).some((k) => { const [c, r, x] = k.split('/'); return x ? areaOn(S, c, r, x) : (S.areas || []).some((a) => a.key === k && a.on); }))
      .map((w) => { const s2 = status(w, now); const [c, r, x] = (w.targets[0] || '').split('/'); const city = x || ((((GEO[c] || {}).regions || {})[r] || {}).cities || [])[0] || r; const cre = st.creatives[w.id] || {};
        return { id: w.id, name: w.name, where: locName(w.targets), stage: s2, open: s2 === 'open', city, region: r, country: c, zip: zipOf[city] || '', heroSrc: cre.hero ? cre.hero.src : '',
          label: s2 === 'open' ? 'Order by ' + fS(T(w.closes)) + ' · delivered ' + fS(T(w.delivers)) : 'Opens ' + fS(T(w.opens)) + ' · delivered ' + fS(T(w.delivers)) }; })
      .sort((a, b) => (a.open ? 0 : 1) - (b.open ? 0 : 1) || a.name.localeCompare(b.name));
  }
  function addOrder(o) { const st = load(); return save({ extra: [...st.extra, o] }); }
  window.ATPre = { geo, attr, openList, LS, GEO, SEED, DEF, load, save, status, resolve, parse, covers, scopeOn, areaOn, search, areaLabel, countryName, countryOf, regionName, locName, slug, addOrder, subscribe: (f) => { subs.add(f); return () => subs.delete(f); }, T, fD, fS, fDT, PHONE: '+1 (833) 555-0199' };
})();
