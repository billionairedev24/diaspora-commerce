/* AfroTransact seller theme engine — OKLCH ramps + WCAG 2.2 contrast vetting.
   Same algorithm ships server-side in storefront-service (Go) so a theme can never be
   published that fails contrast. window.ATTheme.vet(input) -> { checks, tokens, ramp, ok } */
(function () {
  const clamp = (x, a, b) => Math.min(b, Math.max(a, x));
  const hexToRgb = (h) => { h = h.replace('#', ''); if (h.length === 3) h = h.split('').map((c) => c + c).join(''); const n = parseInt(h, 16); return [(n >> 16) & 255, (n >> 8) & 255, n & 255]; };
  const rgbToHex = (r, g, b) => '#' + [r, g, b].map((v) => Math.round(clamp(v, 0, 255)).toString(16).padStart(2, '0')).join('').toUpperCase();
  const s2l = (c) => { c /= 255; return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4); };
  const l2s = (c) => 255 * (c <= 0.0031308 ? 12.92 * c : 1.055 * Math.pow(c, 1 / 2.4) - 0.055);
  const luminance = (hex) => { const [r, g, b] = hexToRgb(hex).map(s2l); return 0.2126 * r + 0.7152 * g + 0.0722 * b; };
  const contrast = (a, b) => { const la = luminance(a), lb = luminance(b); return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05); };

  const toOklch = (hex) => {
    const [r, g, b] = hexToRgb(hex).map(s2l);
    const l = Math.cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b);
    const m = Math.cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b);
    const s = Math.cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b);
    const L = 0.2104542553 * l + 0.793617785 * m - 0.0040720468 * s;
    const A = 1.9779984951 * l - 2.428592205 * m + 0.4505937099 * s;
    const B = 0.0259040371 * l + 0.7827717662 * m - 0.808675766 * s;
    return { L, C: Math.hypot(A, B), H: Math.atan2(B, A) };
  };
  const fromOklch = ({ L, C, H }) => {
    for (let c = C; c >= 0; c -= 0.005) {
      const A = c * Math.cos(H), B = c * Math.sin(H);
      const l = Math.pow(L + 0.3963377774 * A + 0.2158037573 * B, 3);
      const m = Math.pow(L - 0.1055613458 * A - 0.0638541728 * B, 3);
      const s = Math.pow(L - 0.0894841775 * A - 1.291485548 * B, 3);
      const rgb = [4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s, -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s, -0.0041960863 * l - 0.7034186147 * m + 1.707614701 * s];
      if (rgb.every((v) => v >= -0.0005 && v <= 1.0005)) return rgbToHex(...rgb.map((v) => l2s(clamp(v, 0, 1))));
    }
    return rgbToHex(...[L, L, L].map((v) => l2s(clamp(v, 0, 1))));
  };

  const STEPS = [50, 100, 200, 300, 400, 500, 600, 700, 800, 900];
  const LIGHT = [0.975, 0.945, 0.885, 0.815, 0.735, 0.655, 0.565, 0.475, 0.38, 0.285];
  const ramp = (hex) => { const o = toOklch(hex); return STEPS.map((step, i) => ({ step, hex: fromOklch({ L: LIGHT[i], C: o.C * (i < 2 ? 0.35 : i < 4 ? 0.75 : 1), H: o.H }) })); };

  /* Move lightness until contrast against `bg` reaches `target`. */
  const fit = (hex, bg, target) => {
    if (contrast(hex, bg) >= target) return hex;
    const o = toOklch(hex); const darker = luminance(bg) > 0.18;
    for (let i = 1; i <= 100; i++) { const L = clamp(o.L + (darker ? -i : i) * 0.008, 0, 1); const c = fromOklch({ L, C: o.C, H: o.H }); if (contrast(c, bg) >= target) return c; }
    return darker ? '#000000' : '#FFFFFF';
  };
  const onColor = (bg) => (contrast('#000000', bg) >= contrast('#FFFFFF', bg) ? '#000000' : '#FFFFFF');

  const vet = ({ primary, accent, surface = '#FFFFFF', text = '#191816' }) => {
    const onP = onColor(primary), onA = onColor(accent);
    const ink = fit(primary, surface, 4.5), accentInk = fit(accent, surface, 4.5);
    const pr = ramp(primary);
    const checks = [
      { id: 'body', label: 'Body text on store surface', fg: text, bg: surface, need: 4.5 },
      { id: 'onPrimary', label: 'Button label on primary', fg: onP, bg: primary, need: 4.5 },
      { id: 'onAccent', label: 'Button label on accent', fg: onA, bg: accent, need: 4.5 },
      { id: 'primaryUi', label: 'Primary as UI edge on surface (non-text)', fg: primary, bg: surface, need: 3, fix: fit(primary, surface, 3) },
      { id: 'primaryText', label: 'Primary used as text / links', fg: primary, bg: surface, need: 4.5, fix: ink },
      { id: 'accentText', label: 'Accent used as text', fg: accent, bg: surface, need: 4.5, fix: accentInk }
    ].map((c) => { const ratio = contrast(c.fg, c.bg); return { ...c, ratio: Math.round(ratio * 100) / 100, pass: ratio >= c.need }; });
    const blocking = checks.filter((c) => ['body', 'onPrimary', 'onAccent'].includes(c.id) && !c.pass);
    return {
      ok: blocking.length === 0, checks, ramp: pr,
      tokens: {
        '--store-primary': primary, '--store-primary-hover': pr[6].hex, '--store-on-primary': onP,
        '--store-accent': accent, '--store-on-accent': onA, '--store-ink': ink, '--store-tint': pr[0].hex,
        '--store-surface': surface, '--store-text': text
      }
    };
  };
  const apply = (el, tokens) => { if (el) Object.entries(tokens).forEach(([k, v]) => el.style.setProperty(k, v)); };
  window.ATTheme = { contrast, luminance, ramp, fit, onColor, vet, apply, toOklch };
})();
