/* Polling audit (docs/engineering/session-timeouts.md): a request nobody asked for must send `X-Background: 1`, or a tab
   left open would keep the session alive for ever. Three layers:
   1. every known polling query, built with a recording stand-in for the API, calls it with the background flag;
   2. the flag really becomes the header (and is off by default);
   3. a scan of the apps' and packages' source: `refetchInterval` exists only in the query factories that pass the flag,
      timers that call the BFF carry the header, and nothing refetches on window focus by itself. A new poll that is not
      added here (and flagged) fails the scan. */
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { dirname, join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

import { describe, expect, it } from 'vitest';
import { z } from 'zod';

import { BACKGROUND, pollWhile, sellerQueries, staffQueries } from '../src';
import { BACKGROUND_HEADER, createRequester } from '../src/http';
import { type SellerApiClient } from '../src/seller-client';
import { type StaffApiClient } from '../src/staff-client';
import { staffPreorderQueries,type StaffPreordersApi } from '../src/staff-preorders';

interface Call {
  method: string;
  args: unknown[];
}

/** An API whose every method records its call and answers nothing. */
function recorder<T extends object>(calls: Call[]): T {
  return new Proxy({}, { get: (_t, method: string) => (...args: unknown[]) => {
    calls.push({ method, args });
    return Promise.resolve(undefined);
  } }) as T;
}

const ctx = { signal: new AbortController().signal } as never;
const always = (): number => 1000;

interface Poll {
  name: string;
  /** Builds the query the way a screen does when it polls. */
  build: (calls: Call[]) => { queryFn?: unknown; refetchInterval?: unknown };
  /** The API method it must call. */
  method: string;
}

/** Every query that refreshes by itself. Add new polls here: the scan below fails for any it does not know. */
const POLLS: Poll[] = [
  { name: 'staffQueries.runs', method: 'listRuns', build: (c) => staffQueries.runs(recorder<StaffApiClient>(c)) },
  { name: 'staffQueries.run', method: 'getRun', build: (c) => staffQueries.run(recorder<StaffApiClient>(c), 'run-1') },
  { name: 'staffQueries.exports (polling)', method: 'listExports', build: (c) => staffQueries.exports(recorder<StaffApiClient>(c), always) },
  { name: 'sellerQueries.runPickups', method: 'listRunPickups', build: (c) => sellerQueries.runPickups(recorder<SellerApiClient>(c), 'org-1') },
  { name: 'sellerQueries.runPickup', method: 'getRunPickup', build: (c) => sellerQueries.runPickup(recorder<SellerApiClient>(c), 'org-1', 'run-1') },
  { name: 'sellerQueries.importJob (polling)', method: 'getImport', build: (c) => sellerQueries.importJob(recorder<SellerApiClient>(c), 'org-1', 'imp-1', always) },
  {
    name: 'staffPreorderQueries.orders (polling)',
    method: 'orders',
    build: (c) => staffPreorderQueries.orders(recorder<StaffPreordersApi>(c), 'w-1', { page: 0, size: 20 }, always)
  },
  { name: 'staffPreorderQueries.refunds (polling)', method: 'refunds', build: (c) => staffPreorderQueries.refunds(recorder<StaffPreordersApi>(c), 'w-1', always) }
];

describe('every polling query is flagged as background', () => {
  it.each(POLLS)('$name', async ({ build, method }) => {
    const calls: Call[] = [];
    const options = build(calls);
    expect(options.refetchInterval, 'it must be a polling query').toBeDefined();
    await (options.queryFn as (c: never) => Promise<unknown>)(ctx);
    const call = calls.find((c) => c.method === method);
    expect(call, `${method} was not called`).toBeDefined();
    expect(call?.args.at(-1), `${method} must be called with BACKGROUND as its last argument`).toBe(BACKGROUND);
  });

  it('the same screens are foreground when they do not poll (a click that loads them is the person being active)', async () => {
    const calls: Call[] = [];
    const exportsOptions = staffQueries.exports(recorder<StaffApiClient>(calls));
    expect(exportsOptions.refetchInterval).toBeUndefined();
    await (exportsOptions.queryFn as (c: never) => Promise<unknown>)(ctx);
    expect(calls[0]?.args.at(-1)).toBeUndefined();
  });

  it('a poll rule becomes a refetchInterval that stops when the work is done', () => {
    const options = staffQueries.exports(recorder<StaffApiClient>([]), pollWhile(3000, (d) => d !== undefined));
    const interval = options.refetchInterval as (q: { state: { data: unknown } }) => number | false;
    expect(interval({ state: { data: undefined } })).toBe(false);
    expect(interval({ state: { data: { items: [] } } })).toBe(3000);
  });
});

describe('the flag is the header', () => {
  const headersOf = async (background?: boolean) => {
    let seen: Headers | undefined;
    const request = createRequester({
      baseUrl: '/api/bff',
      fetch: async (req) => {
        seen = req.headers;
        return new Response('{}', { status: 200, headers: { 'content-type': 'application/json' } });
      }
    });
    await request('/v1/x', z.object({}), { background });
    return seen;
  };

  it('is sent as X-Background: 1 for background requests only', async () => {
    expect((await headersOf(true))?.get(BACKGROUND_HEADER)).toBe('1');
    expect((await headersOf(false))?.get(BACKGROUND_HEADER)).toBeNull();
    expect((await headersOf())?.get(BACKGROUND_HEADER)).toBeNull();
  });
});

/* ── Source scan ─────────────────────────────────────────────────────────── */

const WEB = join(dirname(fileURLToPath(import.meta.url)), '..', '..', '..');

function sources(dir: string, out: string[] = []): string[] {
  for (const name of readdirSync(dir)) {
    if (name === 'node_modules' || name === '.next' || name === 'generated' || name === 'dist') continue;
    const full = join(dir, name);
    if (statSync(full).isDirectory()) sources(full, out);
    else if (/\.(ts|tsx)$/.test(name) && !/\.(test|stories)\./.test(name) && !full.includes(`${join('/', 'test', '')}`)) out.push(full);
  }
  return out;
}

const FILES = ['apps', 'packages'].flatMap((root) =>
  readdirSync(join(WEB, root)).flatMap((pkg) => {
    const src = join(WEB, root, pkg, 'src');
    return statSync(join(WEB, root, pkg)).isDirectory() && safeIsDir(src) ? sources(src) : [];
  })
);

function safeIsDir(p: string): boolean {
  try {
    return statSync(p).isDirectory();
  } catch {
    return false;
  }
}

/** Lines of code (comments dropped) that match, as `path:line`. */
function codeMatches(pattern: RegExp): { file: string; line: number; text: string }[] {
  return FILES.flatMap((file) =>
    readFileSync(file, 'utf8')
      .split('\n')
      .map((text, i) => ({ file: relative(WEB, file), line: i + 1, text }))
      .filter(({ text }) => pattern.test(text) && !/^\s*(\/\/|\*|\/\*)/.test(text))
  );
}

describe('source scan', () => {
  it('refetchInterval is set only by the query factories, and each of those passes BACKGROUND', () => {
    const found = codeMatches(/refetchInterval/);
    const files = [...new Set(found.map((m) => m.file))].sort();
    expect(files).toEqual(['packages/api-client/src/query/polling.ts', 'packages/api-client/src/query/seller.ts', 'packages/api-client/src/query/staff.ts']);
    for (const m of found.filter((x) => !x.file.endsWith('polling.ts'))) {
      expect(m.text, `${m.file}:${m.line} polls without BACKGROUND`).toContain('BACKGROUND');
    }
  });

  it('every pollingOf() call sits next to a fetcher that passes BACKGROUND', () => {
    const found = codeMatches(/pollingOf\(/).filter((m) => !m.file.endsWith('polling.ts') && !/^\s*import /.test(m.text));
    expect(found.length).toBeGreaterThanOrEqual(4);
    for (const m of found) {
      const file = readFileSync(join(WEB, m.file), 'utf8').split('\n');
      // the queryOptions call: its text from the line that opens it to the line with pollingOf
      let start = m.line - 1;
      while (start > 0 && !/queryOptions\(/.test(file[start] ?? '') && m.line - start < 12) start -= 1;
      const block = file.slice(start, m.line).join('\n');
      expect(block, `${m.file}:${m.line}: queryFn must pass BACKGROUND when polling`).toMatch(/poll \? BACKGROUND : undefined/);
    }
  });

  it('timers that call the BFF carry X-Background', () => {
    const timers = new Set(codeMatches(/\bsetInterval\(/).map((m) => m.file));
    const calling = [...timers].filter((f) => /\bfetch\(/.test(readFileSync(join(WEB, f), 'utf8')));
    // the maintenance screens re-check /v1/maintenance on a timer
    expect(calling.sort()).toEqual(['apps/seller-center/src/components/shell/MaintenanceScreen.tsx', 'apps/storefront/src/components/shell/MaintenanceScreen.tsx']);
    for (const f of calling) expect(readFileSync(join(WEB, f), 'utf8'), f).toContain('BACKGROUND_HEADER');
  });

  it('nothing refetches on window focus or reconnect by itself', () => {
    expect(codeMatches(/refetchOnWindowFocus:\s*(true|'always')/)).toEqual([]);
    expect(codeMatches(/refetchOnReconnect:\s*(true|'always')/)).toEqual([]);
  });

  it('the apps set no refetchInterval of their own', () => {
    expect(codeMatches(/refetchInterval/).filter((m) => m.file.startsWith('apps/'))).toEqual([]);
  });
});
