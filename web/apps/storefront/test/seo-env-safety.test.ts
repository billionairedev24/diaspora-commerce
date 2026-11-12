import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

import { NextRequest } from 'next/server';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { pageMetadata } from '@/lib/seo/metadata';
import { indexingPolicy, NOINDEX_HEADER, robotsHeaderFor, wwwRedirectTarget } from '@/lib/seo/policy';
import { buildRobotsTxt } from '@/lib/seo/robots';
import { middleware } from '@/middleware';

import { robotsAllows } from './robotsMatcher';

vi.mock('server-only', () => ({}));

/* CI guard: a non-prod build can NEVER emit an indexable page. Hosts come from infra/cloudflare/hosts.json (the one host map),
   the Worker's environments from wrangler.jsonc; nothing here names a host by hand. */

const read = (rel: string) => readFileSync(fileURLToPath(new URL(rel, import.meta.url)), 'utf8');
const map = JSON.parse(read('../../../../infra/cloudflare/hosts.json')) as { zone: string; envs: Record<string, { suffix: string; zone?: string }>; roles: string[]; other_roles: string[] };

const storefrontHost = (env: string) => {
  const c = map.envs[env]!;
  const zone = c.zone ?? map.zone;
  return c.suffix === '' ? zone : `${c.suffix}.${zone}`;
};
const allHosts = (env: string): string[] => {
  const c = map.envs[env]!;
  const zone = c.zone ?? map.zone;
  return [storefrontHost(env), ...[...map.roles, ...map.other_roles].map((r) => (c.suffix === '' ? `${r}.${zone}` : `${r}-${c.suffix}.${zone}`))];
};

/** wrangler.jsonc is JSONC: strip comments outside strings (URLs contain //). */
function parseJsonc(text: string): Record<string, unknown> {
  let out = '';
  let inString = false;
  for (let i = 0; i < text.length; i++) {
    const c = text[i]!;
    if (inString) {
      out += c;
      if (c === '\\') out += text[++i] ?? '';
      else if (c === '"') inString = false;
    } else if (c === '"') {
      inString = true;
      out += c;
    } else if (c === '/' && text[i + 1] === '/') {
      while (i < text.length && text[i] !== '\n') i++;
      out += '\n';
    } else {
      out += c;
    }
  }
  return JSON.parse(out.replace(/,(\s*[}\]])/g, '$1')) as Record<string, unknown>;
}

interface Block {
  vars: Record<string, string>;
  routes?: { pattern: string }[];
}
const wrangler = parseJsonc(read('../wrangler.jsonc')) as unknown as Block & { env: Record<string, Block> };
/** wrangler environment -> host-map environment */
const blocks: Record<string, { block: Block; env: string }> = {
  dev: { block: wrangler, env: 'dev' },
  preview: { block: wrangler.env['preview']!, env: 'dev' },
  uat: { block: wrangler.env['uat']!, env: 'uat' },
  prod: { block: wrangler.env['prod']!, env: 'prod' },
  trial: { block: wrangler.env['trial']!, env: 'trial' }
};

describe('wrangler.jsonc against the host map', () => {
  it.each(Object.entries(blocks))('%s: APP_ENV is set and PUBLIC_WEB_URL is the environment’s storefront host', (name, { block, env }) => {
    expect(block.vars['APP_ENV'], `${name} has no APP_ENV`).toBeTruthy();
    expect(new URL(block.vars['PUBLIC_WEB_URL']!).host).toBe(storefrontHost(env));
    for (const r of block.routes ?? []) expect(allHosts(env)).toContain(r.pattern.replace(/\/\*$/, ''));
  });

  it('only the prod Worker says APP_ENV=prod (uat, dev, previews and the 8-day trial on test.<zone> never do)', () => {
    for (const [name, { block }] of Object.entries(blocks)) expect(block.vars['APP_ENV'] === 'prod', name).toBe(name === 'prod');
  });
});

describe('indexing policy: every host of every environment', () => {
  const envOf = (name: string) => ({ APP_ENV: blocks[name]!.block.vars['APP_ENV'], PUBLIC_WEB_URL: blocks[name]!.block.vars['PUBLIC_WEB_URL'] });
  const hostsToTry = ['storefront-dev.example.workers.dev', 'x.workers.dev', `www.${map.zone}`, 'localhost:3000', '', ...Object.keys(map.envs).flatMap(allHosts)];

  it.each(Object.keys(blocks))('Worker %s: indexable only on prod’s own storefront host', (name) => {
    for (const host of hostsToTry) {
      const p = indexingPolicy(envOf(name), host);
      const expected = name === 'prod' && host === storefrontHost('prod');
      expect(p.indexable, `${name} on ${host}`).toBe(expected);
    }
  });

  it('every non-prod Worker answers every path with X-Robots-Tag: noindex', () => {
    for (const name of Object.keys(blocks).filter((n) => n !== 'prod')) {
      for (const host of allHosts(blocks[name]!.env)) {
        for (const path of ['/', '/p/AT-1', '/s?dept=Fashion', '/help', '/robots.txt', '/sitemap.xml', '/_next/static/chunks/a.js', '/api/bff/v1/home', '/does/not/exist']) {
          expect(robotsHeaderFor(indexingPolicy(envOf(name), host), path), `${name} ${host}${path}`).toBe(NOINDEX_HEADER);
        }
      }
    }
  });

  it('fails closed: a missing, odd or look-alike APP_ENV is noindex', () => {
    const url = `https://${storefrontHost('prod')}`;
    for (const appEnv of [undefined, '', 'production', 'PRODUCTION', 'prod-eu', 'trial', 'uat', 'dev', 'preview', 'local', 'true', '1']) {
      expect(indexingPolicy({ APP_ENV: appEnv, PUBLIC_WEB_URL: url }, storefrontHost('prod')).indexable, String(appEnv)).toBe(false);
    }
    expect(indexingPolicy({ APP_ENV: 'prod' }, storefrontHost('prod')).indexable).toBe(false);
    expect(indexingPolicy({ APP_ENV: 'prod', PUBLIC_WEB_URL: 'not a url' }, storefrontHost('prod')).indexable).toBe(false);
    expect(indexingPolicy({ APP_ENV: 'prod', PUBLIC_WEB_URL: url }, undefined).indexable).toBe(false);
    // Prod itself is case-insensitive about the env name only (a wrangler typo like "Prod" must not leave prod noindexed forever).
    expect(indexingPolicy({ APP_ENV: ' Prod ', PUBLIC_WEB_URL: url }, storefrontHost('prod')).indexable).toBe(true);
  });

  it('prod: private paths are noindex, public pages carry no X-Robots-Tag', () => {
    const p = indexingPolicy(envOf('prod'), storefrontHost('prod'));
    for (const path of ['/cart', '/checkout', '/checkout/complete', '/account', '/account/orders', '/orders/AT-1', '/signin', '/api/bff/v1/me', '/api/csp-report', '/back-office/seller', '/sell', '/gift-cards', '/offline']) {
      expect(robotsHeaderFor(p, path), path).toBe(NOINDEX_HEADER);
    }
    for (const path of ['/', '/p/AT-1', '/s?dept=Fashion', '/help/shipping', '/services/abc', '/cartography', '/accountants']) expect(robotsHeaderFor(p, path), path).toBeUndefined();
  });

  it('prod: www.<host> is 301-redirected to the one canonical host; nothing else is', () => {
    const env = envOf('prod');
    expect(wwwRedirectTarget(env, new URL(`https://www.${storefrontHost('prod')}/p/AT-1?x=1`))?.toString()).toBe(`https://${storefrontHost('prod')}/p/AT-1?x=1`);
    expect(wwwRedirectTarget(env, new URL(`https://${storefrontHost('prod')}/`))).toBeUndefined();
    expect(wwwRedirectTarget(envOf('trial'), new URL(`https://www.${storefrontHost('trial')}/`))).toBeUndefined();
  });
});

describe('robots.txt', () => {
  const prod = indexingPolicy({ APP_ENV: 'prod', PUBLIC_WEB_URL: `https://${storefrontHost('prod')}` }, storefrontHost('prod'));

  it('every non-prod host: Disallow: / and no Sitemap line', () => {
    for (const name of ['dev', 'uat', 'trial'] as const) {
      const env = { APP_ENV: blocks[name]!.block.vars['APP_ENV'], PUBLIC_WEB_URL: blocks[name]!.block.vars['PUBLIC_WEB_URL'] };
      const txt = buildRobotsTxt(indexingPolicy(env, storefrontHost(name)));
      expect(txt).toBe('User-agent: *\nDisallow: /\n');
      expect(txt).not.toMatch(/sitemap/i);
      expect(robotsAllows(txt, '/')).toBe(false);
      expect(robotsAllows(txt, '/p/AT-1')).toBe(false);
    }
  });

  it('prod: allows pages, /_next/static, images and category pages; blocks cart, checkout, account, search facets and tracking params', () => {
    const txt = buildRobotsTxt(prod);
    for (const path of ['/', '/p/AT-1', '/s?dept=Fashion', '/s?community=Yoruba&page=2', '/help/shipping', '/_next/static/chunks/main-abc.js', '/_next/static/css/app.css', '/_next/image?url=%2Fa.jpg&w=640&q=75', '/icons/icon-512.png', '/logo-mark.svg', '/sitemap.xml', '/sitemaps/products-1.xml']) {
      expect(robotsAllows(txt, path), path).toBe(true);
    }
    for (const path of ['/cart', '/checkout', '/checkout/complete', '/account', '/account/addresses', '/orders/AT-1', '/signin', '/api/bff/v1/me', '/s?q=jollof', '/s?dept=Fashion&sort=price_asc', '/s?dept=Fashion&price=u10', '/s?community=Igbo&rating=4', '/p/AT-1?utm_source=news', '/?gclid=abc', '/search?q=x']) {
      expect(robotsAllows(txt, path), path).toBe(false);
    }
    expect(txt).toContain(`Sitemap: https://${storefrontHost('prod')}/sitemap.xml`);
  });
});

describe('every response of a non-prod Worker carries noindex (middleware)', () => {
  afterEach(() => vi.unstubAllEnvs());

  it('dev, uat, trial and preview: X-Robots-Tag on pages, 404s, routes and the BFF proxy; prod only on private paths', () => {
    for (const [name, { block, env }] of Object.entries(blocks)) {
      vi.stubEnv('APP_ENV', block.vars['APP_ENV']!);
      vi.stubEnv('PUBLIC_WEB_URL', block.vars['PUBLIC_WEB_URL']!);
      for (const path of ['/', '/p/AT-1', '/robots.txt', '/sitemap.xml', '/api/bff/v1/home', '/no/such/page', '/cart']) {
        const res = middleware(new NextRequest(`https://${storefrontHost(env)}${path}`, { headers: { host: storefrontHost(env) } }));
        const tag = res.headers.get('X-Robots-Tag');
        if (name === 'prod' && !['/cart', '/api/bff/v1/home'].includes(path)) expect(tag, `${name}${path}`).toBeNull();
        else expect(tag, `${name}${path}`).toMatch(/noindex/);
      }
    }
  });

  it('a Worker reached through a host that is not its own (workers.dev, a preview URL) is noindex even in prod', () => {
    vi.stubEnv('APP_ENV', 'prod');
    vi.stubEnv('PUBLIC_WEB_URL', blocks['prod']!.block.vars['PUBLIC_WEB_URL']!);
    const res = middleware(new NextRequest('https://storefront-prod.acme.workers.dev/', { headers: { host: 'storefront-prod.acme.workers.dev' } }));
    expect(res.headers.get('X-Robots-Tag')).toMatch(/noindex/);
  });
});

describe('metadata never makes a page indexable on a non-prod host', () => {
  it('pageMetadata: index:true is overridden to noindex by the policy', () => {
    const nonProd = indexingPolicy({ APP_ENV: 'uat', PUBLIC_WEB_URL: 'https://uat.afrotransact.com' }, 'uat.afrotransact.com');
    const md = pageMetadata({ title: 'Jollof rice', canonicalPath: '/p/AT-1', index: true }, nonProd);
    expect(md.robots).toEqual({ index: false, follow: false });
    const prod = indexingPolicy({ APP_ENV: 'prod', PUBLIC_WEB_URL: 'https://afrotransact.com' }, 'afrotransact.com');
    expect(pageMetadata({ title: 'Jollof rice', canonicalPath: '/p/AT-1', index: true }, prod).robots).toMatchObject({ index: true });
    expect(pageMetadata({ title: 'Empty', canonicalPath: '/s?dept=X', index: false }, prod).robots).toEqual({ index: false, follow: false });
  });
});

describe('route handlers: only prod serves robots.txt allow rules and a sitemap', () => {
  afterEach(() => vi.unstubAllEnvs());

  it('non-prod: robots.txt disallows all; sitemap index, children and revalidate are 404 with noindex', async () => {
    vi.stubEnv('APP_ENV', 'trial');
    vi.stubEnv('PUBLIC_WEB_URL', 'https://test.afrotransact.com');
    vi.stubEnv('SEO_REVALIDATE_TOKEN', 'secret');
    const get = (path: string) => new Request(`https://test.afrotransact.com${path}`, { headers: { host: 'test.afrotransact.com' } });
    const robots = await (await import('@/app/robots.txt/route')).GET(get('/robots.txt'));
    expect(await robots.text()).toBe('User-agent: *\nDisallow: /\n');
    expect(robots.headers.get('X-Robots-Tag')).toMatch(/noindex/);
    const index = await (await import('@/app/sitemap.xml/route')).GET(get('/sitemap.xml'));
    expect(index.status).toBe(404);
    const child = await (await import('@/app/sitemaps/[name]/route')).GET(get('/sitemaps/products-1.xml'), { params: Promise.resolve({ name: 'products-1.xml' }) });
    expect(child.status).toBe(404);
    const reval = await (await import('@/app/api/seo/revalidate/route')).POST(new Request('https://test.afrotransact.com/api/seo/revalidate', { method: 'POST', headers: { host: 'test.afrotransact.com', authorization: 'Bearer secret' } }));
    expect(reval.status).toBe(404);
  });

  it('the zone’s edge rule adds X-Robots-Tag to every non-prod host and the prod back-office/api hosts, from hosts.json', () => {
    const tf = read('../../../../infra/cloudflare/zone.tf');
    expect(tf).toMatch(/x-robots-tag/i);
    expect(tf).toContain('env_all_hosts');
    expect(tf).toMatch(/noindex/);
  });
});
