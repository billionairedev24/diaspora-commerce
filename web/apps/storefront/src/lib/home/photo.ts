/**
 * A real photograph: an http(s) or site-relative URL that is not an svg, an ico, a data: URI or a placeholder path. The same rule
 * the gateway applies (`eligibleCard`, `photo_ok`): the home never shows an icon, an emoji or a striped box in place of a photo,
 * it shows text instead.
 */
export function isRealPhoto(url: string | null | undefined): url is string {
  if (!url) return false;
  const u = url.trim();
  if (!u || /^data:/i.test(u)) return false;
  if (!/^https?:\/\//i.test(u) && !u.startsWith('/')) return false;
  const path = u.split(/[?#]/)[0] ?? '';
  if (/\.(svg|ico)$/i.test(path)) return false;
  if (/placeholder|no-image|noimage|default-image/i.test(path)) return false;
  return true;
}
