import {
  type PriceBucketId,
  PriceBucketSchema,
  type SearchQuery,
  type SearchQueryInput,
  SearchQuerySchema,
  SearchSortSchema
} from './schemas/search';

/* Search state ⇄ storefront URL (/s?q=…&dept=…) and ⇄ BFF query string (/v1/search?q=…&department=…).
   URL keys are short and human-readable; pages are 1-based in the URL and 0-based in the API. */

type ParamSource = URLSearchParams | Record<string, string | string[] | undefined>;

function getter(src: ParamSource): (k: string) => string | null {
  if (src instanceof URLSearchParams) return (k) => src.get(k);
  return (k) => {
    const v = src[k];
    return Array.isArray(v) ? (v[0] ?? null) : (v ?? null);
  };
}

const num = (v: string | null): number | null => {
  if (v === null || v === '') return null;
  const n = Number(v);
  return Number.isFinite(n) ? n : null;
};

/** Reads the storefront URL search params. Unknown or invalid values fall back to defaults. */
export function parseSearchUrl(src: ParamSource): SearchQuery {
  const get = getter(src);
  const ff = get('fulfilment');
  const sold = get('soldBy');
  const price = get('price');
  const sort = get('sort');
  const page = num(get('page'));
  const input: SearchQueryInput = {
    q: (get('q') ?? '').slice(0, 200),
    department: get('dept') || null,
    community: get('community') || null,
    fulfilment: ff === 'afrotransact' ? 'AFROTRANSACT' : ff === 'seller' ? 'SELLER' : null,
    sellerKind: sold === 'platform' ? 'PLATFORM' : sold === 'seller' ? 'SELLER' : null,
    minRating: num(get('rating')),
    price: price && PriceBucketSchema.safeParse(price).success ? (price as PriceBucketId) : null,
    maxDays: get('delivery') === '1' ? 1 : null,
    sort: sort && SearchSortSchema.safeParse(sort).success ? (sort as SearchQuery['sort']) : 'relevance',
    page: page && page > 1 ? Math.floor(page) - 1 : 0
  };
  const parsed = SearchQuerySchema.safeParse(input);
  return parsed.success ? parsed.data : SearchQuerySchema.parse({});
}

/** Serialises search state for the storefront URL, omitting defaults so URLs stay short and shareable. */
export function toSearchUrl(query: Partial<SearchQuery>): URLSearchParams {
  const p = new URLSearchParams();
  if (query.q) p.set('q', query.q);
  if (query.department) p.set('dept', query.department);
  if (query.community) p.set('community', query.community);
  if (query.fulfilment) p.set('fulfilment', query.fulfilment === 'AFROTRANSACT' ? 'afrotransact' : 'seller');
  if (query.sellerKind) p.set('soldBy', query.sellerKind === 'PLATFORM' ? 'platform' : 'seller');
  if (query.minRating) p.set('rating', String(query.minRating));
  if (query.price) p.set('price', query.price);
  if (query.maxDays === 1) p.set('delivery', '1');
  if (query.sort && query.sort !== 'relevance') p.set('sort', query.sort);
  if (query.page && query.page > 0) p.set('page', String(query.page + 1));
  return p;
}

export function searchHref(query: Partial<SearchQuery>): string {
  const qs = toSearchUrl(query).toString();
  return qs ? `/s?${qs}` : '/s';
}

/** Query string for GET /v1/search on the gateway BFF. */
export function toApiSearchParams(query: SearchQuery): URLSearchParams {
  const p = new URLSearchParams();
  if (query.q) p.set('q', query.q);
  if (query.department) p.set('department', query.department);
  if (query.community) p.set('community', query.community);
  if (query.fulfilment) p.set('fulfilment', query.fulfilment);
  if (query.sellerKind) p.set('sellerKind', query.sellerKind);
  if (query.minRating !== null) p.set('minRating', String(query.minRating));
  if (query.price) p.set('price', query.price);
  if (query.maxDays !== null) p.set('maxDays', String(query.maxDays));
  p.set('sort', query.sort);
  p.set('page', String(query.page));
  p.set('size', String(query.size));
  return p;
}

/** Inverse of toApiSearchParams (used by the mock BFF and contract tests). */
export function fromApiSearchParams(p: URLSearchParams): SearchQuery {
  const get = (k: string) => p.get(k);
  const ff = get('fulfilment');
  const sk = get('sellerKind');
  const price = get('price');
  const sort = get('sort');
  const parsed = SearchQuerySchema.safeParse({
    q: get('q') ?? '',
    department: get('department') || null,
    community: get('community') || null,
    fulfilment: ff === 'AFROTRANSACT' || ff === 'SELLER' ? ff : null,
    sellerKind: sk === 'PLATFORM' || sk === 'SELLER' ? sk : null,
    minRating: num(get('minRating')),
    price: price && PriceBucketSchema.safeParse(price).success ? price : null,
    maxDays: num(get('maxDays')),
    sort: sort && SearchSortSchema.safeParse(sort).success ? sort : 'relevance',
    page: num(get('page')) ?? 0,
    size: num(get('size')) ?? 12
  });
  if (!parsed.success) throw parsed.error;
  return parsed.data;
}
