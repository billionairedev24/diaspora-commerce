#!/usr/bin/env node
/* Launcher icons, splash mark and favicon for the Expo apps, from the AfroTransact logo mark (the gold bag with the
   Africa silhouette, design/assets/logo-mark.svg — the single source of the paths).

     node ../../scripts/brand-assets.mjs customer        # from mobile/apps/customer (pnpm brand)
     node ../../scripts/brand-assets.mjs driver --check  # fails when a committed file is stale

   Writes, per app:
     assets/brand/*.svg      the composed sources (committed, reviewable)
     assets/images/*.png     icon (1024, opaque, iOS + default), adaptive-icon (Android foreground, transparent,
                             inside the 66% safe zone), adaptive-monochrome (Android 13 themed icons), splash-icon
                             (the mark for expo-splash-screen), favicon (web)
   The customer app is the plain mark on black, like any big consumer app; the driver app is the same mark with a gold
   road-arrow badge so the two are told apart on a home screen. PNGs are rendered with sharp (librsvg). */
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = resolve(HERE, '../..');
const app = process.argv[2];
const check = process.argv.includes('--check');
if (app !== 'customer' && app !== 'driver') {
  console.error('usage: brand-assets.mjs customer|driver [--check]');
  process.exit(2);
}
const APP_DIR = join(ROOT, 'mobile/apps', app);
const sharp = createRequire(join(APP_DIR, 'package.json'))('sharp');

// Brand colours (tokens: --at-gold-500, --at-ink-1000, --at-ink-0). Asset files only; components use the tokens.
const GOLD = '#FFD400';
const BLACK = '#000000';
const WHITE = '#FFFFFF';

/* The mark's three paths: bag (gold), Africa and Madagascar (cut out of the bag). */
const source = readFileSync(join(ROOT, 'design/assets/logo-mark.svg'), 'utf8');
const paths = [...source.matchAll(/<path d="([^"]+)"/g)].map((m) => m[1]);
if (paths.length !== 3) throw new Error(`design/assets/logo-mark.svg: expected 3 paths, found ${paths.length}`);
const [BAG, AFRICA, MADAGASCAR] = paths;
// Bounding box of the bag in the path coordinates.
const MARK_W = 103.14;
const MARK_H = 117.63;

/** The mark scaled to height h, centred on (cx, cy). mono: one colour with Africa as a hole (evenodd). */
function mark(cx, cy, h, { mono = null, cutout = BLACK } = {}) {
  const k = h / MARK_H;
  const tx = cx - (MARK_W / 2) * k;
  const ty = cy - (MARK_H / 2) * k;
  const g = `<g transform="translate(${tx.toFixed(2)} ${ty.toFixed(2)}) scale(${k.toFixed(5)})">`;
  if (mono) return `${g}<path fill-rule="evenodd" fill="${mono}" d="${BAG} ${AFRICA} ${MADAGASCAR}"/></g>`;
  if (cutout === null) return `${g}<path fill-rule="evenodd" fill="${GOLD}" d="${BAG} ${AFRICA} ${MADAGASCAR}"/></g>`;
  return `${g}<path fill="${GOLD}" d="${BAG}"/><path fill="${cutout}" d="${AFRICA}"/><path fill="${cutout}" d="${MADAGASCAR}"/></g>`;
}

/** Driver cue: a round badge with a road arrow, bottom right of the mark. */
function badge(cx, cy, r, { mono = null, ring = BLACK } = {}) {
  const fill = mono ?? GOLD;
  const ink = mono ? 'none' : BLACK;
  // Arrow in badge units (-1..1): a block arrow pointing right with two speed lines.
  const u = (x, y) => `${(cx + x * r).toFixed(1)} ${(cy + y * r).toFixed(1)}`;
  const arrow = `M${u(-0.38, -0.2)} L${u(0.12, -0.2)} L${u(0.12, -0.46)} L${u(0.58, 0)} L${u(0.12, 0.46)} L${u(0.12, 0.2)} L${u(-0.38, 0.2)} Z`;
  const lines = `M${u(-0.66, -0.2)} L${u(-0.5, -0.2)} M${u(-0.74, 0.2)} L${u(-0.5, 0.2)}`;
  if (mono) {
    // Themed icon: a ring with the arrow inside, one colour.
    return `<circle cx="${cx}" cy="${cy}" r="${r * 0.88}" fill="none" stroke="${fill}" stroke-width="${r * 0.16}"/>` +
      `<path d="${arrow}" fill="${fill}"/><path d="${lines}" stroke="${fill}" stroke-width="${r * 0.12}" stroke-linecap="round"/>`;
  }
  return `<circle cx="${cx}" cy="${cy}" r="${r + r * 0.14}" fill="${ring}"/><circle cx="${cx}" cy="${cy}" r="${r}" fill="${fill}"/>` +
    `<path d="${arrow}" fill="${ink}"/><path d="${lines}" stroke="${ink}" stroke-width="${r * 0.12}" stroke-linecap="round"/>`;
}

const S = 1024;
const svg = (body, bg = null) =>
  `<svg xmlns="http://www.w3.org/2000/svg" width="${S}" height="${S}" viewBox="0 0 ${S} ${S}">${bg ? `<rect width="${S}" height="${S}" fill="${bg}"/>` : ''}${body}</svg>\n`;

/* Layouts. Adaptive icons are masked to a circle/squircle showing the middle ~66%: keep everything inside it. */
const DRIVER = app === 'driver';
const sources = DRIVER
  ? {
      icon: svg(mark(S * 0.46, S * 0.47, S * 0.56) + badge(S * 0.7, S * 0.7, S * 0.15), BLACK),
      'adaptive-icon': svg(mark(S * 0.47, S * 0.48, S * 0.4) + badge(S * 0.63, S * 0.63, S * 0.1)),
      // One colour: the badge is set off from the bag by a gap (mask) instead of the black ring.
      'adaptive-monochrome': svg(
        `<mask id="gap"><rect width="${S}" height="${S}" fill="${WHITE}"/><circle cx="${S * 0.63}" cy="${S * 0.63}" r="${S * 0.125}" fill="${BLACK}"/></mask>` +
          `<g mask="url(#gap)">${mark(S * 0.47, S * 0.48, S * 0.4, { mono: WHITE })}</g>` + badge(S * 0.63, S * 0.63, S * 0.1, { mono: WHITE })
      ),
      'splash-icon': svg(mark(S * 0.46, S * 0.47, S * 0.7, { cutout: null }) + badge(S * 0.72, S * 0.74, S * 0.17, { ring: BLACK })),
      favicon: svg(mark(S * 0.46, S * 0.47, S * 0.7) + badge(S * 0.74, S * 0.74, S * 0.2), BLACK)
    }
  : {
      icon: svg(mark(S / 2, S / 2, S * 0.6), BLACK),
      'adaptive-icon': svg(mark(S / 2, S / 2, S * 0.44)),
      'adaptive-monochrome': svg(mark(S / 2, S / 2, S * 0.44, { mono: WHITE })),
      'splash-icon': svg(mark(S / 2, S / 2, S * 0.8, { cutout: null })),
      favicon: svg(mark(S / 2, S / 2, S * 0.78), BLACK)
    };
const PNG_SIZE = { icon: 1024, 'adaptive-icon': 1024, 'adaptive-monochrome': 1024, 'splash-icon': 1024, favicon: 48 };
const OPAQUE = new Set(['icon', 'favicon']);

const brandDir = join(APP_DIR, 'assets/brand');
const imagesDir = join(APP_DIR, 'assets/images');
mkdirSync(brandDir, { recursive: true });
mkdirSync(imagesDir, { recursive: true });

let stale = 0;
for (const [name, body] of Object.entries(sources)) {
  const svgPath = join(brandDir, `${name}.svg`);
  let current = '';
  try { current = readFileSync(svgPath, 'utf8'); } catch { /* new */ }
  if (current !== body) {
    if (check) { console.error(`stale: ${svgPath.slice(ROOT.length + 1)}`); stale++; continue; }
    writeFileSync(svgPath, body);
  }
  if (check) continue;
  let img = sharp(Buffer.from(body), { density: 72 * (PNG_SIZE[name] / S) * 4 }).resize(PNG_SIZE[name], PNG_SIZE[name]);
  // iOS rejects icons with an alpha channel.
  img = OPAQUE.has(name) ? img.flatten({ background: BLACK }).removeAlpha() : img.ensureAlpha();
  await img.png({ compressionLevel: 9 }).toFile(join(imagesDir, `${name}.png`));
  console.log(`  ${app}: assets/images/${name}.png (${PNG_SIZE[name]}px)`);
}
if (stale) {
  console.error(`${stale} brand source(s) out of date: run pnpm --filter @afrotransact/${app}-app brand`);
  process.exit(1);
}
