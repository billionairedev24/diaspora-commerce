import axe from 'axe-core';
import { describe, expect, it, vi } from 'vitest';

import { tEn } from '@/i18n/t';

vi.mock('@/i18n/server', () => ({ getLocale: () => Promise.resolve('en'), getT: () => Promise.resolve(tEn) }));

describe('offline page route', () => {
  it('is a public, uncached-by-proxies HTML document with the offline message and a way back', async () => {
    const { GET } = await import('@/app/offline/route');
    const res = await GET();
    expect(res.headers.get('content-type')).toMatch(/^text\/html/);
    expect(res.headers.get('cache-control')).toMatch(/private/);
    const html = await res.text();
    expect(html).toContain('<html lang="en" dir="ltr">');
    expect(html).toContain('You are offline');
    expect(html).toContain('<a href="/">Try again</a>');
    expect(html).not.toContain('<script');
  });

  it('is axe-clean (landmark, heading, language, title, link name)', async () => {
    const { GET } = await import('@/app/offline/route');
    document.documentElement.innerHTML = (await (await GET()).text()).replace(/^<!doctype html><html[^>]*>/i, '').replace(/<\/html>$/, '');
    document.documentElement.lang = 'en';
    // jsdom has no layout or colour engine: the colours are design tokens whose pairs the token tests check.
    const results = await axe.run(document, { rules: { 'color-contrast': { enabled: false } } });
    expect(results.violations).toEqual([]);
  });
});
