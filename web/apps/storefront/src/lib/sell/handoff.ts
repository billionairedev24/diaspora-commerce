/* /sell and /provide are route handlers that answer with a redirect to Seller Center's sign-in, on another origin. Next's
   <Link> would prefetch them and navigate to them with an RSC fetch (`?_rsc=`); the browser then follows the redirect
   cross-origin and blocks it with a CORS error. These addresses are always plain links (full page navigation), so the
   redirect is an ordinary navigation and nothing is prefetched. */

const HANDOFF = /^\/(?:sell|provide)(?:[/?#]|$)/;

/** True for an address that hands over to another app (Seller Center): render a plain <a>, never a <Link>. */
export function isHandoffHref(href: string): boolean {
  return HANDOFF.test(href);
}
