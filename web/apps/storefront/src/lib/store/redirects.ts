/**
 * The ONE address of a seller's storefront is /b/<lowercase slug>. These variants 301 to it (query kept), so links from
 * earlier versions, hand-typed capitals and trailing slashes all land on the canonical page and search engines consolidate:
 *   /b/LaserFeet, /b/laserfeet/  ->  /b/laserfeet
 *   /store/x, /stores/x, /seller/x, /sellers/x, /shop/x, /business/x  ->  /b/x
 * Returns the target path, or null when the path is already canonical or is not a storefront address.
 */
const ALIAS = /^\/(?:b|store|stores|seller|sellers|shop|business)\/([A-Za-z0-9][A-Za-z0-9-]{0,79})\/?$/;

export function storefrontRedirectPath(pathname: string): string | null {
  const m = ALIAS.exec(pathname);
  if (!m) return null;
  const target = `/b/${m[1]!.toLowerCase()}`;
  return target === pathname ? null : target;
}
