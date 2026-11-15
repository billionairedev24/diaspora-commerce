/* MOCK DATA ONLY: stand-in "studio shots" for the demo storefronts. The mock BFF answers /v1/mock-photos/{name}.jpg with an
   SVG drawn from the name (soft backdrop, floor shadow, a plain product form with a label), so the page can be looked at
   without a media service. Real storefronts only ever show real photographs from AfroTransact media. */

import { type DemoShape } from './fixtures/laserfeet';

const hash = (s: string): number => {
  let h = 2166136261;
  for (let i = 0; i < s.length; i++) h = Math.imul(h ^ s.charCodeAt(i), 16777619);
  return h >>> 0;
};
const hsl = (h: number, s: number, l: number) => `hsl(${h % 360} ${s}% ${l}%)`;
const esc = (s: string) => s.replace(/&/g, '&amp;').replace(/</g, '&lt;');

function body(shape: DemoShape, hue: number, label: string): string {
  const main = hsl(hue, 42, 46);
  const dark = hsl(hue, 45, 30);
  const light = hsl(hue, 55, 78);
  const text = label.length > 15 ? [label.slice(0, label.lastIndexOf(' ', 15) > 0 ? label.lastIndexOf(' ', 15) : 15), label.slice(label.lastIndexOf(' ', 15) > 0 ? label.lastIndexOf(' ', 15) + 1 : 15)] : [label];
  const tspans = text.map((t, i) => `<text x="400" y="${560 + i * 40}" text-anchor="middle" font-family="Inter, Arial, sans-serif" font-size="34" font-weight="600" fill="#fff">${esc(t.toUpperCase().slice(0, 16))}</text>`).join('');
  const gloss = `<rect x="470" y="300" width="22" height="330" rx="11" fill="#fff" opacity=".16"/>`;
  switch (shape) {
    case 'jar':
      return `<rect x="250" y="320" width="300" height="330" rx="46" fill="url(#b)"/><rect x="236" y="250" width="328" height="96" rx="30" fill="${dark}"/><rect x="236" y="250" width="328" height="20" rx="10" fill="#fff" opacity=".18"/>${tspans}${gloss}`;
    case 'tube':
      return `<path d="M290 260 L510 260 L486 700 L314 700 Z" fill="url(#b)"/><rect x="330" y="224" width="140" height="48" rx="12" fill="${dark}"/><rect x="314" y="700" width="172" height="24" rx="6" fill="${dark}"/>${tspans}${gloss}`;
    case 'bottle':
      return `<rect x="330" y="200" width="140" height="90" rx="22" fill="${dark}"/><rect x="262" y="278" width="276" height="400" rx="60" fill="url(#b)"/>${tspans}${gloss}`;
    case 'socks':
      return `<rect x="230" y="330" width="340" height="150" rx="40" fill="${light}"/><rect x="250" y="470" width="300" height="130" rx="40" fill="url(#b)"/><rect x="270" y="590" width="260" height="110" rx="40" fill="${dark}"/><rect x="230" y="330" width="340" height="26" rx="13" fill="${main}"/>`;
    case 'tool':
      return `<rect x="355" y="180" width="90" height="520" rx="45" fill="url(#b)" transform="rotate(18 400 440)"/><rect x="340" y="560" width="120" height="150" rx="30" fill="${dark}" transform="rotate(18 400 440)"/>`;
    default:
      return `<rect x="220" y="300" width="360" height="380" rx="14" fill="url(#b)"/><rect x="220" y="300" width="360" height="60" rx="14" fill="${dark}"/>${tspans}`;
  }
}

export function mockPhotoSvg(name: string, shape: DemoShape = 'box', label = ''): string {
  const hue = hash(name) % 360;
  const bgTop = hsl(hue + 18, 30, 93);
  const bgBottom = hsl(hue + 18, 36, 84);
  return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 800 1000" width="800" height="1000">
<defs>
<linearGradient id="bg" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="${bgTop}"/><stop offset="1" stop-color="${bgBottom}"/></linearGradient>
<linearGradient id="b" x1="0" y1="0" x2="1" y2="0"><stop offset="0" stop-color="${hsl(hue, 40, 38)}"/><stop offset=".45" stop-color="${hsl(hue, 44, 50)}"/><stop offset="1" stop-color="${hsl(hue, 40, 34)}"/></linearGradient>
<filter id="blur" x="-30%" y="-30%" width="160%" height="160%"><feGaussianBlur stdDeviation="22"/></filter>
</defs>
<rect width="800" height="1000" fill="url(#bg)"/>
<ellipse cx="400" cy="730" rx="230" ry="34" fill="#000" opacity=".22" filter="url(#blur)"/>
${body(shape, hue, label || name)}
</svg>`;
}
