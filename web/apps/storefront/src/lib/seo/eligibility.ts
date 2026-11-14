/* Which products may be advertised to search engines. Same rule as the home page and rails (gateway-bff site_home.go
   eligibleCard/realPhoto): in stock, with a real photograph; never a held, out-of-stock, icon, emoji or placeholder item.
   "Live with a stored RESOLVED tax decision" is not re-derived here: the catalog's ProductLiveGate (and its 60 s sweep,
   runbook CatalogLiveWithoutTaxDecision) only lets such a listing into the search index, and the sitemap reads the index
   through the gateway, so a listing without a resolved decision is never listed. */

const PLACEHOLDER_MARKS = ['placeholder', 'no-image', 'noimage', 'no_image', 'default-image', 'coming-soon', 'image-not-available', '/icons/', '/icon-'];

/** True when `url` points at an actual photograph (http(s) or site-relative; not svg/ico, not a known stand-in). Keep in step with site_home.go. */
export function realPhoto(url: string | null | undefined): boolean {
  if (!url) return false;
  const raw = url.trim().toLowerCase();
  if (raw === '') return false;
  if (!raw.startsWith('https://') && !raw.startsWith('http://') && !raw.startsWith('/')) return false;
  let path: string;
  try {
    path = new URL(raw, 'https://x.invalid').pathname;
  } catch {
    return false;
  }
  if (path.endsWith('.svg') || path.endsWith('.ico')) return false;
  return !PLACEHOLDER_MARKS.some((m) => path.includes(m));
}

export interface SitemapProductCandidate {
  atin: string;
  inStock: boolean;
  imageUrl: string | null;
  availableQty: number;
  stockStatus: 'IN_STOCK' | 'LOW_STOCK' | 'OUT_OF_STOCK';
}

/** A product is listed in the sitemap only when it is in stock and has a real photo. */
export function sitemapProductEligible(p: SitemapProductCandidate): boolean {
  return p.inStock && p.stockStatus !== 'OUT_OF_STOCK' && p.availableQty > 0 && realPhoto(p.imageUrl);
}

/** Absolute URLs of the real photographs among `urls` (relative ones resolve against `origin`), in order, de-duplicated. */
export function realPhotoUrls(urls: readonly (string | null | undefined)[], origin: string | undefined): string[] {
  const out: string[] = [];
  for (const u of urls) {
    if (!realPhoto(u) || !u) continue;
    let abs: string;
    try {
      abs = new URL(u.trim(), origin ?? 'https://x.invalid').toString();
    } catch {
      continue;
    }
    if (abs.startsWith('https://x.invalid')) continue;
    if (!out.includes(abs)) out.push(abs);
  }
  return out;
}

export interface RelatedCandidate extends SitemapProductCandidate {
  /** Present when the hit is a paid placement; such hits are never shown as plain recommendations. */
  sponsor?: unknown;
}

/** A product may be recommended (cart rail) only when it is in stock with a real photograph, the same rule as the sitemap and the home rails, and is not a paid placement. */
export function recommendable(p: RelatedCandidate): boolean {
  return sitemapProductEligible(p) && !p.sponsor;
}
