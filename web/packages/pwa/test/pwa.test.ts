import { describe, expect, it } from 'vitest';

import { buildManifest, installMode, isIosDevice, MANIFEST_HEADERS, offlineHtml, shouldRegister } from '../src';

describe('manifest', () => {
  const manifest = buildManifest({
    name: 'AfroTransact',
    shortName: 'AfroTransact',
    startUrl: '/?source=pwa',
    scope: '/',
    themeColor: 'theme',
    backgroundColor: 'background',
    icons: [
      { src: '/icons/icon-192.png', sizes: '192x192', type: 'image/png', purpose: 'any' },
      { src: '/icons/icon-512.png', sizes: '512x512', type: 'image/png', purpose: 'any' },
      { src: '/icons/maskable-512.png', sizes: '512x512', type: 'image/png', purpose: 'maskable' }
    ]
  });

  it('is installable: standalone, name, start_url inside scope, 192 and 512 icons and a maskable one', () => {
    expect(manifest.display).toBe('standalone');
    expect(manifest.name).toBeTruthy();
    expect(manifest.short_name).toBeTruthy();
    expect(manifest.start_url.startsWith(manifest.scope)).toBe(true);
    const sizes = manifest.icons.filter((i) => i.purpose === 'any').map((i) => i.sizes);
    expect(sizes).toEqual(expect.arrayContaining(['192x192', '512x512']));
    expect(manifest.icons.some((i) => i.purpose === 'maskable')).toBe(true);
    expect(manifest.theme_color).toBe('theme');
    expect(manifest.background_color).toBe('background');
    expect(manifest.id).toBe('/');
  });

  it('is served as the manifest media type and revalidated', () => {
    expect(MANIFEST_HEADERS['Content-Type']).toMatch(/^application\/manifest\+json/);
    expect(MANIFEST_HEADERS['Cache-Control']).toContain('max-age=3600');
  });
});

describe('install entry', () => {
  const base = { userAgent: 'Mozilla/5.0 (X11; Linux x86_64)', maxTouchPoints: 0, platform: 'Linux x86_64', standalone: false, hasPrompt: false };
  const iphone = { ...base, userAgent: 'Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X)', platform: 'iPhone', maxTouchPoints: 5 };
  const ipadOs = { ...base, userAgent: 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7)', platform: 'MacIntel', maxTouchPoints: 5 };

  it('offers the browser prompt when one was handed over', () => {
    expect(installMode({ ...base, hasPrompt: true })).toBe('prompt');
  });

  it('shows the Share > Add to Home Screen steps on iPhone and iPad (iPadOS reports a Mac)', () => {
    expect(installMode(iphone)).toBe('ios');
    expect(installMode(ipadOs)).toBe('ios');
    expect(isIosDevice({ ...base, platform: 'MacIntel' })).toBe(false);
  });

  it('shows nothing when already installed or when the browser cannot install', () => {
    expect(installMode({ ...iphone, standalone: true })).toBe('none');
    expect(installMode({ ...base, hasPrompt: true, standalone: true })).toBe('none');
    expect(installMode(base)).toBe('none');
  });
});

describe('registration', () => {
  it('registers in production only', () => {
    expect(shouldRegister({ nodeEnv: 'production', hasServiceWorker: true })).toBe(true);
    expect(shouldRegister({ nodeEnv: 'development', hasServiceWorker: true })).toBe(false);
    expect(shouldRegister({ nodeEnv: 'test', hasServiceWorker: true })).toBe(false);
    expect(shouldRegister({ nodeEnv: 'production', hasServiceWorker: false })).toBe(false);
  });
});

describe('offline page', () => {
  const html = offlineHtml({
    lang: 'en',
    dir: 'ltr',
    title: 'Offline <x>',
    heading: 'You are offline',
    body: 'Check your connection & try again.',
    retryLabel: 'Try again',
    retryHref: '/',
    colors: { canvas: 'white', text: 'black', textMuted: 'grey', actionPrimary: 'gold', actionPrimaryText: 'black' },
    fontFamily: 'Inter, sans-serif'
  });

  it('is a self-contained, accessible document: language, title, one h1, a main landmark, no script or external request', () => {
    expect(html).toContain('<html lang="en" dir="ltr">');
    expect(html).toContain('<title>Offline &lt;x&gt;</title>');
    expect(html.match(/<h1>/g)).toHaveLength(1);
    expect(html).toContain('<main>');
    expect(html).not.toMatch(/<script|<link|src=|url\(/i);
    expect(html).toContain('Check your connection &amp; try again.');
    expect(html).toContain('<a href="/">Try again</a>');
  });
});
