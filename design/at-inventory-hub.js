/* AfroTransact inventory hub — warehouses, suppliers, POs, international inbound shipments,
   lots with expiry, landed cost, GL postings, barcodes. Demo data persisted in localStorage. */
(function () {
  if (window.ATInvHub) return;
  const KEY = 'at.invhub.v2';
  const TODAY = '2026-10-09';
  const STAGES = ['PO confirmed', 'Cargo ready', 'Picked up at origin', 'Export cleared', 'Departed origin', 'Arrived US port', 'US customs cleared', 'Delivered to FC', 'Received & put away'];
  const DEPART = 4, RECEIVED = 8;
  const ck = (body) => { const d = body.split('').reverse().map(Number); const s = d.reduce((a, n, i) => a + n * (i % 2 === 0 ? 3 : 1), 0); return String((10 - (s % 10)) % 10); };
  const upc = (item) => { const b = '86001' + item; return b + ck(b); };
  const PIDS = { 'AT-JOL-6': 'p2', 'AT-BAS-10': 'p11', 'AT-BSP-8': 'p16', 'AT-SHI-12': 'p3' };
  const PRODUCTS = [
    ['AT-JOL-6', 'AfroTransact Brand Jollof Seasoning, 6 oz', '200061', 'SUP-ACC', 'GH', '0910.91.0000', 0.032, 18, 'Ambient', 150, 7.99],
    ['AT-BAS-10', 'AfroTransact Brand Aged Basmati Rice, 10 lb', '200102', 'SUP-PUN', 'IN', '1006.30.1000', 0.0, 24, 'Ambient', 120, 21.99],
    ['AT-BSP-8', 'AfroTransact Brand African Black Soap, 8 oz', '200083', 'SUP-KSW', 'GH', '3401.11.5000', 0.0, 36, 'Ambient', 150, 9.49],
    ['AT-SHI-12', 'Shito Black Pepper Sauce, Hot, 12 oz', '200124', 'SUP-ACC', 'GH', '2103.90.9091', 0.064, 12, 'Ambient', 60, 8.99],
    ['AT-PAL-1L', 'AfroTransact Brand Red Palm Oil, 1 L', '200015', 'SUP-ACC', 'GH', '1511.10.0000', 0.0, 18, 'Ambient', 200, 11.49],
    ['AT-GAR-5', 'Ijebu Gari, 5 lb', '200056', 'SUP-LPF', 'NG', '1106.20.9000', 0.064, 12, 'Ambient', 120, 12.99],
    ['AT-FON-2', 'Organic Fonio, 2 lb', '200027', 'SUP-DKG', 'SN', '1008.90.0100', 0.0, 12, 'Ambient', 60, 10.99]
  ].map(([sku, name, item, supplier, origin, hts, duty, shelf, storage, reorder, price]) => ({ sku, name, item, upc: upc(item), gtin14: '00' + upc(item), supplier, origin, hts, duty, shelf, storage, reorder, price, category: /Soap/.test(name) ? 'Beauty' : 'Groceries', pid: PIDS[sku] || null, listing: { status: PIDS[sku] ? 'Live' : sku === 'AT-FON-2' ? 'Draft' : 'Live', content: { title: true, desc: true, images: !!PIDS[sku] || sku !== 'AT-FON-2', allergens: true, nutrition: sku !== 'AT-FON-2' } } }));
  const minLife = (p) => Math.max(90, Math.round(p.shelf * 30.4 * 0.5));
  const SUPPLIERS = [
    ['SUP-ACC', 'Accra Pack Foods Ltd', 'GH', 'Accra, Ghana', 'GHS', 'Net 30 · 30% deposit', 35, 96, 0.4, ['FDA FFR 12874410221', 'FSSC 22000', 'Ghana FDA'], 'Approved'],
    ['SUP-KSW', 'Kumasi Soap Works', 'GH', 'Kumasi, Ghana', 'GHS', 'Net 45', 42, 91, 0.9, ['GSA certified', 'FDA cosmetics listing'], 'Approved'],
    ['SUP-GSE', 'Ghana Shea Exporters', 'GH', 'Tamale, Ghana', 'USD', 'LC at sight', 50, 88, 1.2, ['Fair Trade', 'USDA Organic'], 'Approved'],
    ['SUP-LPF', 'Lagos Pack Foods', 'NG', 'Ikeja, Lagos, Nigeria', 'NGN', '50% deposit · 50% on B/L', 45, 82, 2.1, ['NAFDAC', 'FDA FFR 19920031874'], 'On watch'],
    ['SUP-PUN', 'Punjab Rice Mills', 'IN', 'Karnal, Haryana, India', 'USD', 'Net 60', 55, 97, 0.2, ['APEDA', 'FDA FFR 11002233441', 'BRCGS'], 'Approved'],
    ['SUP-DKG', 'Dakar Grains SARL', 'SN', 'Dakar, Senegal', 'XOF', 'Net 30', 30, 93, 0.6, ['Ecocert Organic', 'FDA FFR 17700012345'], 'Onboarding']
  ].map(([id, name, country, city, currency, terms, lead, onTime, defect, certs, status]) => ({ id, name, country, city, currency, terms, lead, onTime, defect, certs, status }));
  const WAREHOUSES = [
    ['WH-TEM', 'Tema Consolidation Hub', 'Origin consolidation (3PL)', 'Tema Free Zone, Ghana', 'GH', ['Ambient', 'Bonded'], 400, 0.46, '18–28 °C', 'Kofi Asante · Meridian Logistics'],
    ['WH-LOS', 'Lagos Consolidation Hub', 'Origin consolidation (3PL)', 'Apapa, Lagos, Nigeria', 'NG', ['Ambient'], 250, 0.31, '20–30 °C', 'Chidi Okeke · Ports & Cargo'],
    ['Houston FC', 'Houston FC', 'US fulfilment centre', 'Houston, TX 77032', 'US', ['Ambient', 'Chilled', 'Hazmat'], 2400, 0.71, '15–24 °C', 'Daniel Mensah'],
    ['Edison FC', 'Edison FC', 'US fulfilment centre', 'Edison, NJ 08837', 'US', ['Ambient', 'Chilled'], 1800, 0.64, '15–24 °C', 'Rachel Kim'],
    ['Atlanta FC', 'Atlanta FC', 'US fulfilment centre', 'Atlanta, GA 30349', 'US', ['Ambient'], 1500, 0.58, '15–24 °C', 'Tunde Bakare'],
    ['Los Angeles FC', 'Los Angeles FC', 'US fulfilment centre', 'Ontario, CA 91761', 'US', ['Ambient', 'Chilled'], 1200, 0.22, '15–24 °C', 'Maria Lopez']
  ].map(([id, name, type, addr, country, zones, pallets, used, temp, manager]) => ({ id, name, type, addr, country, zones, pallets, used, temp, manager }));
  const L = (lot, sku, wh, bin, qty, mfg, exp, recv, shp, cost, status) => ({ lot, sku, wh, bin, qty, mfg, exp, recv, shp, cost, status: status || 'Sellable' });
  const LOTS = [
    L('L2604-JOL-01', 'AT-JOL-6', 'Houston FC', 'A-03-02', 420, '2026-04-02', '2027-10-02', '2026-05-20', 'SHP-2605-GH09', 1.92),
    L('L2601-JOL-02', 'AT-JOL-6', 'Edison FC', 'B-01-04', 260, '2026-01-10', '2027-07-10', '2026-03-02', 'SHP-2602-GH05', 1.88),
    L('L2505-JOL-07', 'AT-JOL-6', 'Atlanta FC', 'A-11-01', 180, '2025-05-01', '2026-11-01', '2025-07-14', 'SHP-2507-GH02', 1.81),
    L('L2603-BAS-01', 'AT-BAS-10', 'Houston FC', 'C-02-01', 120, '2026-03-01', '2028-03-01', '2026-04-28', 'SHP-2604-IN01', 9.64),
    L('L2601-BAS-02', 'AT-BAS-10', 'Edison FC', 'C-05-03', 300, '2026-01-15', '2028-01-15', '2026-03-10', 'SHP-2603-IN02', 9.51),
    L('L2601-BAS-03', 'AT-BAS-10', 'Atlanta FC', 'C-01-02', 90, '2026-01-15', '2028-01-15', '2026-03-12', 'SHP-2603-IN02', 9.51),
    L('L2512-BAS-04', 'AT-BAS-10', 'Los Angeles FC', 'C-01-01', 60, '2025-12-02', '2027-12-02', '2026-02-01', 'SHP-2601-IN04', 9.40),
    L('L2602-BSP-01', 'AT-BSP-8', 'Houston FC', 'D-04-02', 200, '2026-02-20', '2029-02-20', '2026-04-11', 'SHP-2604-GH03', 2.36),
    L('L2602-BSP-02', 'AT-BSP-8', 'Atlanta FC', 'D-02-05', 320, '2026-02-20', '2029-02-20', '2026-04-15', 'SHP-2604-GH03', 2.36),
    L('L2511-SHI-03', 'AT-SHI-12', 'Houston FC', 'A-07-03', 30, '2025-10-30', '2026-10-30', '2026-01-08', 'SHP-2601-GH01', 3.12),
    L('L2512-SHI-04', 'AT-SHI-12', 'Edison FC', 'A-02-06', 80, '2025-12-15', '2026-12-15', '2026-02-20', 'SHP-2602-GH05', 3.18),
    L('L2510-SHI-01', 'AT-SHI-12', 'Atlanta FC', 'A-09-01', 18, '2025-10-02', '2026-10-02', '2025-12-01', 'SHP-2511-GH08', 3.05, 'Expired'),
    L('L2512-SHI-05', 'AT-SHI-12', 'Los Angeles FC', 'Q-01-01', 12, '2025-12-15', '2026-12-15', '2026-02-24', 'SHP-2602-GH05', 3.18, 'Quarantine'),
    L('L2607-PAL-01', 'AT-PAL-1L', 'Houston FC', 'B-06-01', 640, '2026-07-01', '2028-01-01', '2026-08-30', 'SHP-2608-GH07', 4.21),
    L('L2506-PAL-02', 'AT-PAL-1L', 'Atlanta FC', 'B-03-02', 210, '2025-06-20', '2026-12-20', '2025-09-02', 'SHP-2508-GH04', 4.05),
    L('L2603-GAR-01', 'AT-GAR-5', 'Edison FC', 'C-08-02', 400, '2026-03-30', '2027-03-30', '2026-06-04', 'SHP-2606-NG01', 4.88),
    L('L2511-GAR-02', 'AT-GAR-5', 'Houston FC', 'C-09-04', 150, '2025-11-28', '2026-11-28', '2026-02-10', 'SHP-2602-NG03', 4.71),
    L('L2606-FON-01', 'AT-FON-2', 'Edison FC', 'C-10-01', 90, '2026-06-01', '2027-06-01', '2026-08-02', 'SHP-2607-SN01', 5.40)
  ];
  const S = (id, mode, carrier, ref, bl, incoterm, origin, port, dest, wh, supplierIds, po, etd, eta, stage, costs, lines, docs, note) => ({ id, mode, carrier, ref, bl, incoterm, origin, port, dest, wh, supplierIds, po, etd, eta, stage, costs, lines: lines.map(([sku, qty, fob, lot, mfg, exp]) => ({ sku, qty, fob, lot, mfg, exp })), docs, note: note || '' });
  const SHIPMENTS = [
    S('SHP-2610-GH01', 'Ocean · FCL 40′ HC', 'MSC', 'MSCU 482913 7', 'MEDUTM2610441', 'FOB Tema', 'Tema, Ghana', 'Port of Houston (Barbours Cut)', 'Houston, TX', 'Houston FC', ['SUP-ACC', 'SUP-KSW'], 'PO-1P-314', '2026-09-14', '2026-10-16', 5,
      { freight: 4850, insurance: 210, fees: 95, broker: 350, drayage: 780, receiving: 420 },
      [['AT-PAL-1L', 2400, 3.10, 'L2608-PAL-03', '2026-08-20', '2028-02-20'], ['AT-BSP-8', 3000, 1.65, 'L2608-BSP-03', '2026-08-11', '2029-08-11'], ['AT-SHI-12', 1800, 2.25, 'L2608-SHI-06', '2026-08-25', '2027-08-25'], ['AT-JOL-6', 2000, 1.30, 'L2608-JOL-03', '2026-08-18', '2028-02-18']],
      [['Commercial invoice', 'ok'], ['Packing list', 'ok'], ['Bill of lading', 'ok'], ['Certificate of origin', 'ok'], ['ISF 10+2 filed', 'ok'], ['FDA prior notice', 'ok'], ['CBP entry (7501)', 'pending']], 'Vessel MSC Aurora · 6 days from Houston'),
    S('SHP-2610-NG02', 'Ocean · LCL 14 CBM', 'Maersk', 'MSKU 771204 2 (shared)', 'MAEU254118833', 'CIF Newark', 'Apapa, Lagos, Nigeria', 'Port Newark–Elizabeth', 'Edison, NJ', 'Edison FC', ['SUP-LPF'], 'PO-1P-316', '2026-09-02', '2026-10-05', 5,
      { freight: 0, insurance: 0, fees: 72, broker: 325, drayage: 540, receiving: 260 },
      [['AT-GAR-5', 1500, 2.95, 'L2608-GAR-03', '2026-08-04', '2027-08-04']],
      [['Commercial invoice', 'ok'], ['Packing list', 'ok'], ['Bill of lading', 'ok'], ['Certificate of origin', 'ok'], ['ISF 10+2 filed', 'ok'], ['FDA prior notice', 'ok'], ['NAFDAC export permit', 'ok'], ['CBP entry (7501)', 'hold']], 'FDA hold — sample taken for exam (aflatoxin screen); est. release 3–5 days'),
    S('SHP-2610-GH04', 'Air · ACC → ATL', 'Delta Cargo', 'AWB 006-48812290', '006-48812290', 'FCA Accra', 'Kotoka Intl (ACC), Ghana', 'Hartsfield–Jackson (ATL)', 'Atlanta, GA', 'Atlanta FC', ['SUP-ACC'], 'PO-1P-318', '2026-10-06', '2026-10-08', 6,
      { freight: 2380, insurance: 60, fees: 40, broker: 180, drayage: 220, receiving: 140 },
      [['AT-JOL-6', 900, 1.30, 'L2609-JOL-04', '2026-09-22', '2028-03-22'], ['AT-SHI-12', 400, 2.25, 'L2609-SHI-07', '2026-09-24', '2027-09-24']],
      [['Commercial invoice', 'ok'], ['Packing list', 'ok'], ['Air waybill', 'ok'], ['Certificate of origin', 'ok'], ['FDA prior notice', 'ok'], ['CBP entry (7501)', 'ok']], 'Cleared · truck booked for 10 Oct 08:00'),
    S('SHP-2610-IN03', 'Ocean · FCL 20′', 'Hapag-Lloyd', 'HLXU 300218 4', 'HLCUMUN2609771', 'FOB Mundra', 'Mundra, India', 'Port of Houston (Bayport)', 'Houston, TX', 'Houston FC', ['SUP-PUN'], 'PO-1P-315', '2026-09-20', '2026-11-02', 4,
      { freight: 2950, insurance: 120, fees: 70, broker: 300, drayage: 690, receiving: 300 },
      [['AT-BAS-10', 1800, 8.20, 'L2609-BAS-05', '2026-09-01', '2028-09-01']],
      [['Commercial invoice', 'ok'], ['Packing list', 'ok'], ['Bill of lading', 'ok'], ['Phytosanitary certificate', 'ok'], ['ISF 10+2 filed', 'ok'], ['FDA prior notice', 'pending'], ['CBP entry (7501)', 'pending']], 'Transshipment at Colombo'),
    S('SHP-2610-SN05', 'Air · DSS → JFK', 'Air Senegal Cargo', 'AWB pending', '—', 'FCA Dakar', 'Blaise Diagne Intl (DSS), Senegal', 'JFK Intl, New York', 'Edison, NJ', 'Edison FC', ['SUP-DKG'], 'PO-1P-319', '2026-10-15', '2026-10-17', 1,
      { freight: 1450, insurance: 40, fees: 35, broker: 180, drayage: 310, receiving: 90 },
      [['AT-FON-2', 600, 3.60, 'L2610-FON-02', '2026-10-01', '2027-10-01']],
      [['Commercial invoice', 'pending'], ['Packing list', 'pending'], ['Air waybill', 'missing'], ['Organic certificate (NOP import)', 'ok'], ['FDA prior notice', 'missing']], 'Cargo ready 12 Oct at Dakar 3PL')
  ];
  const FX = { GHS: 15.42, NGN: 1528, XOF: 566, USD: 1 };
  const POS = [
    ['PO-1P-314', 'SUP-ACC', 'GHS', 15.42, '2026-08-01', 'SHP-2610-GH01', 'Shipped', 'Houston FC', 'FOB Tema', null],
    ['PO-1P-315', 'SUP-PUN', 'USD', 1, '2026-08-12', 'SHP-2610-IN03', 'Shipped', 'Houston FC', 'FOB Mundra', null],
    ['PO-1P-316', 'SUP-LPF', 'NGN', 1528, '2026-08-05', 'SHP-2610-NG02', 'Shipped', 'Edison FC', 'CIF Newark', null],
    ['PO-1P-318', 'SUP-ACC', 'GHS', 15.42, '2026-09-10', 'SHP-2610-GH04', 'Shipped', 'Atlanta FC', 'FCA Accra', null],
    ['PO-1P-319', 'SUP-DKG', 'XOF', 566, '2026-09-28', 'SHP-2610-SN05', 'Shipped', 'Edison FC', 'FCA Dakar', null],
    ['PO-1P-321', 'SUP-PUN', 'USD', 1, '2026-10-06', null, 'Approved', 'Houston FC', 'FOB Mundra', [['AT-BAS-10', 1200, 8.3]]],
    ['PO-1P-322', 'SUP-KSW', 'GHS', 15.42, '2026-10-08', null, 'Draft', 'Atlanta FC', 'FOB Tema', [['AT-BSP-8', 2400, 25.4]]],
    ['PO-1P-310', 'SUP-PUN', 'USD', 1, '2026-02-02', 'SHP-2603-IN02', 'Closed', 'Edison FC', 'FOB Mundra', [['AT-BAS-10', 1960, 8.22]]],
    ['PO-1P-311', 'SUP-ACC', 'GHS', 14.90, '2026-06-20', 'SHP-2608-GH07', 'Closed', 'Houston FC', 'FOB Tema', [['AT-PAL-1L', 2200, 44.8]]]
  ].map(([id, supplier, currency, fx, date, shp, status, wh, incoterm, lines]) => ({ id, supplier, currency, fx, date, shp, status, wh, incoterm, lines: lines ? lines.map(([sku, qty, unit]) => ({ sku, qty, unit })) : null, invoice: status === 'Closed' || !!shp, history: [{ at: date + ' 09:00', status: 'Draft', by: 'Ama O. (Buyer)' }].concat(status !== 'Draft' ? [{ at: date + ' 15:10', status: 'Approved', by: 'Kwesi D. (Finance)' }] : []) }));
  const ACCOUNTS = { 1300: 'Inventory — finished goods', 1310: 'Inventory in transit', 1320: 'Inventory — quarantine', 2110: 'GR/IR clearing (supplier invoices)', 2120: 'Accrued freight, duty & brokerage', 5000: 'Cost of goods sold', 5090: 'Inventory shrink & write-offs' };
  const seed = () => {
    const lots = LOTS.map((x) => ({ ...x }));
    const journal = [
      { id: 'JE-24117', date: '2026-09-14', ref: 'SHP-2610-GH01', memo: 'Title transferred at FOB Tema — goods in transit', lines: [[1310, 18420, 0], [2110, 0, 18420]] },
      { id: 'JE-24102', date: '2026-09-20', ref: 'SHP-2610-IN03', memo: 'Title transferred at FOB Mundra — goods in transit', lines: [[1310, 14760, 0], [2110, 0, 14760]] },
      { id: 'JE-24088', date: '2026-09-06', ref: 'SHP-2610-NG02', memo: 'CIF shipment departed — goods in transit', lines: [[1310, 4425, 0], [2110, 0, 4425]] },
      { id: 'JE-24061', date: '2026-09-02', ref: 'ORD-WEEK-35', memo: 'COGS for shipped 1P orders (FIFO lots)', lines: [[5000, 6212.4, 0], [1300, 0, 6212.4]] }
    ];
    const moves = [
      ['2026-10-08 16:40', 'PICK', 'AT-JOL-6', 'L2505-JOL-07', 'Atlanta FC A-11-01 → Pack', -24, 'ORD-118204', 'FEFO pick · Tunde B.'],
      ['2026-10-08 11:02', 'QUARANTINE', 'AT-SHI-12', 'L2512-SHI-05', 'LA A-03-04 → Q-01-01', 12, 'INC-3321', 'Broken seals · Maria L.'],
      ['2026-10-07 09:15', 'TRANSFER', 'AT-BAS-10', 'L2512-BAS-04', 'Houston FC → Los Angeles FC', 60, 'TO-0091', 'Rebalance · system'],
      ['2026-10-03 07:30', 'EXPIRED', 'AT-SHI-12', 'L2510-SHI-01', 'Atlanta FC A-09-01', 18, 'auto', 'Blocked from sale at expiry'],
      ['2026-09-30 14:22', 'CYCLE COUNT', 'AT-PAL-1L', 'L2607-PAL-01', 'Houston FC B-06-01', -2, 'CC-0930', 'Variance −2 · Daniel M.']
    ].map(([at, type, sku, lot, where, qty, ref, by]) => ({ at, type, sku, lot, where, qty, ref, by }));
    const shipments = SHIPMENTS.map((s) => ({ ...s, lines: s.lines.map((l) => ({ ...l })), docs: s.docs.map((d) => d.slice()), costs: { ...s.costs } }));
    const pos = POS.map((p) => { const s = shipments.find((x) => x.id === p.shp); return { ...p, lines: p.lines || (s ? s.lines.map((l) => ({ sku: l.sku, qty: l.qty, unit: Math.round(l.fob * p.fx * 100) / 100 })) : []), history: p.history.slice() }; });
    return { lots, products: PRODUCTS.map((p) => ({ ...p, listing: { ...p.listing, content: { ...p.listing.content } } })), pos, shipments, receipts: {}, sync: [], stages: Object.fromEntries(SHIPMENTS.map((s) => [s.id, s.stage])), events: {}, journal, moves, seq: 24118, poSeq: 323, shpSeq: 6 };
  };
  const listeners = new Set();
  let state;
  try { state = JSON.parse(localStorage.getItem(KEY)) || null; } catch (e) { state = null; }
  if (!state || !state.products) state = seed();
  const save = () => { try { localStorage.setItem(KEY, JSON.stringify(state)); } catch (e) {} listeners.forEach((f) => f()); };
  const now = () => TODAY + ' ' + new Date().toTimeString().slice(0, 5);
  const days = (d) => Math.round((new Date(d) - new Date(TODAY)) / 864e5);
  const product = (sku) => state.products.find((p) => p.sku === sku);
  const addDays = (d, n) => { const t = new Date(d); t.setDate(t.getDate() + n); return t.toISOString().slice(0, 10); };
  const addMonths = (d, n) => { const t = new Date(d); t.setMonth(t.getMonth() + n); return t.toISOString().slice(0, 10); };
  const pushSync = (event, detail) => { state.sync.unshift({ at: now(), event, detail }); if (state.sync.length > 60) state.sync.length = 60; };
  const landed = (s) => {
    const fob = s.lines.reduce((a, l) => a + l.qty * l.fob, 0);
    const other = s.costs.freight + s.costs.insurance + s.costs.fees + s.costs.broker + s.costs.drayage + s.costs.receiving;
    const lines = s.lines.map((l) => { const v = l.qty * l.fob; const share = fob ? v / fob : 0; const duty = v * (product(l.sku).duty || 0); const alloc = other * share; const total = v + duty + alloc; return { ...l, value: v, share, duty, alloc, total, unit: total / l.qty }; });
    const duty = lines.reduce((a, l) => a + l.duty, 0);
    return { fob, duty, other, total: fob + duty + other, lines };
  };
  const je = (date, ref, memo, lines) => { state.journal.unshift({ id: 'JE-' + state.seq++, date, ref, memo, lines }); };
  const api = {
    TODAY, STAGES, DEPART, RECEIVED, SUPPLIERS, WAREHOUSES, ACCOUNTS, FX, minLife, addMonths,
    get PRODUCTS() { return state.products; }, get SHIPMENTS() { return state.shipments; }, get POS() { return state.pos; },
    get: () => state, subscribe: (f) => { listeners.add(f); return () => listeners.delete(f); },
    days, product, landed,
    supplier: (id) => SUPPLIERS.find((s) => s.id === id),
    advance(id, actor) {
      const s = state.shipments.find((x) => x.id === id); const cur = state.stages[id]; if (cur >= RECEIVED - 1) return 'needs-receipt';
      const next = cur + 1; state.stages[id] = next; state.events[id] = { ...(state.events[id] || {}), [next]: now() };
      const lc = landed(s);
      if (next === DEPART) je(TODAY, id, 'Title transferred at ' + s.incoterm + ' — goods in transit', [[1310, lc.fob, 0], [2110, 0, lc.fob]]);
      if (next === RECEIVED) {
        lc.lines.forEach((l, i) => {
          const bin = 'R-' + String(i + 1).padStart(2, '0') + '-0' + (1 + (i % 4));
          state.lots.unshift({ lot: l.lot, sku: l.sku, wh: s.wh, bin, qty: l.qty, mfg: l.mfg, exp: l.exp, recv: TODAY, shp: id, cost: Math.round(l.unit * 100) / 100, status: 'Sellable' });
          state.moves.unshift({ at: now(), type: 'RECEIVE', sku: l.sku, lot: l.lot, where: s.wh + ' dock → ' + bin, qty: l.qty, ref: id, by: actor });
        });
        je(TODAY, id, 'Received at ' + s.wh + ' — capitalise landed cost', [[1300, lc.total, 0], [1310, 0, lc.fob], [2120, 0, lc.total - lc.fob]]);
      }
      save();
    },

    poTotal(po) { const local = po.lines.reduce((a, l) => a + l.qty * l.unit, 0); return { local, usd: local / po.fx, units: po.lines.reduce((a, l) => a + l.qty, 0) }; },
    poStatus(po) {
      if (po.status === 'Closed' || po.status === 'Cancelled' || !po.shp) return po.status;
      const s = state.shipments.find((x) => x.id === po.shp); const st = state.stages[po.shp]; if (!s) return po.status;
      if (st >= RECEIVED) return 'Received'; if (st === RECEIVED - 1) return state.receipts[po.shp] ? 'Receiving' : 'At FC'; if (s.docs.some((d) => d[1] === 'hold')) return 'Customs hold';
      if (st >= 6) return 'Customs cleared'; if (st >= DEPART) return 'In transit'; return 'Booked';
    },
    createPO(d, actor) {
      const sup = SUPPLIERS.find((x) => x.id === d.supplier); const id = 'PO-1P-' + state.poSeq++;
      state.pos.unshift({ id, supplier: d.supplier, currency: sup.currency, fx: FX[sup.currency], date: TODAY, shp: null, status: 'Draft', wh: d.wh, incoterm: d.incoterm, lines: d.lines.map((l) => ({ sku: l.sku, qty: +l.qty, unit: +l.unit })), invoice: false, history: [{ at: now(), status: 'Draft', by: actor }] });
      save(); return id;
    },
    poAction(id, action, actor) {
      const po = state.pos.find((p) => p.id === id); if (!po) return;
      const to = { submit: 'Pending approval', approve: 'Approved', reject: 'Draft', send: 'Sent to supplier', confirm: 'Confirmed', cancel: 'Cancelled', invoice: po.status, close: 'Closed' }[action];
      if (action === 'invoice') { po.invoice = true; po.history.unshift({ at: now(), status: 'Supplier invoice matched', by: actor }); }
      else { po.status = to; po.history.unshift({ at: now(), status: to, by: actor }); }
      if (action === 'send') pushSync('purchasing.po.sent', id + ' emailed to ' + SUPPLIERS.find((x) => x.id === po.supplier).name + ' (PDF + EDI 850)');
      if (action === 'close') pushSync('purchasing.po.closed', id + ' three-way matched — supplier payment released');
      save();
    },
    createShipment(poId, d, actor) {
      const po = state.pos.find((p) => p.id === poId); const id = 'SHP-2610-' + SUPPLIERS.find((x) => x.id === po.supplier).country + String(state.shpSeq++).padStart(2, '0');
      const air = d.mode.startsWith('Air');
      const lines = po.lines.map((l, i) => { const p = product(l.sku); const mfg = addDays(TODAY, -10); return { sku: l.sku, qty: l.qty, fob: Math.round((l.unit / po.fx) * 100) / 100, lot: 'L2610-' + l.sku.split('-')[1] + '-' + String(i + 11), mfg, exp: addMonths(mfg, p.shelf) }; });
      const docs = (air ? ['Commercial invoice', 'Packing list', 'Air waybill', 'Certificate of origin', 'FDA prior notice', 'CBP entry (7501)'] : ['Commercial invoice', 'Packing list', 'Bill of lading', 'Certificate of origin', 'ISF 10+2 filed', 'FDA prior notice', 'CBP entry (7501)']).map((n) => [n, 'missing']);
      const sup = SUPPLIERS.find((x) => x.id === po.supplier);
      state.shipments.unshift({ id, mode: d.mode, carrier: d.carrier, ref: d.ref || 'pending', bl: d.ref || '—', incoterm: po.incoterm, origin: sup.city, port: d.port, dest: po.wh, wh: po.wh, supplierIds: [po.supplier], po: poId, etd: d.etd, eta: d.eta, stage: 0, costs: { freight: +d.freight || 0, insurance: +d.insurance || 0, fees: air ? 40 : 90, broker: air ? 180 : 320, drayage: air ? 240 : 700, receiving: Math.round(lines.reduce((a, l) => a + l.qty, 0) * 0.12) }, lines, docs, note: 'ASN awaiting supplier batch details' });
      state.stages[id] = 0; state.events[id] = { 0: now() }; po.shp = id; po.status = 'Shipped'; po.history.unshift({ at: now(), status: 'Shipment ' + id + ' booked', by: actor });
      pushSync('logistics.shipment.booked', id + ' for ' + poId + ' · ' + d.mode + ' · ETA ' + d.eta); save(); return id;
    },
    updateAsnLine(id, i, patch) { const s = state.shipments.find((x) => x.id === id); Object.assign(s.lines[i], patch); if (patch.mfg && !patch.exp) s.lines[i].exp = addMonths(patch.mfg, product(s.lines[i].sku).shelf); save(); },
    setDoc(id, i, st) { const s = state.shipments.find((x) => x.id === id); s.docs[i][1] = st; if (st === 'ok' && !s.docs.some((d) => d[1] === 'hold') && /hold/i.test(s.note)) s.note = 'Hold released'; save(); },
    startReceipt(id, actor) {
      const s = state.shipments.find((x) => x.id === id); if (state.receipts[id]) return;
      state.receipts[id] = { id: 'GRN-' + id.slice(4), status: 'Open', by: actor, at: now(), dock: 'Dock ' + (2 + (id.length % 4)), lines: s.lines.map((l, i) => ({ key: 'k' + i, sku: l.sku, expected: l.qty, asnLot: l.lot, lot: '', mfg: '', exp: '', good: '', damaged: 0, bin: (product(l.sku).storage === 'Chilled' ? 'K-' : 'R-') + String(i + 1).padStart(2, '0') + '-0' + (1 + (i % 4)), qa: 'Pending', scanned: false, split: false })), seq: s.lines.length };
      state.moves.unshift({ at: now(), type: 'DOCK ARRIVAL', sku: s.lines.map((l) => l.sku).join(', '), lot: '—', where: s.wh + ' ' + state.receipts[id].dock, qty: s.lines.reduce((a, l) => a + l.qty, 0), ref: id, by: actor });
      save();
    },
    updateReceiptLine(id, key, patch) { const r = state.receipts[id]; const l = r.lines.find((x) => x.key === key); Object.assign(l, patch); if (patch.mfg && !l.exp) l.exp = addMonths(patch.mfg, product(l.sku).shelf); save(); },
    scanLine(id, key) { const r = state.receipts[id]; const l = r.lines.find((x) => x.key === key); const s = state.shipments.find((x) => x.id === id); const a = s.lines.find((x) => x.lot === l.asnLot) || s.lines.find((x) => x.sku === l.sku); Object.assign(l, { lot: a.lot, mfg: a.mfg, exp: a.exp, scanned: true }); save(); },
    splitLine(id, key) { const r = state.receipts[id]; const i = r.lines.findIndex((x) => x.key === key); const l = r.lines[i]; r.lines.splice(i + 1, 0, { ...l, key: 'k' + r.seq++, expected: 0, lot: '', mfg: '', exp: '', good: '', damaged: 0, bin: l.bin.replace(/\d$/, (d) => String((+d % 4) + 1)), qa: 'Pending', scanned: false, split: true }); save(); },
    removeLine(id, key) { const r = state.receipts[id]; r.lines = r.lines.filter((x) => x.key !== key || !x.split); save(); },
    validateReceipt(id) {
      const r = state.receipts[id]; if (!r) return null; const out = {}; let ok = true;
      r.lines.forEach((l) => {
        const p = product(l.sku); const e = []; const w = []; const good = Number(l.good); const dmg = Number(l.damaged) || 0;
        if (l.good === '' || isNaN(good) || good < 0) e.push('Enter the counted good quantity');
        if (good + dmg > 0) {
          if (!l.lot) e.push('Batch / lot number is required'); else if (!/^[A-Z0-9-]{4,20}$/i.test(l.lot)) e.push('Lot must be 4–20 letters, digits or dashes');
          if (!l.mfg) e.push('Manufacture date is required'); if (!l.exp) e.push('Expiry date is required');
          if (l.mfg && l.exp && l.exp <= l.mfg) e.push('Expiry must be after manufacture date');
          if (l.mfg && l.mfg > TODAY) e.push('Manufacture date is in the future');
          if (l.exp && days(l.exp) < 0) e.push('Already expired — reject at dock');
          else if (l.exp && days(l.exp) < minLife(p)) w.push('Short-dated: ' + days(l.exp) + ' days left, minimum ' + minLife(p) + ' — goes to quarantine');
          if (good > 0 && !/^[A-Z]-\d{2}-\d{2}$/.test(l.bin)) e.push('Put-away bin must look like R-01-02');
          if (l.qa === 'Pending' && good > 0) e.push('QA inspection result needed');
          if (l.qa === 'Fail') w.push('QA failed — whole batch quarantined');
          if (!l.scanned && l.lot) w.push('Keyed manually — case label not scanned');
          if (l.lot && l.asnLot && l.lot !== l.asnLot && !l.split) w.push('Lot differs from supplier ASN (' + l.asnLot + ')');
          if (dmg > 0) w.push(dmg + ' damaged → separate quarantine lot + carrier claim');
          if (state.lots.some((x) => x.lot === l.lot && x.sku === l.sku)) e.push('Lot ' + l.lot + ' already exists for this SKU');
        }
        out[l.key] = { e, w }; if (e.length) ok = false;
      });
      const bySku = {}; r.lines.forEach((l) => { bySku[l.sku] = bySku[l.sku] || { expected: 0, got: 0 }; bySku[l.sku].expected += l.expected; bySku[l.sku].got += (Number(l.good) || 0) + (Number(l.damaged) || 0); });
      const variance = Object.entries(bySku).map(([sku, v]) => ({ sku, ...v, diff: v.got - v.expected, over: v.got > v.expected * 1.02 }));
      variance.forEach((v) => { if (v.over) ok = false; });
      return { lines: out, variance, ok };
    },
    completeReceipt(id, actor) {
      const v = api.validateReceipt(id); if (!v || !v.ok) return { error: 'Fix the highlighted lines first' };
      const s = state.shipments.find((x) => x.id === id); const r = state.receipts[id]; const lc = landed(s);
      const unitBySku = {}; lc.lines.forEach((l) => { unitBySku[l.sku] = unitBySku[l.sku] || { total: 0, qty: 0 }; unitBySku[l.sku].total += l.total; });
      r.lines.forEach((l) => { const u = unitBySku[l.sku]; if (u) u.qty += (Number(l.good) || 0) + (Number(l.damaged) || 0); });
      let sellV = 0, qV = 0; const sellBySku = {};
      r.lines.forEach((l) => {
        const p = product(l.sku); const good = Number(l.good) || 0; const dmg = Number(l.damaged) || 0; const u = unitBySku[l.sku]; const cost = u && u.qty ? Math.round((u.total / u.qty) * 100) / 100 : 0;
        const short = days(l.exp) < minLife(p); const status = l.qa === 'Fail' || short ? 'Quarantine' : 'Sellable';
        if (good > 0) { state.lots.unshift({ lot: l.lot, sku: l.sku, wh: s.wh, bin: status === 'Quarantine' ? 'Q-01-0' + (1 + (good % 3)) : l.bin, qty: good, mfg: l.mfg, exp: l.exp, recv: TODAY, shp: id, cost, status, reason: l.qa === 'Fail' ? 'QA failed at receipt' : short ? 'Short-dated at receipt' : '' });
          state.moves.unshift({ at: now(), type: 'RECEIVE', sku: l.sku, lot: l.lot, where: s.wh + ' ' + r.dock, qty: good, ref: r.id, by: actor });
          state.moves.unshift({ at: now(), type: status === 'Sellable' ? 'PUT-AWAY' : 'QUARANTINE', sku: l.sku, lot: l.lot, where: r.dock + ' → ' + (status === 'Sellable' ? l.bin : 'Q-01'), qty: good, ref: r.id, by: actor });
          if (status === 'Sellable') { sellV += good * cost; sellBySku[l.sku] = (sellBySku[l.sku] || 0) + good; } else qV += good * cost; }
        if (dmg > 0) { state.lots.unshift({ lot: l.lot + '-DMG', sku: l.sku, wh: s.wh, bin: 'Q-02-01', qty: dmg, mfg: l.mfg, exp: l.exp, recv: TODAY, shp: id, cost, status: 'Quarantine', reason: 'Damaged in transit · claim ' + s.carrier });
          state.moves.unshift({ at: now(), type: 'QUARANTINE', sku: l.sku, lot: l.lot + '-DMG', where: r.dock + ' → Q-02-01', qty: dmg, ref: r.id, by: actor }); qV += dmg * cost; }
      });
      const total = Math.round((sellV + qV) * 100) / 100;
      je(TODAY, r.id, 'Goods received at ' + s.wh + ' — landed cost capitalised', [[1300, Math.round(sellV * 100) / 100, 0], [1320, Math.round(qV * 100) / 100, 0], [1310, 0, Math.round(Math.min(lc.fob, total) * 100) / 100], [2120, 0, Math.round(Math.max(0, total - lc.fob) * 100) / 100]].filter((x) => x[1] || x[2]));
      r.status = 'Completed'; r.done = now(); r.variance = v.variance; state.stages[id] = RECEIVED; state.events[id] = { ...(state.events[id] || {}), [RECEIVED]: now() };
      const po = state.pos.find((p) => p.id === s.po); if (po) po.history.unshift({ at: now(), status: 'Goods received (' + r.id + ')', by: actor });
      pushSync('inventory.receipt.completed', r.id + ': ' + r.lines.length + ' batch lines → ' + Object.keys(sellBySku).length + ' sellable SKUs');
      const I = window.ATInv; const snap = I && I.snapshot();
      Object.entries(sellBySku).forEach(([sku, qty]) => {
        const p = product(sku);
        if (I && snap.rows.some((x) => x.sku === sku && x.fc === s.wh)) { I.receive(sku, s.wh, qty, r.id, actor); pushSync('inventory.availability.changed', sku + ' +' + qty + ' at ' + s.wh + ' → storefront stock updated (' + p.pid + ')'); }
        else pushSync('inventory.availability.changed', sku + ' +' + qty + ' at ' + s.wh + (p.listing.status === 'Live' ? ' → listing updated' : ' → listing is ' + p.listing.status + ', not yet sellable'));
        if (p.listing.status === 'Live') pushSync('search.index.updated', p.name + ' · next best-before ' + (state.lots.filter((x) => x.sku === sku && x.status === 'Sellable' && x.qty > 0).sort((a, b) => a.exp < b.exp ? -1 : 1)[0] || {}).exp);
      });
      save(); return { ok: true, sell: Object.values(sellBySku).reduce((a, n) => a + n, 0), total };
    },
    addProduct(d, actor) {
      const n = state.products.length + 1; const item = String(200100 + n * 7).slice(0, 6); const code = d.name.replace(/[^A-Za-z]/g, '').slice(0, 3).toUpperCase(); const sku = 'AT-' + code + '-' + (d.size || n);
      const sup = SUPPLIERS.find((x) => x.id === d.supplier);
      state.products.push({ sku, name: d.name, item, upc: upc(item), gtin14: '00' + upc(item), supplier: d.supplier, origin: sup.country, hts: d.hts, duty: (+d.duty || 0) / 100, shelf: +d.shelf, storage: d.storage, reorder: +d.reorder, price: +d.price, category: d.category, pid: null, listing: { status: 'Draft', content: { title: true, desc: false, images: false, allergens: false, nutrition: false } } });
      pushSync('catalog.product.created', sku + ' · GTIN ' + upc(item) + ' assigned from AfroTransact GS1 prefix 086001'); save(); return sku;
    },
    setListing(sku, patch, actor) {
      const p = product(sku); if (patch.content) p.listing.content = { ...p.listing.content, ...patch.content }; if (patch.price != null) p.price = +patch.price;
      if (patch.status) { p.listing.status = patch.status; if (patch.status === 'Live' && !p.pid) p.pid = 'p-' + sku.toLowerCase(); pushSync(patch.status === 'Live' ? 'catalog.listing.published' : 'catalog.listing.paused', p.name + (patch.status === 'Live' ? ' → storefront “Sold & shipped by AfroTransact”' : ' hidden from search, cart adds blocked') + ' · by ' + actor); }
      save();
    },
    setStatus(lot, status, actor, reason) {
      const x = state.lots.find((l) => l.lot === lot); if (!x) return; const v = Math.round(x.qty * x.cost * 100) / 100; const prev = x.status;
      if (status === 'Written off') { je(TODAY, lot, 'Write-off: ' + (reason || prev.toLowerCase()), [[5090, v, 0], [prev === 'Quarantine' ? 1320 : 1300, 0, v]]); state.moves.unshift({ at: now(), type: 'WRITE-OFF', sku: x.sku, lot, where: x.wh + ' ' + x.bin + ' → destroy', qty: -x.qty, ref: 'WO-' + state.seq, by: actor }); x.qty = 0; }
      if (status === 'Quarantine') { je(TODAY, lot, 'Move to quarantine', [[1320, v, 0], [1300, 0, v]]); state.moves.unshift({ at: now(), type: 'QUARANTINE', sku: x.sku, lot, where: x.wh + ' ' + x.bin + ' → Q-01', qty: x.qty, ref: reason || 'QA hold', by: actor }); }
      if (status === 'Sellable' && prev === 'Quarantine') { je(TODAY, lot, 'Released from quarantine', [[1300, v, 0], [1320, 0, v]]); state.moves.unshift({ at: now(), type: 'RELEASE', sku: x.sku, lot, where: 'Q-01 → ' + x.wh + ' ' + x.bin, qty: x.qty, ref: 'QA pass', by: actor }); }
      x.status = status; save();
    },
    reset() { state = seed(); save(); }
  };
  window.ATInvHub = api;
})();
