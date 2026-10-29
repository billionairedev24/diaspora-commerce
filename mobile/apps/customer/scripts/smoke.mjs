// Is the stack this app points at reachable, and does it serve products a phone can show?
//   pnpm smoke                                  # the URLs the app would use (EXPO_PUBLIC_*, else the local stack on EXPO_PUBLIC_DEV_HOST)
//   EXPO_PUBLIC_DEV_HOST=192.168.1.50 pnpm smoke # the Mac's LAN address, as a phone sees it
// Public routes only (no sign-in): the BFF's search, the first product photo, identity's OIDC discovery.
const host = (process.env.EXPO_PUBLIC_DEV_HOST || 'localhost').trim();
const bff = (process.env.EXPO_PUBLIC_BFF_URL || `http://${host}:8200`).replace(/\/+$/, '');
const issuer = (process.env.EXPO_PUBLIC_OIDC_ISSUER || `http://${host}:8100`).replace(/\/+$/, '');

let failed = 0;
async function step(name, fn) {
  const t0 = Date.now();
  try {
    const detail = await fn();
    console.log(`  ok    ${name}${detail ? ` — ${detail}` : ''} (${Date.now() - t0} ms)`);
  } catch (e) {
    failed++;
    const cause = e?.cause?.code ?? e?.cause?.message ?? '';
    console.log(`  FAIL  ${name} — ${e.message}${cause ? ` (${cause})` : ''}`);
  }
}
async function getJson(url) {
  const res = await fetch(url, { headers: { Accept: 'application/json', 'X-Client': 'customer-app-smoke' }, signal: AbortSignal.timeout(10_000) });
  if (!res.ok) throw new Error(`${res.status} ${res.statusText} from ${url}`);
  return res.json();
}

console.log(`BFF      ${bff}\nIdentity ${issuer}\n`);
let hits = [];
await step('identity: OIDC discovery', async () => {
  const d = await getJson(`${issuer}/.well-known/openid-configuration`);
  if (!d.authorization_endpoint) throw new Error('no authorization_endpoint');
  if (d.issuer.replace(/\/+$/, '') !== issuer) throw new Error(`issuer is ${d.issuer}, the app expects ${issuer} (tokens would not validate: run make up MOBILE_HOST=lan)`);
  return d.issuer;
});
await step('bff: search returns products', async () => {
  const page = await getJson(`${bff}/v1/search?q=&size=12&page=0`);
  hits = page.hits ?? [];
  if (!hits.length) throw new Error('no products (seed the catalogue: make seed)');
  return `${hits.length} of ${page.total ?? '?'} — ${hits.slice(0, 3).map((h) => h.title).join(', ')}`;
});
await step('bff: a product page', async () => {
  const atin = hits[0]?.atin;
  if (!atin) throw new Error('skipped: no products');
  const d = await getJson(`${bff}/v1/products/${encodeURIComponent(atin)}`);
  return `${d.product.title}, ${d.product.images.length} photo(s), buy box ${d.buyBox ? 'yes' : 'none'}`;
});
await step('photos: the first product photo downloads', async () => {
  const withPhoto = hits.find((h) => h.imageUrl);
  if (!withPhoto) throw new Error('no product has a photo (upload one in Seller Center, or check the media rewrite)');
  const res = await fetch(withPhoto.imageUrl, { signal: AbortSignal.timeout(10_000) });
  if (!res.ok) throw new Error(`${res.status} for ${withPhoto.imageUrl}`);
  if (/localhost|127\.0\.0\.1/.test(withPhoto.imageUrl) && host !== 'localhost') throw new Error(`photo URL points at localhost, which a phone cannot reach: ${withPhoto.imageUrl} (LAN mode rewrites it)`);
  return `${res.headers.get('content-type')} from ${new URL(withPhoto.imageUrl).host}`;
});
console.log(failed ? `\n${failed} check(s) failed.` : '\nThe phone can see the products.');
process.exit(failed ? 1 : 0);
