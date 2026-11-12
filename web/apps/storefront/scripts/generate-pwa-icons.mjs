// Regenerates public/icons/*.png (installable web app icons) from public/logo-mark.svg. Run after the logo or the brand
// colours change:  pnpm --filter storefront icons
// The background of the maskable and Apple touch icons is the header colour token (read from the generated tokens),
// so no colour is written down here.
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

import sharp from 'sharp';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const tokensSource = readFileSync(resolve(root, '../../packages/tokens/src/generated/tokens.ts'), 'utf8');
const resolvedBlock = tokensSource.slice(tokensSource.indexOf('export const resolvedVariables'));
const background = /"--at-color-header-bg":\s*"([^"]+)"/.exec(resolvedBlock)?.[1];
if (!background) throw new Error('--at-color-header-bg not found in the resolved tokens');

// The logo file carries a C2PA metadata block that rasterisers do not need.
const svg = readFileSync(resolve(root, 'public/logo-mark.svg'), 'utf8').replace(/<metadata>[\s\S]*?<\/metadata>/, '');

/** Logo centred on a square canvas: `fill` is the share of the canvas the logo's height takes; `solid` paints the background. */
async function icon(file, size, fill, solid) {
  const logo = await sharp(Buffer.from(svg), { density: 600 })
    .resize({ width: Math.round(size * fill), height: Math.round(size * fill), fit: 'inside' })
    .png()
    .toBuffer();
  await sharp({ create: { width: size, height: size, channels: 4, background: solid ? background : { r: 0, g: 0, b: 0, alpha: 0 } } })
    .composite([{ input: logo, gravity: 'center' }])
    .png({ compressionLevel: 9 })
    .toFile(resolve(root, 'public/icons', file));
  console.log(`public/icons/${file}`);
}

await icon('icon-192.png', 192, 0.88, false);
await icon('icon-512.png', 512, 0.88, false);
// Maskable: the launcher crops to a circle or squircle, so the logo stays inside the central 80% safe zone.
await icon('maskable-512.png', 512, 0.56, true);
// iOS has no transparency: Add to Home Screen paints it black.
await icon('apple-touch-icon.png', 180, 0.66, true);
