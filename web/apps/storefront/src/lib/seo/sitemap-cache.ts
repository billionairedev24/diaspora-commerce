import { countUrls, SITEMAP_KINDS, type SitemapKind, type SitemapSet } from './sitemap';
import { type BuildResult } from './sitemap-source';

/* Keeps the generated sitemap set in memory (one Worker isolate), regenerated when it is older than the TTL (the scheduled
   regeneration: the next crawler hit after the TTL rebuilds it) or when `invalidate()` is called (regenerate on change).
   Concurrent requests share one build (single flight). A failing or suspiciously small kind keeps its previous version
   (stale-if-error) and is reported, so a catalog or gateway outage never empties the sitemap Google reads. */

export type SeoEvent =
  | { event: 'sitemap.generated'; urls: number; perKind: Partial<Record<SitemapKind, number>>; truncated: boolean }
  | { event: 'sitemap.kind_failed'; kind: SitemapKind; keptPrevious: boolean }
  | { event: 'sitemap.count_drop'; kind: SitemapKind; previous: number; current: number; keptPrevious: boolean };

export interface SitemapCacheOptions {
  build: () => Promise<BuildResult>;
  ttlMs: number;
  now?: () => number;
  report?: (e: SeoEvent) => void;
  /** A kind that shrinks below this share of its previous size is held back (default 0.5)... */
  dropRatio?: number;
  /** ...for at most this many generations in a row (default 3), after which the smaller set is accepted as real. */
  maxHeldBack?: number;
  /** Below this previous size a shrink is never suspicious (default 20 URLs). */
  dropFloor?: number;
}

export interface SitemapCache {
  get(): Promise<SitemapSet>;
  invalidate(): void;
}

const kindCount = (set: SitemapSet | undefined, kind: SitemapKind): number => (set?.kinds[kind] ?? []).reduce((n, c) => n + c.urls.length, 0);

export function createSitemapCache(o: SitemapCacheOptions): SitemapCache {
  const now = o.now ?? Date.now;
  const report = o.report ?? (() => undefined);
  const dropRatio = o.dropRatio ?? 0.5;
  const maxHeld = o.maxHeldBack ?? 3;
  const dropFloor = o.dropFloor ?? 20;
  let current: SitemapSet | undefined;
  let builtAt = 0;
  let inflight: Promise<SitemapSet> | undefined;
  const held: Partial<Record<SitemapKind, number>> = {};

  const merge = (prev: SitemapSet | undefined, r: BuildResult): SitemapSet => {
    const next: SitemapSet = { generatedAt: r.set.generatedAt, kinds: { ...r.set.kinds } };
    for (const kind of SITEMAP_KINDS) {
      const before = kindCount(prev, kind);
      const after = kindCount(r.set, kind);
      if (r.failed.includes(kind)) {
        const keep = prev?.kinds[kind];
        if (keep) next.kinds[kind] = keep;
        report({ event: 'sitemap.kind_failed', kind, keptPrevious: !!keep });
        continue;
      }
      if (before >= dropFloor && after < before * dropRatio) {
        const n = (held[kind] ?? 0) + 1;
        const keep = n <= maxHeld ? prev?.kinds[kind] : undefined;
        report({ event: 'sitemap.count_drop', kind, previous: before, current: after, keptPrevious: !!keep });
        if (keep) {
          held[kind] = n;
          next.kinds[kind] = keep;
          continue;
        }
      }
      held[kind] = 0;
    }
    return next;
  };

  const rebuild = (): Promise<SitemapSet> => {
    inflight ??= (async () => {
      try {
        const r = await o.build();
        const merged = merge(current, r);
        current = merged;
        builtAt = now();
        const perKind: Partial<Record<SitemapKind, number>> = {};
        for (const k of SITEMAP_KINDS) if (kindCount(merged, k) > 0) perKind[k] = kindCount(merged, k);
        report({ event: 'sitemap.generated', urls: countUrls(merged), perKind, truncated: r.truncated });
        return merged;
      } catch (e) {
        // Whole build failed (gateway down): serve the last good set, otherwise let the route answer 503.
        for (const kind of SITEMAP_KINDS) if (kindCount(current, kind) > 0) report({ event: 'sitemap.kind_failed', kind, keptPrevious: true });
        if (current) {
          // Retry in a minute, not on every crawler hit.
          builtAt = now() - o.ttlMs + Math.min(60_000, o.ttlMs);
          return current;
        }
        throw e;
      } finally {
        inflight = undefined;
      }
    })();
    return inflight;
  };

  return {
    get: () => (current && now() - builtAt < o.ttlMs ? Promise.resolve(current) : rebuild()),
    invalidate: () => {
      builtAt = 0;
    }
  };
}
