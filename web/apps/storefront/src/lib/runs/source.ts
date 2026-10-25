/* Delivery run alert sign-ups remember where they came from (S262): the consent record keeps a `source`, and links that
   carry UTM attribution (the location page's share links, run alert messages) add the UTM source to it, so a sign-up can
   be traced to the channel that shared the link. The source is stored on the consent record in delivery-runs
   (lowercase letters, digits, dot, dash and underscore; at most 80 characters). */

const TOKEN = /[^a-z0-9_-]+/g;

/** "location-page" or, when the landing URL carried utm_source=wa, "location-page.wa". */
export function subscriptionSource(base: string, params: URLSearchParams | null | undefined): string {
  const utm = (params?.get('utm_source') ?? '').toLowerCase().replace(TOKEN, '').slice(0, 30);
  return utm ? `${base}.${utm}` : base;
}

/** Share links for the location page: the BFF supplies them with UTM attribution; anything else is dropped. */
export function shareEntries(links: Record<string, string>): { channel: string; url: string }[] {
  return Object.entries(links)
    .filter(([, url]) => /^https:\/\//.test(url))
    .map(([channel, url]) => ({ channel, url }));
}
