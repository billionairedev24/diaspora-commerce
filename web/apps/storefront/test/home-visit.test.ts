import { NextRequest } from 'next/server';
import { describe, expect, it } from 'vitest';

import { planVisit, varietyAllowed, visitFromCookies } from '@/lib/home/visit';
import { middleware } from '@/middleware';

/* The visit behind the varied home: one visit is one stable page, the next visit a different one, crawlers and shoppers who
   refused Functional cookies get the deterministic default. The cookies are random, first-party and identify nobody. */

const HTML = { accept: 'text/html,application/xhtml+xml' };
const req = (cookie = '', headers: Record<string, string> = {}, path = '/') => new NextRequest(`https://shop.test${path}`, { headers: { ...HTML, ...(cookie ? { cookie } : {}), ...headers } });
const consent = (functional: boolean) => `at_consent=${encodeURIComponent(JSON.stringify({ necessary: true, functional, analytics: false, advertising: false, dns: false, gpc: false, v: '1', at: '2026-11-01T00:00:00Z' }))}`;

describe('planVisit', () => {
  const newId = (() => {
    let n = 0;
    return () => `id${++n}`;
  })();
  it('a first page load starts a visit: a session visitor id and a 30 minute sliding window', () => {
    const p = planVisit({ document: true, newId });
    expect(p.set.find((c) => c.name === 'at_vid')).toMatchObject({ value: expect.any(String) });
    expect(p.set.find((c) => c.name === 'at_vid')?.maxAge).toBeUndefined();
    expect(p.set.find((c) => c.name === 'at_visit')).toMatchObject({ maxAge: 1800 });
  });

  it('a returning visitor keeps the id and only slides the window', () => {
    const p = planVisit({ document: true, visitor: 'v-old', window: 'w-old', newId });
    expect(p.set.map((c) => c.name)).toEqual(['at_visit']);
    expect(p.values).toEqual({ at_vid: 'v-old', at_visit: 'w-old' });
  });

  it('only page loads start or slide a visit; crawlers get none; refusing Functional cookies clears them', () => {
    expect(planVisit({ document: false, newId }).set).toEqual([]);
    expect(planVisit({ document: true, userAgent: 'Mozilla/5.0 (compatible; Googlebot/2.1)', newId })).toMatchObject({ crawler: true, set: [] });
    expect(planVisit({ document: true, consentRaw: consent(false).slice('at_consent='.length), visitor: 'v', window: 'w', newId })).toMatchObject({ set: [], clear: expect.arrayContaining(['at_vid', 'at_visit']) });
  });

  it('no choice yet counts as on; an explicit Functional yes is on; an explicit no is off', () => {
    expect(varietyAllowed(undefined)).toBe(true);
    expect(varietyAllowed(consent(true).slice(11))).toBe(true);
    expect(varietyAllowed(consent(false).slice(11))).toBe(false);
  });
});

describe('visitFromCookies (what the server relays to the gateway)', () => {
  const jar = (o: Record<string, string>) => (n: string) => o[n];
  it('relays visitor, window and ONLY the earlier visits of the seen memory, so a refresh never reshuffles', () => {
    const v = visitFromCookies(jar({ at_vid: 'v', at_visit: 'w2', at_hs: 'w2~cur1-cur2~old1-old2.old3' }), {});
    expect(v).toEqual({ visitor: 'v', window: 'w2', seen: ['old1', 'old2', 'old3'], variant: undefined });
  });
  it('a crawler (agent or flag) is flagged and carries nothing else; a refusal carries nothing', () => {
    expect(visitFromCookies(jar({ at_vid: 'v' }), { userAgent: 'bingbot/2.0' })).toEqual({ crawler: true });
    expect(visitFromCookies(jar({ at_vid: 'v' }), { crawlerFlag: '1' })).toEqual({ crawler: true });
    expect(visitFromCookies(jar({ at_vid: 'v', at_consent: consent(false).slice(11) }), {})).toEqual({});
  });
});

describe('middleware: visit cookies, crawler flag, uncached home HTML', () => {
  it('a first visit gets a session visitor cookie and a 30 minute visit cookie, and the request itself already sees them', () => {
    const res = middleware(req());
    const vid = res.cookies.get('at_vid');
    const visit = res.cookies.get('at_visit');
    expect(vid?.value).toMatch(/^[0-9a-f]{32}$/);
    expect(vid?.maxAge).toBeUndefined();
    expect(vid?.httpOnly).toBe(true);
    expect(visit?.maxAge).toBe(1800);
    expect(visit?.value).not.toBe(vid?.value);
    expect(res.headers.get('x-middleware-request-cookie') ?? '').toContain(`at_vid=${vid?.value}`);
  });

  it('two first visits get different visit ids (so different pages); a returning page load keeps the visitor and slides the window', () => {
    const a = middleware(req());
    const b = middleware(req());
    expect(a.cookies.get('at_visit')?.value).not.toBe(b.cookies.get('at_visit')?.value);
    const again = middleware(req(`at_vid=${a.cookies.get('at_vid')?.value}; at_visit=${a.cookies.get('at_visit')?.value}`));
    expect(again.cookies.get('at_vid')).toBeUndefined();
    expect(again.cookies.get('at_visit')?.value).toBe(a.cookies.get('at_visit')?.value);
    expect(again.cookies.get('at_visit')?.maxAge).toBe(1800);
  });

  it('crawlers are flagged for the server and get no cookies', () => {
    const res = middleware(req('', { 'user-agent': 'Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)' }));
    expect(res.cookies.get('at_vid')).toBeUndefined();
    expect(res.cookies.get('at_visit')).toBeUndefined();
    expect(res.headers.get('x-middleware-request-x-crawler')).toBe('1');
  });

  it('a client cannot fake the crawler flag, and non-page requests start no visit', () => {
    expect(middleware(req('', { 'x-crawler': '1' })).headers.get('x-middleware-request-x-crawler')).toBeNull();
    expect(middleware(new NextRequest('https://shop.test/some/data', { headers: { accept: 'application/json' } })).cookies.get('at_visit')).toBeUndefined();
  });

  it('a shopper who refused Functional cookies gets no visit and an old one is cleared', () => {
    const res = middleware(req(`${consent(false)}; at_vid=old; at_visit=old`));
    expect(res.cookies.get('at_vid')?.value).toBe('');
    expect(res.cookies.get('at_visit')?.value).toBe('');
  });

  it('the home HTML is never cached; other pages keep the framework default', () => {
    expect(middleware(req()).headers.get('Cache-Control')).toBe('private, no-store');
    expect(middleware(req('', {}, '/help')).headers.get('Cache-Control')).toBeNull();
  });
});
