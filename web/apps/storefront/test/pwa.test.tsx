import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

import { COMMON_NEVER_CACHE } from '@afrotransact/pwa';
import { resolvedVariables } from '@afrotransact/tokens';
import { act, fireEvent, screen } from '@testing-library/react';
import axe from 'axe-core';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { GET as getManifest } from '@/app/manifest.webmanifest/route';
import { GET as getServiceWorker } from '@/app/sw.js/route';
import { InstallApp } from '@/components/shell/InstallApp';
import { storefrontManifest, storefrontServiceWorkerConfig } from '@/lib/pwa';

import { renderWithProviders } from './render';

const root = resolve(import.meta.dirname, '..');

/** Width and height of a PNG file, from its IHDR chunk. */
function pngSize(path: string): { width: number; height: number } {
  const buf = readFileSync(resolve(root, 'public', path));
  expect(buf.subarray(1, 4).toString('ascii')).toBe('PNG');
  return { width: buf.readUInt32BE(16), height: buf.readUInt32BE(20) };
}

describe('manifest', () => {
  it('is served as the manifest media type with an installable shape', async () => {
    const res = getManifest();
    expect(res.headers.get('content-type')).toMatch(/^application\/manifest\+json/);
    const m = (await res.json()) as ReturnType<typeof storefrontManifest>;
    expect(m).toMatchObject({ name: 'AfroTransact', short_name: 'AfroTransact', display: 'standalone', scope: '/', start_url: '/?source=pwa' });
    // Colours are the design tokens, not literals of the manifest's own.
    expect(m.theme_color).toBe(resolvedVariables['--at-color-header-bg']);
    expect(m.background_color).toBe(resolvedVariables['--at-color-canvas']);
  });

  it('lists icons that exist at their declared size, including a maskable one', () => {
    const { icons } = storefrontManifest();
    expect(icons.some((i) => i.purpose === 'maskable')).toBe(true);
    for (const icon of icons) {
      const [w, h] = icon.sizes.split('x').map(Number);
      expect(pngSize(icon.src.replace(/^\//, ''))).toEqual({ width: w, height: h });
    }
    expect(icons.filter((i) => i.purpose === 'any').map((i) => i.sizes)).toEqual(expect.arrayContaining(['192x192', '512x512']));
    expect(pngSize('icons/apple-touch-icon.png')).toEqual({ width: 180, height: 180 });
  });
});

describe('service worker route', () => {
  it('is JavaScript, never cached for long, and carries the storefront rules', async () => {
    const res = getServiceWorker();
    expect(res.headers.get('content-type')).toMatch(/^text\/javascript/);
    expect(res.headers.get('cache-control')).toMatch(/no-cache/);
    const src = await res.text();
    expect(src).toContain('afrotransact-storefront');
    for (const p of ['/api', '/bff', '/auth', '/account', '/cart', '/checkout', '/orders']) expect(src).toContain(JSON.stringify(p));
  });

  it('never caches the BFF proxy, auth, cart, checkout or account paths', () => {
    const config = storefrontServiceWorkerConfig();
    for (const p of ['/api', '/bff', '/auth', '/cart', '/checkout', '/account', '/orders']) expect(config.neverCache).toContain(p);
    expect(config.neverCache).toEqual(COMMON_NEVER_CACHE);
    // Only build output and brand assets are static; no page is.
    expect(config.staticPrefixes).toEqual(['/_next/static/', '/icons/']);
    expect(config.staticFiles).toEqual(['/logo-mark.svg']);
  });
});

describe('Cloudflare headers file', () => {
  const headers = readFileSync(resolve(root, 'public/_headers'), 'utf8');
  it('makes hashed build output immutable and the unversioned icons short-lived', () => {
    expect(headers).toMatch(/\/_next\/static\/\*\s+Cache-Control: public, max-age=31536000, immutable/);
    expect(headers).toMatch(/\/icons\/\*\s+Cache-Control: public, max-age=86400/);
  });
  it('never gives the worker script, manifest or offline page a long cache', () => {
    expect(headers.replace(/^#.*$/gm, '')).not.toMatch(/\/sw\.js|manifest|offline/);
  });
});

describe('install entry', () => {
  const IPHONE = 'Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 Version/18.0 Mobile/15E148 Safari/604.1';
  const original = { ua: navigator.userAgent, touch: navigator.maxTouchPoints };

  beforeEach(() => {
    vi.stubGlobal('matchMedia', (query: string) => ({ matches: false, media: query, addEventListener: vi.fn(), removeEventListener: vi.fn(), addListener: vi.fn(), removeListener: vi.fn(), onchange: null, dispatchEvent: vi.fn() }));
  });
  afterEach(() => {
    vi.unstubAllGlobals();
    Object.defineProperty(navigator, 'userAgent', { value: original.ua, configurable: true });
    Object.defineProperty(navigator, 'maxTouchPoints', { value: original.touch, configurable: true });
  });

  it('shows nothing until the browser offers to install, and never as a banner', () => {
    renderWithProviders(<InstallApp />);
    expect(screen.queryByRole('button')).not.toBeInTheDocument();
  });

  it('keeps the browser prompt and replays it from the button', async () => {
    renderWithProviders(<InstallApp />);
    const prompt = vi.fn(() => Promise.resolve());
    const event = Object.assign(new Event('beforeinstallprompt', { cancelable: true }), { prompt, userChoice: Promise.resolve({ outcome: 'accepted' as const }) });
    act(() => {
      window.dispatchEvent(event);
    });
    expect(event.defaultPrevented).toBe(true);
    const button = screen.getByRole('button', { name: 'Install the app' });
    await act(async () => {
      fireEvent.click(button);
    });
    expect(prompt).toHaveBeenCalledTimes(1);
    expect(screen.queryByRole('button', { name: 'Install the app' })).not.toBeInTheDocument();
  });

  it('disappears once the app is installed', () => {
    renderWithProviders(<InstallApp />);
    const event = Object.assign(new Event('beforeinstallprompt', { cancelable: true }), { prompt: vi.fn(), userChoice: Promise.resolve({ outcome: 'dismissed' as const }) });
    act(() => {
      window.dispatchEvent(event);
    });
    expect(screen.getByRole('button', { name: 'Install the app' })).toBeInTheDocument();
    vi.stubGlobal('matchMedia', (query: string) => ({ matches: query.includes('standalone'), media: query, addEventListener: vi.fn(), removeEventListener: vi.fn() }));
    act(() => {
      window.dispatchEvent(new Event('appinstalled'));
    });
    expect(screen.queryByRole('button', { name: 'Install the app' })).not.toBeInTheDocument();
  });

  it('on iPhone the button reveals the Share > Add to Home Screen steps', () => {
    Object.defineProperty(navigator, 'userAgent', { value: IPHONE, configurable: true });
    Object.defineProperty(navigator, 'maxTouchPoints', { value: 5, configurable: true });
    renderWithProviders(<InstallApp />);
    const button = screen.getByRole('button', { name: 'Install the app' });
    expect(button).toHaveAttribute('aria-expanded', 'false');
    expect(screen.queryByText(/Add to Home Screen/)).not.toBeInTheDocument();
    fireEvent.click(button);
    expect(button).toHaveAttribute('aria-expanded', 'true');
    expect(screen.getByText('To install on iPhone or iPad: tap Share, then Add to Home Screen.')).toBeInTheDocument();
  });

  it('is axe-clean (collapsed and expanded on iPhone)', async () => {
    Object.defineProperty(navigator, 'userAgent', { value: IPHONE, configurable: true });
    Object.defineProperty(navigator, 'maxTouchPoints', { value: 5, configurable: true });
    const { container } = renderWithProviders(<InstallApp />);
    // jsdom has no layout or colour engine: contrast is checked on the real page (Playwright axe).
    const run = () => axe.run(container, { rules: { 'color-contrast': { enabled: false } } });
    expect((await run()).violations).toEqual([]);
    fireEvent.click(screen.getByRole('button', { name: 'Install the app' }));
    expect((await run()).violations).toEqual([]);
  });
});
