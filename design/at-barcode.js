/* Barcode encoders used by Catalog, Seller Center labels and the driver app.
   Code 128-B for AfroTransact identifiers (ATIN, AT-FN unit labels, shipment labels, LPNs, bins)
   and EAN-13 / UPC-A (GTIN) for manufacturer barcodes. Output is a list of bars for rendering. */
(function () {
  const C128 = '212222 222122 222221 121223 121322 131222 122213 122312 132212 221213 221312 231212 112232 122132 122231 113222 123122 123221 223211 221132 221231 213212 223112 312131 311222 321122 321221 312212 322112 322211 212123 212321 232121 111323 131123 131321 112313 132113 132311 211313 231113 231311 112133 112331 132131 113123 113321 133121 313121 211331 231131 213113 213311 213131 311123 311321 331121 312113 312311 332111 314111 221411 431111 111224 111422 121124 121421 141122 141221 112214 112412 122114 122411 142112 142211 241211 221114 413111 241112 134111 111242 121142 121241 114212 124112 124211 411212 421112 421211 212141 214121 412121 111143 111341 131141 114113 114311 411113 411311 113141 114131 311141 411131 211412 211214 211232'.split(' ');
  const STOP = '2331112';
  const widthsToBars = (w) => w.split('').map((n, i) => ({ w: Number(n), on: i % 2 === 0 }));
  const code128 = (text) => {
    const vals = [...String(text)].map((ch) => { const v = ch.charCodeAt(0) - 32; if (v < 0 || v > 94) throw new Error('Unsupported character'); return v; });
    const check = (104 + vals.reduce((a, v, i) => a + v * (i + 1), 0)) % 103;
    const seq = [104, ...vals, check].map((v) => C128[v]).join('') + STOP;
    return [{ w: 10, on: false }, ...widthsToBars(seq), { w: 10, on: false }];
  };
  const L = ['0001101', '0011001', '0010011', '0111101', '0100011', '0110001', '0101111', '0111011', '0110111', '0001011'];
  const G = ['0100111', '0110011', '0011011', '0100001', '0011101', '0111001', '0000101', '0010001', '0001001', '0010111'];
  const R = L.map((p) => p.split('').map((b) => (b === '1' ? '0' : '1')).join(''));
  const PAR = ['LLLLLL', 'LLGLGG', 'LLGGLG', 'LLGGGL', 'LGLLGG', 'LGGLLG', 'LGGGLL', 'LGLGLG', 'LGLGGL', 'LGGLGL'];
  const gtinCheck = (body) => { const d = String(body).replace(/\D/g, '').split('').reverse().map(Number); const sum = d.reduce((a, n, i) => a + n * (i % 2 === 0 ? 3 : 1), 0); return (10 - (sum % 10)) % 10; };
  const gtinValid = (code) => { const c = String(code).replace(/\D/g, ''); if (![8, 12, 13, 14].includes(c.length)) return false; return gtinCheck(c.slice(0, -1)) === Number(c.slice(-1)); };
  const ean13 = (code) => {
    let c = String(code).replace(/\D/g, ''); if (c.length === 12 && gtinValid(c)) c = '0' + c; if (c.length === 12) c = c + gtinCheck(c); if (c.length !== 13) throw new Error('EAN-13 needs 12 or 13 digits');
    const first = Number(c[0]); let m = '101';
    for (let i = 1; i <= 6; i++) m += (PAR[first][i - 1] === 'L' ? L : G)[Number(c[i])];
    m += '01010'; for (let i = 7; i <= 12; i++) m += R[Number(c[i])]; m += '101';
    const bars = []; let run = 1; for (let i = 1; i <= m.length; i++) { if (m[i] === m[i - 1]) run++; else { bars.push({ w: run, on: m[i - 1] === '1' }); run = 1; } }
    return { digits: c, bars: [{ w: 9, on: false }, ...bars, { w: 9, on: false }] };
  };
  // Seller-facing identifiers
  const B36 = '0123456789ABCDEFGHJKLMNPQRSTUVWXYZ';
  const rand = (n) => Array.from({ length: n }, () => B36[Math.floor(Math.random() * B36.length)]).join('');
  window.ATBarcode = {
    code128, ean13, gtinCheck, gtinValid,
    newAtin: () => 'AT0' + rand(7), newFnsku: () => 'ATF' + rand(7), newShipLabel: () => 'ATS' + rand(9), newLpn: () => 'LPN' + rand(8),
    // Render helper: scale bar widths to px strings for templates
    toCss: (bars, unit) => bars.map((b) => ({ w: (b.w * (unit || 2)) + 'px', bg: b.on ? 'var(--at-ink-1000)' : 'transparent' }))
  };
})();
