import { afterEach, describe, expect, it, vi } from 'vitest';

vi.mock('server-only', () => ({}));
// Run the real route handlers against the in-process mock gateway (the same handlers the e2e build uses), as if on prod.
vi.mock('@/lib/config', () => ({ mocksEnabled: () => true, bffOrigin: () => 'http://localhost:8200' }));

const ORIGIN = 'https://afrotransact.com';
const req = (path: string) => new Request(`${ORIGIN}${path}`, { headers: { host: 'afrotransact.com' } });

describe('sitemap routes on prod, generated from the (mock) gateway', () => {
  afterEach(() => vi.unstubAllEnvs());

  it('serves a well-formed index and children that pass the sitemap checks; cacheable; never lists private or facet URLs', async () => {
    vi.stubEnv('APP_ENV', 'prod');
    vi.stubEnv('PUBLIC_WEB_URL', ORIGIN);
    const { GET: index } = await import('@/app/sitemap.xml/route');
    const { GET: child } = await import('@/app/sitemaps/[name]/route');
    const res = await index(req('/sitemap.xml'));
    expect(res.status).toBe(200);
    expect(res.headers.get('content-type')).toContain('application/xml');
    expect(res.headers.get('cache-control')).toMatch(/s-maxage=\d+/);
    const doc = new DOMParser().parseFromString(await res.text(), 'application/xml');
    expect(doc.querySelector('parsererror')).toBeNull();
    const locs = [...doc.getElementsByTagName('loc')].map((l) => l.textContent!);
    expect(locs.length).toBeGreaterThan(0);
    for (const loc of locs) {
      expect(loc).toMatch(new RegExp(`^${ORIGIN}/sitemaps/(products|categories|stores|services|content)-\\d+\\.xml$`));
      const c = await child(req(new URL(loc).pathname), { params: Promise.resolve({ name: new URL(loc).pathname.split('/').pop()! }) });
      expect(c.status, loc).toBe(200);
      const cdoc = new DOMParser().parseFromString(await c.text(), 'application/xml');
      expect(cdoc.querySelector('parsererror'), loc).toBeNull();
      const urls = [...cdoc.getElementsByTagName('url')].map((u) => u.getElementsByTagName('loc')[0]!.textContent!);
      expect(urls.length).toBeGreaterThan(0);
      expect(urls.length).toBeLessThanOrEqual(50_000);
      for (const u of urls) {
        expect(u.startsWith(ORIGIN), u).toBe(true);
        expect(new URL(u).pathname, u).not.toMatch(/^\/(cart|checkout|account|orders|signin|signup|api)/);
        // /s is listed only as a category page: exactly one of dept / community
        if (new URL(u).pathname === '/s') expect([...new URL(u).searchParams.keys()].filter((k) => k !== 'page').sort().join(), u).toMatch(/^(dept|community)$/);
        for (const k of ['q', 'sort', 'price', 'rating', 'fulfilment', 'soldBy', 'delivery']) expect(new URL(u).searchParams.has(k), u).toBe(false);
      }
    }
    // unknown children are 404, not an empty 200
    expect((await child(req('/sitemaps/products-999.xml'), { params: Promise.resolve({ name: 'products-999.xml' }) })).status).toBe(404);
    expect((await child(req('/sitemaps/..%2Fx.xml'), { params: Promise.resolve({ name: '../x.xml' }) })).status).toBe(404);
  }, 60_000);

  it('prod robots.txt names the index; a request on another host gets Disallow: /', async () => {
    vi.stubEnv('APP_ENV', 'prod');
    vi.stubEnv('PUBLIC_WEB_URL', ORIGIN);
    const { GET } = await import('@/app/robots.txt/route');
    expect(await (await GET(req('/robots.txt'))).text()).toContain(`Sitemap: ${ORIGIN}/sitemap.xml`);
    const other = await GET(new Request('https://storefront-prod.acme.workers.dev/robots.txt', { headers: { host: 'storefront-prod.acme.workers.dev' } }));
    expect(await other.text()).toBe('User-agent: *\nDisallow: /\n');
  });
});
