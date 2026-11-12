import { runInNewContext } from 'node:vm';

import { describe, expect, it } from 'vitest';

import { COMMON_NEVER_CACHE, defaultServiceWorkerConfig, SERVICE_WORKER_HEADERS, serviceWorkerSource } from '../src';

/* The tests run the GENERATED worker source in a sandbox with a fake CacheStorage, fetch and event system, so they pin
   the rules that really ship (not a copy of them). */

interface FakeResponse {
  status: number;
  type: string;
  headers: Headers;
  body: string;
  clone: () => FakeResponse;
}

function res(body: string, init: { status?: number; type?: string; headers?: Record<string, string> } = {}): FakeResponse {
  const r: FakeResponse = { status: init.status ?? 200, type: init.type ?? 'basic', headers: new Headers(init.headers), body, clone: () => r };
  return r;
}

class FakeCache {
  readonly entries = new Map<string, FakeResponse>();
  match(req: { url: string } | string): Promise<FakeResponse | undefined> {
    return Promise.resolve(this.entries.get(typeof req === 'string' ? new URL(req, ORIGIN).href : req.url));
  }
  put(req: { url: string }, response: FakeResponse): Promise<void> {
    this.entries.set(req.url, response);
    return Promise.resolve();
  }
  add(req: { url: string }): Promise<void> {
    this.entries.set(req.url, res('offline page'));
    return Promise.resolve();
  }
  keys(): Promise<Array<{ url: string }>> {
    return Promise.resolve([...this.entries.keys()].map((url) => ({ url })));
  }
  delete(req: { url: string }): Promise<boolean> {
    return Promise.resolve(this.entries.delete(req.url));
  }
}

const ORIGIN = 'https://www.example.test';

type Handler = (event: Record<string, unknown>) => void;

function load(overrides: Parameters<typeof defaultServiceWorkerConfig>[0] = { cachePrefix: 'at-test' }) {
  const store = new Map<string, FakeCache>();
  const handlers = new Map<string, Handler>();
  const network: string[] = [];
  let online = true;
  const caches = {
    open: (name: string) => {
      let c = store.get(name);
      if (!c) store.set(name, (c = new FakeCache()));
      return Promise.resolve(c);
    },
    keys: () => Promise.resolve([...store.keys()]),
    delete: (name: string) => Promise.resolve(store.delete(name))
  };
  const state = { skipWaiting: 0, claimed: 0 };
  const self = {
    location: { origin: ORIGIN },
    addEventListener: (type: string, fn: Handler) => handlers.set(type, fn),
    skipWaiting: () => {
      state.skipWaiting++;
      return Promise.resolve();
    },
    clients: { claim: () => ((state.claimed += 1), Promise.resolve()) }
  };
  const fetchFn = (req: { url: string }) => {
    network.push(req.url);
    return online ? Promise.resolve(res(`network ${req.url}`)) : Promise.reject(new TypeError('offline'));
  };
  class FakeRequest {
    readonly url: string;
    constructor(url: string) {
      this.url = new URL(url, ORIGIN).href;
    }
  }
  runInNewContext(serviceWorkerSource(defaultServiceWorkerConfig(overrides)), { self, caches, fetch: fetchFn, Request: FakeRequest, Response: class {}, URL, Promise, JSON, RegExp });

  const dispatchFetch = async (path: string, init: { method?: string; mode?: string; headers?: Record<string, string> } = {}): Promise<FakeResponse | 'passthrough'> => {
    const request = { url: new URL(path, ORIGIN).href, method: init.method ?? 'GET', mode: init.mode ?? 'no-cors', headers: new Headers(init.headers) };
    let responded: Promise<FakeResponse> | null = null;
    handlers.get('fetch')?.({ request, respondWith: (p: Promise<FakeResponse>) => (responded = p) });
    return responded ? await (responded as Promise<FakeResponse>) : 'passthrough';
  };
  const lifecycle = async (type: 'install' | 'activate') => {
    let work: Promise<unknown> = Promise.resolve();
    handlers.get(type)?.({ waitUntil: (p: Promise<unknown>) => (work = p) });
    await work;
  };
  return { store, network, state, dispatchFetch, lifecycle, setOnline: (v: boolean) => (online = v) };
}

describe('service worker rules', () => {
  it('precaches only the offline page at install and takes over at once', async () => {
    const sw = load();
    await sw.lifecycle('install');
    expect([...sw.store.keys()]).toEqual(['at-test-offline-1']);
    expect([...(sw.store.get('at-test-offline-1')?.entries.keys() ?? [])]).toEqual([`${ORIGIN}/offline`]);
    expect(sw.state.skipWaiting).toBe(1);
  });

  it('serves immutable static assets cache-first: one network fetch, then the cache', async () => {
    const sw = load();
    const path = '/_next/static/chunks/app-1a2b3c.js';
    const first = await sw.dispatchFetch(path);
    expect(first).toMatchObject({ body: `network ${ORIGIN}${path}` });
    sw.setOnline(false);
    const second = await sw.dispatchFetch(path);
    expect(second).toMatchObject({ body: `network ${ORIGIN}${path}` });
    expect(sw.network).toHaveLength(1);
    sw.setOnline(true);
    expect(await sw.dispatchFetch('/icons/icon-192.png')).not.toBe('passthrough');
    expect(await sw.dispatchFetch('/logo-mark.svg')).not.toBe('passthrough');
  });

  it.each(['/api/csp-report', '/bff/v1/me', '/auth/callback', '/account/security', '/account', '/cart', '/checkout/pay', '/orders/AT-1', '/membership', '/sell', '/_next/data/x/y.json', '/_next/image?url=a'])(
    'never intercepts %s',
    async (path) => {
      const sw = load();
      expect(await sw.dispatchFetch(path)).toBe('passthrough');
      expect([...sw.store.keys()]).toEqual([]);
    }
  );

  it('does not intercept non-GET requests, other origins, range requests or RSC payloads', async () => {
    const sw = load();
    expect(await sw.dispatchFetch('/_next/static/a.js', { method: 'POST' })).toBe('passthrough');
    expect(await sw.dispatchFetch('/_next/static/a.js', { headers: { Range: 'bytes=0-9' } })).toBe('passthrough');
    expect(await sw.dispatchFetch('/p/shoe?_rsc=abc')).toBe('passthrough');
    expect(await sw.dispatchFetch('/p/shoe', { headers: { RSC: '1' } })).toBe('passthrough');
    expect(await sw.dispatchFetch('/_next/static/a.js', { headers: { Authorization: 'Bearer x' } })).toBe('passthrough');
    expect(await sw.dispatchFetch('/p/shoe')).toBe('passthrough');
    expect(await sw.dispatchFetch('/s?q=rice')).toBe('passthrough');
    expect(await sw.dispatchFetch('https://cdn.other.test/_next/static/a.js')).toBe('passthrough');
  });

  it('page navigations always hit the network; only a failed fetch shows the offline page, and pages are never stored', async () => {
    const sw = load();
    await sw.lifecycle('install');
    const online = await sw.dispatchFetch('/account/orders', { mode: 'navigate' });
    expect(online).toMatchObject({ body: `network ${ORIGIN}/account/orders` });
    sw.setOnline(false);
    const offline = await sw.dispatchFetch('/account/orders', { mode: 'navigate' });
    expect(offline).toMatchObject({ body: 'offline page' });
    expect([...sw.store.keys()]).toEqual(['at-test-offline-1']);
    expect(sw.store.get('at-test-offline-1')?.entries.size).toBe(1);
  });

  it('does not store private, no-store, partial or cross-origin-typed responses', async () => {
    const cases: Array<[string, FakeResponse]> = [
      ['/_next/static/private.js', res('x', { headers: { 'Cache-Control': 'private, max-age=60' } })],
      ['/_next/static/nostore.js', res('x', { headers: { 'Cache-Control': 'no-store' } })],
      ['/_next/static/vary.js', res('x', { headers: { Vary: '*' } })],
      ['/_next/static/error.js', res('x', { status: 404 })],
      ['/_next/static/partial.js', res('x', { status: 206 })],
      ['/_next/static/opaque.js', res('x', { type: 'opaqueredirect' })]
    ];
    for (const [path, response] of cases) {
      const probe = loadWith(response);
      await probe.dispatchFetch(path);
      expect([...probe.store.get('at-test-static-1')?.entries.keys() ?? []], path).toEqual([]);
    }
  });

  it('keeps the static cache under its entry limit, dropping the oldest', async () => {
    const sw = load({ cachePrefix: 'at-test', maxStaticEntries: 3 });
    for (let i = 0; i < 5; i++) await sw.dispatchFetch(`/_next/static/c${i}.js`);
    const keys = [...(sw.store.get('at-test-static-1')?.entries.keys() ?? [])];
    expect(keys).toEqual([`${ORIGIN}/_next/static/c2.js`, `${ORIGIN}/_next/static/c3.js`, `${ORIGIN}/_next/static/c4.js`]);
  });

  it('cleans up caches of an older version (and only its own prefix) on activate', async () => {
    const sw = load({ cachePrefix: 'at-test', version: '2' });
    for (const name of ['at-test-static-1', 'at-test-offline-1', 'at-test-static-2', 'at-test-offline-2', 'other-app-static-1']) await sw.store.set(name, new FakeCache());
    await sw.lifecycle('activate');
    expect([...sw.store.keys()].sort()).toEqual(['at-test-offline-2', 'at-test-static-2', 'other-app-static-1']);
    expect(sw.state.claimed).toBe(1);
  });

  it('the never-list covers every personal area and is part of the shipped source', () => {
    for (const p of ['/api', '/bff', '/auth', '/account', '/cart', '/checkout', '/orders']) expect(COMMON_NEVER_CACHE).toContain(p);
    const src = serviceWorkerSource(defaultServiceWorkerConfig({ cachePrefix: 'x' }));
    expect(src).toContain('"/checkout"');
  });

  it('is served as JavaScript and never cached for long', () => {
    expect(SERVICE_WORKER_HEADERS['Content-Type']).toMatch(/^text\/javascript/);
    expect(SERVICE_WORKER_HEADERS['Cache-Control']).toMatch(/no-cache/);
    expect(SERVICE_WORKER_HEADERS['Cache-Control']).not.toMatch(/max-age=[1-9]/);
  });
});

/** A worker whose network answers every request with the given response. */
function loadWith(response: FakeResponse) {
  const store = new Map<string, FakeCache>();
  const handlers = new Map<string, Handler>();
  const caches = {
    open: (name: string) => {
      let c = store.get(name);
      if (!c) store.set(name, (c = new FakeCache()));
      return Promise.resolve(c);
    },
    keys: () => Promise.resolve([...store.keys()]),
    delete: (name: string) => Promise.resolve(store.delete(name))
  };
  const self = { location: { origin: ORIGIN }, addEventListener: (type: string, fn: Handler) => handlers.set(type, fn), skipWaiting: () => Promise.resolve(), clients: { claim: () => Promise.resolve() } };
  runInNewContext(serviceWorkerSource(defaultServiceWorkerConfig({ cachePrefix: 'at-test' })), { self, caches, fetch: () => Promise.resolve(response), Request: class {}, Response: class {}, URL, Promise, JSON, RegExp });
  return {
    store,
    dispatchFetch: async (path: string) => {
      let responded: Promise<unknown> | null = null;
      handlers.get('fetch')?.({ request: { url: new URL(path, ORIGIN).href, method: 'GET', mode: 'no-cors', headers: new Headers() }, respondWith: (p: Promise<unknown>) => (responded = p) });
      if (responded) await responded;
    }
  };
}
