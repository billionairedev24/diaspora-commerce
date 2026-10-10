// Recharts dashboard charts — data shape mirrors GET /v1/{portal}/analytics/overview
const loadScript = (src) => new Promise((res, rej) => { if (document.querySelector('script[src="' + src + '"]')) { const t = setInterval(() => { if (window.Recharts) { clearInterval(t); res(); } }, 50); return; } const s = document.createElement('script'); s.src = src; s.onload = res; s.onerror = rej; document.head.appendChild(s); });
let rcPromise = null;
const loadRecharts = () => { if (window.Recharts) return Promise.resolve(window.Recharts); if (!rcPromise) rcPromise = loadScript('https://unpkg.com/prop-types@15.8.1/prop-types.min.js').then(() => loadScript('https://unpkg.com/recharts@2.12.7/umd/Recharts.js')).then(() => window.Recharts); return rcPromise; };

const C = { gold: '#FFD300', ink: '#111111', green: '#1F8A4C', red: '#C62828', blue: '#2563EB', grey: '#9CA3AF', grid: '#E5E7EB' };
const PIE = ['#111111', '#FFD300', '#1F8A4C', '#2563EB', '#C2410C', '#9CA3AF'];
const days = (n) => Array.from({ length: n }, (_, i) => { const d = new Date(Date.now() - (n - 1 - i) * 864e5); return d.toLocaleDateString('en-US', { month: 'short', day: 'numeric' }); });
const wave = (base, amp, i, seed) => Math.round(base + amp * Math.sin((i + seed) / 3.2) + amp * 0.6 * Math.cos((i * seed) / 5) + i * base * 0.012);
const money = (v) => '$' + (v >= 1000 ? (v / 1000).toFixed(1) + 'k' : v);

function data(portal) {
  const d = days(30);
  if (portal === 'platform') return {
    trend: { title: 'Gross merchandise value · last 30 days', series: [['GMV', C.ink, 'area'], ['Net revenue', C.gold, 'line']], rows: d.map((day, i) => ({ day, GMV: wave(42000, 6000, i, 2), 'Net revenue': wave(4600, 700, i, 3) })), fmt: money },
    bars: { title: 'Orders by department', key: 'dept', series: [['Orders', C.ink]], rows: [['Groceries', 1840], ['Fashion', 1210], ['Beauty', 760], ['Home & Art', 410], ['Books', 260], ['Services', 530]].map(([dept, Orders]) => ({ dept, Orders })) },
    pie: { title: 'Applications pipeline', rows: [['Approved', 128], ['In review', 23], ['Needs info', 11], ['Rejected', 9]] },
    stack: { title: 'Payouts vs refunds · weekly', key: 'wk', series: [['Payouts', C.green], ['Refunds', C.red]], rows: ['W1', 'W2', 'W3', 'W4', 'W5', 'W6'].map((wk, i) => ({ wk, Payouts: wave(180000, 22000, i, 1), Refunds: wave(9000, 2000, i, 4) })), fmt: money }
  };
  if (portal === 'provider') return {
    trend: { title: 'Bookings & deposits · last 30 days', series: [['Bookings', C.ink, 'area'], ['Deposits ($)', C.gold, 'line']], rows: d.map((day, i) => ({ day, Bookings: Math.max(0, wave(6, 3, i, 2)), 'Deposits ($)': wave(420, 160, i, 5) })) },
    bars: { title: 'Bookings by service', key: 'svc', series: [['Bookings', C.ink]], rows: [['Box braids', 64], ['Knotless', 51], ['Locs retwist', 38], ['Cornrows', 29], ['Kids styles', 22]].map(([svc, Bookings]) => ({ svc, Bookings })) },
    pie: { title: 'Booking status', rows: [['Completed', 168], ['Upcoming', 24], ['Cancelled', 9], ['No-show', 4]] },
    stack: { title: 'Earnings by week', key: 'wk', series: [['Paid out', C.green], ['Pending', C.gold]], rows: ['W1', 'W2', 'W3', 'W4', 'W5', 'W6'].map((wk, i) => ({ wk, 'Paid out': wave(2400, 500, i, 2), Pending: wave(600, 200, i, 3) })), fmt: money }
  };
  return {
    trend: { title: 'Sales · last 30 days', series: [['Sales', C.ink, 'area'], ['Units', C.gold, 'line']], rows: d.map((day, i) => ({ day, Sales: wave(1900, 450, i, 3), Units: wave(64, 16, i, 1) })) },
    bars: { title: 'Top products by revenue', key: 'p', series: [['Revenue', C.ink]], rows: [['Ofada rice 5 lb', 6240], ['Suya spice', 4180], ['Palm oil 1 L', 3920], ['Ankara dress', 3110], ['Shea butter', 2350]].map(([p, Revenue]) => ({ p, Revenue })), fmt: money },
    pie: { title: 'Orders by fulfilment', rows: [['Fulfilled by AfroTransact', 412], ['Ship yourself', 268], ['Local driver', 97]] },
    stack: { title: 'Stock health by FC', key: 'fc', series: [['Available', C.green], ['Reserved', C.blue], ['Low / out', C.red]], rows: [['HOU1', 820, 64, 12], ['ATL1', 540, 41, 22], ['NJ1', 610, 38, 9], ['DFW1', 330, 25, 31]].map(([fc, a, r, l]) => ({ fc, Available: a, Reserved: r, 'Low / out': l })) }
  };
}

const libs = {};
const lib = (k, src, get) => { if (get()) return Promise.resolve(get()); if (!libs[k]) libs[k] = loadScript(src).then(get); return libs[k]; };
const slug = (s) => s.replace(/[^a-z0-9]+/gi, '-').replace(/^-|-$/g, '').toLowerCase();
const esc = (v) => String(v).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
const save = (blob, name) => { const a = document.createElement('a'); a.href = URL.createObjectURL(blob); a.download = name; a.click(); setTimeout(() => URL.revokeObjectURL(a.href), 4000); };
function toCsv(spec) { const keys = Object.keys(spec.rows[0]); save(new Blob(['\ufeff' + [keys.join(',')].concat(spec.rows.map((r) => keys.map((k) => JSON.stringify(r[k] ?? '')).join(','))).join('\n')], { type: 'text/csv' }), slug(spec.title) + '.csv'); }
const colL = (i) => String.fromCharCode(65 + i);
// Native .xlsx with a real (editable) Excel chart bound to the data range
async function toXlsx(spec) {
  const JSZip = await lib('jszip', 'https://unpkg.com/jszip@3.10.1/dist/jszip.min.js', () => window.JSZip);
  const keys = Object.keys(spec.rows[0]); const n = spec.rows.length; const SH = 'Data';
  const cell = (c, r, v) => typeof v === 'number' ? '<c r="' + colL(c) + r + '"' + (r === 1 ? ' s="1"' : '') + '><v>' + v + '</v></c>' : '<c r="' + colL(c) + r + '" t="inlineStr"' + (r === 1 ? ' s="1"' : '') + '><is><t>' + esc(v) + '</t></is></c>';
  const sheet = '<?xml version="1.0" encoding="UTF-8" standalone="yes"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><cols><col min="1" max="1" width="22" customWidth="1"/><col min="2" max="' + keys.length + '" width="14" customWidth="1"/></cols><sheetData>' +
    [keys].concat(spec.rows.map((r) => keys.map((k) => r[k]))).map((row, ri) => '<row r="' + (ri + 1) + '">' + row.map((v, ci) => cell(ci, ri + 1, v)).join('') + '</row>').join('') + '</sheetData><drawing r:id="rId1"/></worksheet>';
  const ref = (c) => "'" + SH + "'!$" + colL(c) + '$2:$' + colL(c) + '$' + (n + 1);
  const hex = (c) => (c || '#111111').replace('#', '').toUpperCase();
  const ser = (k, i, c) => '<c:ser><c:idx val="' + i + '"/><c:order val="' + i + '"/><c:tx><c:strRef><c:f>' + "'" + SH + "'!$" + colL(i + 1) + '$1</c:f></c:strRef></c:tx>' + (spec.kind === 'pie' ? spec.rows.map((_, p) => '<c:dPt><c:idx val="' + p + '"/><c:bubble3D val="0"/><c:spPr><a:solidFill><a:srgbClr val="' + hex(PIE[p % PIE.length]) + '"/></a:solidFill></c:spPr></c:dPt>').join('') : spec.kind === 'line' ? '<c:spPr><a:ln w="28575"><a:solidFill><a:srgbClr val="' + hex(c) + '"/></a:solidFill></a:ln></c:spPr><c:marker><c:symbol val="none"/></c:marker>' : '<c:spPr><a:solidFill><a:srgbClr val="' + hex(c) + '"/></a:solidFill></c:spPr>') +
    '<c:cat><c:strRef><c:f>' + ref(0) + '</c:f></c:strRef></c:cat><c:val><c:numRef><c:f>' + ref(i + 1) + '</c:f></c:numRef></c:val>' + (spec.kind === 'line' ? '<c:smooth val="1"/>' : '') + '</c:ser>';
  const sers = keys.slice(1).map((k, i) => ser(k, i, (spec.series[i] || [])[1])).join('');
  const ax = '<c:axId val="1001"/><c:axId val="1002"/>';
  const axes = '<c:catAx><c:axId val="1001"/><c:scaling><c:orientation val="' + (spec.kind === 'hbar' ? 'maxMin' : 'minMax') + '"/></c:scaling><c:delete val="0"/><c:axPos val="' + (spec.kind === 'hbar' ? 'l' : 'b') + '"/><c:numFmt formatCode="General" sourceLinked="0"/><c:tickLblPos val="nextTo"/><c:crossAx val="1002"/><c:crosses val="autoZero"/><c:auto val="1"/><c:lblAlgn val="ctr"/><c:lblOffset val="100"/></c:catAx><c:valAx><c:axId val="1002"/><c:scaling><c:orientation val="minMax"/></c:scaling><c:delete val="0"/><c:axPos val="' + (spec.kind === 'hbar' ? 'b' : 'l') + '"/><c:majorGridlines><c:spPr><a:ln><a:solidFill><a:srgbClr val="E5E7EB"/></a:solidFill></a:ln></c:spPr></c:majorGridlines><c:numFmt formatCode="' + (spec.fmt ? '&quot;$&quot;#,##0' : '#,##0') + '" sourceLinked="0"/><c:tickLblPos val="nextTo"/><c:crossAx val="1001"/><c:crosses val="' + (spec.kind === 'hbar' ? 'max' : 'autoZero') + '"/><c:crossBetween val="between"/></c:valAx>';
  const plot = spec.kind === 'pie' ? '<c:doughnutChart><c:varyColors val="1"/>' + sers + '<c:firstSliceAng val="0"/><c:holeSize val="55"/></c:doughnutChart>'
    : spec.kind === 'line' ? '<c:lineChart><c:grouping val="standard"/><c:varyColors val="0"/>' + sers + '<c:marker val="1"/>' + ax + '</c:lineChart>' + axes
    : '<c:barChart><c:barDir val="' + (spec.kind === 'hbar' ? 'bar' : 'col') + '"/><c:grouping val="' + (spec.kind === 'stack' ? 'stacked' : 'clustered') + '"/><c:varyColors val="0"/>' + sers + '<c:gapWidth val="60"/>' + (spec.kind === 'stack' ? '<c:overlap val="100"/>' : '') + ax + '</c:barChart>' + axes;
  const chart = '<?xml version="1.0" encoding="UTF-8" standalone="yes"?><c:chartSpace xmlns:c="http://schemas.openxmlformats.org/drawingml/2006/chart" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><c:roundedCorners val="0"/><c:chart><c:title><c:tx><c:rich><a:bodyPr/><a:p><a:pPr><a:defRPr sz="1400" b="1"/></a:pPr><a:r><a:rPr lang="en-US" sz="1400" b="1"/><a:t>' + esc(spec.title) + '</a:t></a:r></a:p></c:rich></c:tx><c:overlay val="0"/></c:title><c:autoTitleDeleted val="0"/><c:plotArea><c:layout/>' + plot + '</c:plotArea><c:legend><c:legendPos val="' + (spec.kind === 'pie' ? 'r' : 'b') + '"/><c:overlay val="0"/></c:legend><c:plotVisOnly val="1"/></c:chart></c:chartSpace>';
  const c0 = keys.length + 1;
  const drawing = '<?xml version="1.0" encoding="UTF-8" standalone="yes"?><xdr:wsDr xmlns:xdr="http://schemas.openxmlformats.org/drawingml/2006/spreadsheetDrawing" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:c="http://schemas.openxmlformats.org/drawingml/2006/chart"><xdr:twoCellAnchor><xdr:from><xdr:col>' + c0 + '</xdr:col><xdr:colOff>0</xdr:colOff><xdr:row>1</xdr:row><xdr:rowOff>0</xdr:rowOff></xdr:from><xdr:to><xdr:col>' + (c0 + 10) + '</xdr:col><xdr:colOff>0</xdr:colOff><xdr:row>22</xdr:row><xdr:rowOff>0</xdr:rowOff></xdr:to><xdr:graphicFrame macro=""><xdr:nvGraphicFramePr><xdr:cNvPr id="2" name="Chart 1"/><xdr:cNvGraphicFramePr/></xdr:nvGraphicFramePr><xdr:xfrm><a:off x="0" y="0"/><a:ext cx="0" cy="0"/></xdr:xfrm><a:graphic><a:graphicData uri="http://schemas.openxmlformats.org/drawingml/2006/chart"><c:chart r:id="rId1"/></a:graphicData></a:graphic></xdr:graphicFrame><xdr:clientData/></xdr:twoCellAnchor></xdr:wsDr>';
  const rel = (id, type, target) => '<Relationship Id="' + id + '" Type="http://schemas.openxmlformats.org/' + type + '" Target="' + target + '"/>';
  const rels = (x) => '<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">' + x + '</Relationships>';
  const z = new JSZip();
  z.file('[Content_Types].xml', '<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/><Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/><Override PartName="/xl/drawings/drawing1.xml" ContentType="application/vnd.openxmlformats-officedocument.drawing+xml"/><Override PartName="/xl/charts/chart1.xml" ContentType="application/vnd.openxmlformats-officedocument.drawingml.chart+xml"/></Types>');
  z.file('_rels/.rels', rels(rel('rId1', 'officeDocument/2006/relationships/officeDocument', 'xl/workbook.xml')));
  z.file('xl/workbook.xml', '<?xml version="1.0" encoding="UTF-8" standalone="yes"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="' + SH + '" sheetId="1" r:id="rId1"/></sheets></workbook>');
  z.file('xl/_rels/workbook.xml.rels', rels(rel('rId1', 'officeDocument/2006/relationships/worksheet', 'worksheets/sheet1.xml') + rel('rId2', 'officeDocument/2006/relationships/styles', 'styles.xml')));
  z.file('xl/styles.xml', '<?xml version="1.0" encoding="UTF-8" standalone="yes"?><styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><fonts count="2"><font><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="11"/><name val="Calibri"/></font></fonts><fills count="3"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill><fill><patternFill patternType="solid"><fgColor rgb="FFFFD300"/></patternFill></fill></fills><borders count="1"><border/></borders><cellStyleXfs count="1"><xf/></cellStyleXfs><cellXfs count="2"><xf/><xf fontId="1" fillId="2" applyFont="1" applyFill="1"/></cellXfs></styleSheet>');
  z.file('xl/worksheets/sheet1.xml', sheet);
  z.file('xl/worksheets/_rels/sheet1.xml.rels', rels(rel('rId1', 'officeDocument/2006/relationships/drawing', '../drawings/drawing1.xml')));
  z.file('xl/drawings/drawing1.xml', drawing);
  z.file('xl/drawings/_rels/drawing1.xml.rels', rels(rel('rId1', 'officeDocument/2006/relationships/chart', '../charts/chart1.xml')));
  z.file('xl/charts/chart1.xml', chart);
  save(await z.generateAsync({ type: 'blob', mimeType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' }), slug(spec.title) + '.xlsx');
}
async function toPdf(spec, el) {
  const J = await lib('jspdf', 'https://unpkg.com/jspdf@2.5.1/dist/jspdf.umd.min.js', () => window.jspdf && window.jspdf.jsPDF);
  const svg = el && el.querySelector('svg.recharts-surface'); const doc = new J({ unit: 'pt', format: 'letter' }); const W = doc.internal.pageSize.getWidth(); let y = 48;
  doc.setFont('helvetica', 'bold'); doc.setFontSize(16); doc.text(spec.title, 40, y); doc.setFont('helvetica', 'normal'); doc.setFontSize(9); doc.setTextColor(110); doc.text('AfroTransact · exported ' + new Date().toLocaleString('en-US'), 40, y + 16); doc.setTextColor(0); y += 32;
  if (svg) { const box = svg.getBoundingClientRect(); const clone = svg.cloneNode(true); clone.setAttribute('xmlns', 'http://www.w3.org/2000/svg'); clone.setAttribute('width', box.width); clone.setAttribute('height', box.height);
    const legend = el.querySelector('.recharts-legend-wrapper');
    const img = new Image(); img.src = 'data:image/svg+xml;charset=utf-8,' + encodeURIComponent(new XMLSerializer().serializeToString(clone)); await new Promise((r) => { img.onload = r; img.onerror = r; });
    const cv = document.createElement('canvas'); cv.width = box.width * 2; cv.height = box.height * 2; const ctx = cv.getContext('2d'); ctx.fillStyle = '#fff'; ctx.fillRect(0, 0, cv.width, cv.height); ctx.drawImage(img, 0, 0, cv.width, cv.height);
    const w = W - 80; const h = w * box.height / box.width; doc.addImage(cv.toDataURL('image/png'), 'PNG', 40, y, w, h); y += h + 12;
    if (legend) { doc.setFontSize(9); doc.text(spec.series.map((s) => s[0]).join('   ·   '), 40, y); y += 18; } }
  const keys = Object.keys(spec.rows[0]); const cw = (W - 80) / keys.length;
  const head = () => { doc.setFillColor(255, 211, 0); doc.rect(40, y - 11, W - 80, 16, 'F'); doc.setFont('helvetica', 'bold'); keys.forEach((k, i) => doc.text(String(k), 44 + i * cw, y)); doc.setFont('helvetica', 'normal'); y += 16; };
  doc.setFontSize(9); head();
  spec.rows.forEach((r, ri) => { if (y > doc.internal.pageSize.getHeight() - 40) { doc.addPage(); y = 48; head(); } if (ri % 2) { doc.setFillColor(246, 246, 246); doc.rect(40, y - 11, W - 80, 15, 'F'); } keys.forEach((k, i) => doc.text(typeof r[k] === 'number' ? r[k].toLocaleString('en-US') : String(r[k]), 44 + i * cw, y)); y += 15; });
  doc.save(slug(spec.title) + '.pdf');
}

function Card({ spec, children }) {
  const [open, setOpen] = React.useState(false); const [busy, setBusy] = React.useState(''); const ref = React.useRef(null);
  const run = (k, fn) => { setOpen(false); setBusy(k); Promise.resolve().then(fn).catch((e) => console.error(e)).finally(() => setBusy('')); };
  const h = React.createElement; const item = (k, label, icon, fn) => h('button', { key: k, type: 'button', onClick: () => run(k, fn), style: { display: 'flex', alignItems: 'center', gap: 8, width: '100%', height: 34, padding: '0 12px', border: 0, background: 'none', cursor: 'pointer', fontSize: 13, textAlign: 'left' }, onMouseEnter: (e) => { e.currentTarget.style.background = 'var(--at-color-surface-hover)'; }, onMouseLeave: (e) => { e.currentTarget.style.background = 'none'; } }, h('i', { className: icon, style: { fontSize: 16 } }), label);
  return h('div', { ref, style: { border: '1px solid var(--at-color-border)', borderRadius: 'var(--at-radius-md)', padding: 'var(--at-space-4)', background: 'var(--at-color-surface)', display: 'flex', flexDirection: 'column', gap: 'var(--at-space-3)', minWidth: 0 } },
    h('div', { style: { display: 'flex', alignItems: 'center', gap: 8, position: 'relative' } }, h('strong', { style: { flex: 1, fontSize: 'var(--at-text-md)' } }, spec.title),
      h('button', { type: 'button', onClick: () => setOpen(!open), 'aria-expanded': open, style: { display: 'flex', alignItems: 'center', gap: 6, border: '1px solid var(--at-color-border-strong)', borderRadius: 'var(--at-radius-pill)', background: 'var(--at-color-surface)', height: 30, padding: '0 12px', cursor: 'pointer', fontSize: 'var(--at-text-xs)', fontWeight: 600 } }, h('i', { className: busy ? 'ph ph-circle-notch' : 'ph ph-download-simple', style: { animation: busy ? 'atspin 1s linear infinite' : 'none' } }), busy ? 'Exporting…' : 'Export', h('i', { className: 'ph ph-caret-down' })),
      open ? h('div', { role: 'menu', style: { position: 'absolute', right: 0, top: 36, zIndex: 20, minWidth: 200, background: 'var(--at-color-surface)', border: '1px solid var(--at-color-border)', borderRadius: 'var(--at-radius-sm)', boxShadow: 'var(--at-shadow-md)', padding: '4px 0' } },
        item('pdf', 'PDF — chart + table', 'ph ph-file-pdf', () => toPdf(spec, ref.current)), item('xlsx', 'Excel — data + live chart', 'ph ph-microsoft-excel-logo', () => toXlsx(spec)), item('csv', 'CSV — raw data', 'ph ph-file-csv', () => toCsv(spec))) : null),
    h('div', { style: { height: 240 } }, children));
}

function DashCharts({ portal }) {
  const [R, setR] = React.useState(window.Recharts || null); const [err, setErr] = React.useState('');
  React.useEffect(() => { if (!R) loadRecharts().then(setR).catch(() => setErr('Charts failed to load')); }, []);
  const D = React.useMemo(() => data(portal || 'platform'), [portal]);
  const grid = { display: 'grid', gridTemplateColumns: 'repeat(auto-fit,minmax(min(100%,420px),1fr))', gap: 'var(--at-space-4)' };
  if (!R) return React.createElement('div', { style: { ...grid } }, [0, 1, 2, 3].map((i) => React.createElement('div', { key: i, style: { height: 300, borderRadius: 'var(--at-radius-md)', background: 'var(--at-color-surface-muted)', display: 'grid', placeItems: 'center', color: 'var(--at-color-text-muted)', fontSize: 13 } }, err || 'Loading chart…')));
  const h = React.createElement; const tick = { fontSize: 11, fill: '#6B7280' }; const tt = { contentStyle: { borderRadius: 8, border: '1px solid #E5E7EB', fontSize: 12 } };
  const t = D.trend, b = D.bars, p = D.pie, st = D.stack;
  return h('div', { style: grid },
    h(Card, { spec: { ...t, kind: 'line' } }, h(R.ResponsiveContainer, null, h(R.ComposedChart, { data: t.rows, margin: { top: 4, right: 8, left: 0, bottom: 0 } }, h(R.CartesianGrid, { stroke: C.grid, vertical: false }), h(R.XAxis, { dataKey: 'day', tick, interval: 6, tickLine: false, axisLine: false }), h(R.YAxis, { yAxisId: 'l', tick, tickFormatter: t.fmt, width: 48, axisLine: false, tickLine: false }), h(R.YAxis, { yAxisId: 'r', orientation: 'right', tick, width: 40, axisLine: false, tickLine: false, tickFormatter: t.fmt }), h(R.Tooltip, tt), h(R.Legend, { iconType: 'circle', wrapperStyle: { fontSize: 12 } }),
      h(R.Area, { yAxisId: 'l', type: 'monotone', dataKey: t.series[0][0], stroke: t.series[0][1], fill: t.series[0][1], fillOpacity: 0.08, strokeWidth: 2 }), h(R.Line, { yAxisId: 'r', type: 'monotone', dataKey: t.series[1][0], stroke: '#C9A400', strokeWidth: 2, dot: false })))),
    h(Card, { spec: { ...b, kind: 'hbar' } }, h(R.ResponsiveContainer, null, h(R.BarChart, { data: b.rows, layout: 'vertical', margin: { top: 4, right: 16, left: 8, bottom: 0 } }, h(R.CartesianGrid, { stroke: C.grid, horizontal: false }), h(R.XAxis, { type: 'number', tick, tickFormatter: b.fmt, axisLine: false, tickLine: false }), h(R.YAxis, { type: 'category', dataKey: b.key, tick, width: 110, axisLine: false, tickLine: false }), h(R.Tooltip, tt), h(R.Bar, { dataKey: b.series[0][0], fill: C.ink, radius: [0, 4, 4, 0], barSize: 18 })))),
    h(Card, { spec: { title: p.title, kind: 'pie', series: [['Count']], rows: p.rows.map(([name, value]) => ({ [p.key || 'Segment']: name, Count: value })) } }, h(R.ResponsiveContainer, null, h(R.PieChart, null, h(R.Pie, { data: p.rows.map(([name, value]) => ({ name, value })), dataKey: 'value', nameKey: 'name', innerRadius: '55%', outerRadius: '85%', paddingAngle: 2 }, p.rows.map((_, i) => h(R.Cell, { key: i, fill: PIE[i % PIE.length] }))), h(R.Tooltip, tt), h(R.Legend, { layout: 'vertical', align: 'right', verticalAlign: 'middle', iconType: 'circle', wrapperStyle: { fontSize: 12 } })))),
    h(Card, { spec: { ...st, kind: 'stack' } }, h(R.ResponsiveContainer, null, h(R.BarChart, { data: st.rows, margin: { top: 4, right: 8, left: 0, bottom: 0 } }, h(R.CartesianGrid, { stroke: C.grid, vertical: false }), h(R.XAxis, { dataKey: st.key, tick, axisLine: false, tickLine: false }), h(R.YAxis, { tick, tickFormatter: st.fmt, width: 48, axisLine: false, tickLine: false }), h(R.Tooltip, tt), h(R.Legend, { iconType: 'circle', wrapperStyle: { fontSize: 12 } }), st.series.map(([k, c], i) => h(R.Bar, { key: k, dataKey: k, stackId: 'a', fill: c, radius: i === st.series.length - 1 ? [4, 4, 0, 0] : 0 })))))
  );
}
module.exports = { DashCharts };
