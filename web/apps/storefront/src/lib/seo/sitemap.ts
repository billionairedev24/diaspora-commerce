/* Sitemap XML: a sitemap index of chunked child sitemaps (each <= 50,000 URLs and <= 50 MB uncompressed, sitemaps.org),
   image extension entries, optional hreflang alternates. Pure string building: no I/O, fully unit tested. */

export const MAX_URLS_PER_SITEMAP = 50_000;
/** Protocol limit is 50 MB (52,428,800 bytes) uncompressed; stay clear of it. */
export const MAX_BYTES_PER_SITEMAP = 49_000_000;

export interface SitemapImage {
  loc: string;
  title?: string;
}
export interface SitemapAlternate {
  hreflang: string;
  href: string;
}
export interface SitemapUrl {
  loc: string;
  /** W3C date or datetime of a REAL update; omitted when the source cannot say (a wrong lastmod is worse than none). */
  lastmod?: string;
  images?: readonly SitemapImage[];
  alternates?: readonly SitemapAlternate[];
}

export const SITEMAP_KINDS = ['products', 'categories', 'stores', 'services', 'content'] as const;
export type SitemapKind = (typeof SITEMAP_KINDS)[number];

export const escapeXml = (s: string): string =>
  s
    // Characters XML 1.0 cannot carry at all are dropped.
    // eslint-disable-next-line no-control-regex
    .replace(/[\u0000-\u0008\u000B\u000C\u000E-\u001F\uFFFE\uFFFF]/g, '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&apos;');

const encoder = new TextEncoder();
export const byteLength = (s: string): number => encoder.encode(s).length;

/** A valid W3C datetime (or date) as sitemaps want it, from an ISO string or Date; undefined when it is not a real date. */
export function lastmodOf(value: string | Date | null | undefined): string | undefined {
  if (value === null || value === undefined || value === '') return undefined;
  const d = value instanceof Date ? value : new Date(value);
  if (Number.isNaN(d.getTime()) || d.getTime() > Date.now() + 24 * 3600 * 1000) return undefined;
  return d.toISOString();
}

const URLSET_OPEN = '<?xml version="1.0" encoding="UTF-8"?>\n<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9" xmlns:image="http://www.google.com/schemas/sitemap-image/1.1" xmlns:xhtml="http://www.w3.org/1999/xhtml">\n';
const URLSET_CLOSE = '</urlset>\n';

export function renderUrl(u: SitemapUrl): string {
  let x = `<url><loc>${escapeXml(u.loc)}</loc>`;
  if (u.lastmod) x += `<lastmod>${escapeXml(u.lastmod)}</lastmod>`;
  for (const a of u.alternates ?? []) x += `<xhtml:link rel="alternate" hreflang="${escapeXml(a.hreflang)}" href="${escapeXml(a.href)}"/>`;
  // Google reads up to 1,000 images per URL; one main image per page is what we list.
  for (const im of (u.images ?? []).slice(0, 1000)) x += `<image:image><image:loc>${escapeXml(im.loc)}</image:loc>${im.title ? `<image:title>${escapeXml(im.title)}</image:title>` : ''}</image:image>`;
  return `${x}</url>\n`;
}

/** One child sitemap's XML. */
export function renderUrlset(urls: readonly SitemapUrl[]): string {
  return URLSET_OPEN + urls.map(renderUrl).join('') + URLSET_CLOSE;
}

export interface Chunk {
  urls: SitemapUrl[];
  xml: string;
  bytes: number;
}

/** Splits URLs into child sitemaps of at most `maxUrls` URLs and `maxBytes` bytes each, keeping order; every chunk is rendered. */
export function chunkUrls(urls: readonly SitemapUrl[], limits: { maxUrls?: number; maxBytes?: number } = {}): Chunk[] {
  const maxUrls = limits.maxUrls ?? MAX_URLS_PER_SITEMAP;
  const maxBytes = limits.maxBytes ?? MAX_BYTES_PER_SITEMAP;
  const overhead = byteLength(URLSET_OPEN) + byteLength(URLSET_CLOSE);
  const out: Chunk[] = [];
  let cur: SitemapUrl[] = [];
  let parts: string[] = [];
  let bytes = overhead;
  const flush = () => {
    if (cur.length === 0) return;
    out.push({ urls: cur, xml: URLSET_OPEN + parts.join('') + URLSET_CLOSE, bytes });
    cur = [];
    parts = [];
    bytes = overhead;
  };
  for (const u of urls) {
    const xml = renderUrl(u);
    const size = byteLength(xml);
    if (cur.length >= maxUrls || (cur.length > 0 && bytes + size > maxBytes)) flush();
    cur.push(u);
    parts.push(xml);
    bytes += size;
  }
  flush();
  return out;
}

export interface IndexEntry {
  loc: string;
  lastmod?: string;
}

export function renderIndex(entries: readonly IndexEntry[]): string {
  const body = entries.map((e) => `<sitemap><loc>${escapeXml(e.loc)}</loc>${e.lastmod ? `<lastmod>${escapeXml(e.lastmod)}</lastmod>` : ''}</sitemap>\n`).join('');
  return `<?xml version="1.0" encoding="UTF-8"?>\n<sitemapindex xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">\n${body}</sitemapindex>\n`;
}

/** The file name of one child sitemap: products-1.xml. */
export const childName = (kind: SitemapKind, n: number): string => `${kind}-${n}.xml`;

const CHILD_NAME = /^(products|categories|stores|services|content)-([1-9][0-9]{0,5})\.xml$/;
export function parseChildName(name: string): { kind: SitemapKind; n: number } | undefined {
  const m = CHILD_NAME.exec(name);
  if (!m) return undefined;
  return { kind: m[1] as SitemapKind, n: Number(m[2]) };
}

export interface SitemapSet {
  generatedAt: string;
  /** Chunks per kind; a kind with no URLs has no entry (it is not listed in the index). */
  kinds: Partial<Record<SitemapKind, Chunk[]>>;
}

/** The newest real lastmod of a chunk, for the index entry. */
export function chunkLastmod(c: Chunk): string | undefined {
  let best: string | undefined;
  for (const u of c.urls) if (u.lastmod && (!best || u.lastmod > best)) best = u.lastmod;
  return best;
}

/** The index of a sitemap set: one entry per child, children named under /sitemaps/. */
export function indexOfSet(set: SitemapSet, origin: string): IndexEntry[] {
  const out: IndexEntry[] = [];
  for (const kind of SITEMAP_KINDS) {
    (set.kinds[kind] ?? []).forEach((c, i) => {
      const lastmod = chunkLastmod(c);
      out.push({ loc: `${origin}/sitemaps/${childName(kind, i + 1)}`, ...(lastmod ? { lastmod } : {}) });
    });
  }
  return out;
}

export const countUrls = (set: SitemapSet): number => Object.values(set.kinds).reduce((n, cs) => n + (cs ?? []).reduce((m, c) => m + c.urls.length, 0), 0);
