import { buildCsp } from '@afrotransact/ui/csp';
import { describe, expect, it } from 'vitest';

import config from '../next.config';

// AVIF and WebP are product picture formats under every picture-rules profile (docs/engineering/picture-rules.md): Next.js serves AVIF
// first and WebP next when image optimisation is on, and the pictures come from the media origin the CSP already allows.
describe('product pictures: AVIF and WebP', () => {
  it('asks Next.js for AVIF first and WebP next', async () => {
    expect((await config()).images?.formats).toEqual(['image/avif', 'image/webp']);
  });

  it('lets the media origin serve images of any accepted type (the CSP is by origin, not by file type)', () => {
    const csp = buildCsp('storefront', 'n', { MEDIA_PUBLIC_URL: 'https://media.afrotransact.com/media', APP_ENV: 'prod' });
    const img = csp.split(';').map((d) => d.trim()).find((d) => d.startsWith('img-src')) ?? '';
    expect(img).toContain('https://media.afrotransact.com');
    expect(img).not.toContain("'unsafe-inline'");
  });
});
