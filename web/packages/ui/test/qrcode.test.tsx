import { render, screen } from '@testing-library/react';
import jsQR from 'jsqr';
import { describe, expect, it } from 'vitest';

import { QR_QUIET_ZONE, QrCodeImage, qrMatrix } from '../src/security/QrCodeImage';

/** Rasterises the rendered SVG path (1×1 module squares) into RGBA pixels and reads it back with jsQR. */
function decode(svg: SVGElement): string | null {
  const box = Number(svg.getAttribute('viewBox')?.split(' ')[2]);
  const d = svg.querySelector('path')?.getAttribute('d') ?? '';
  const scale = 6;
  const px = box * scale;
  const rgba = new Uint8ClampedArray(px * px * 4).fill(255);
  for (const m of d.matchAll(/M(\d+) (\d+)h1v1h-1z/g)) {
    const x0 = Number(m[1]) * scale;
    const y0 = Number(m[2]) * scale;
    for (let y = y0; y < y0 + scale; y++) {
      for (let x = x0; x < x0 + scale; x++) {
        const i = (y * px + x) * 4;
        rgba[i] = rgba[i + 1] = rgba[i + 2] = 0;
      }
    }
  }
  return jsQR(rgba, px, px)?.data ?? null;
}

const URI = 'otpauth://totp/AfroTransact:seller.owner%40afrotransact.test?secret=JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP&issuer=AfroTransact&algorithm=SHA1&digits=6&period=30';

describe('QrCodeImage', () => {
  it('renders a scannable SVG that decodes to the exact otpauth URI', () => {
    render(<QrCodeImage value={URI} label="QR code for your authenticator app" />);
    const svg = screen.getByRole('img', { name: 'QR code for your authenticator app' }) as unknown as SVGElement;
    expect(decode(svg)).toBe(URI);
  });

  it('keeps a 4-module quiet zone and scales with the size prop', () => {
    render(<QrCodeImage value="otpauth://totp/A:b?secret=ABC" label="qr" size={240} />);
    const svg = screen.getByRole('img', { name: 'qr' });
    const { size } = qrMatrix('otpauth://totp/A:b?secret=ABC');
    expect(svg.getAttribute('viewBox')).toBe(`0 0 ${size + 2 * QR_QUIET_ZONE} ${size + 2 * QR_QUIET_ZONE}`);
    expect(svg.getAttribute('width')).toBe('240');
    expect(svg.querySelector('path')?.getAttribute('d')).not.toMatch(/M[0-3] /);
  });

  it('produces version-appropriate symbols with the finder pattern in the corner', () => {
    const m = qrMatrix(URI);
    expect((m.size - 17) % 4).toBe(0);
    // Finder pattern: a dark 7×7 ring at the top-left.
    expect(m.dark.slice(0, 7).every(Boolean)).toBe(true);
    expect(m.dark[m.size + 1]).toBe(false);
  });
});
