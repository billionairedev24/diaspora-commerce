import 'server-only';

import { type IdentityProviderId, type SiteConfig } from '@afrotransact/api-client';

import { siteQueries } from '@/lib/api/queries';
import { serverApi } from '@/lib/api/server';
import { mocksEnabled } from '@/lib/config';
import { safeReturnTo } from '@/lib/links';
import { getQueryClient } from '@/lib/query/getQueryClient';

export async function authPageData(searchParams: Promise<Record<string, string | string[] | undefined>>) {
  const sp = await searchParams;
  const raw = sp.returnTo;
  const returnTo = safeReturnTo(Array.isArray(raw) ? raw[0] : raw);
  const first = (k: string) => {
    const v = sp[k];
    return (Array.isArray(v) ? v[0] : v) ?? '';
  };
  /* Organisation invite links (Seller Center accept page): the token and the invited address. */
  const invitation = first('invitation').slice(0, 200);
  const invitedEmail = invitation ? first('email').slice(0, 254) : '';
  let config: SiteConfig | null = null;
  try {
    config = await getQueryClient().fetchQuery(siteQueries.config(await serverApi()));
  } catch {
    config = null;
  }
  const providers: IdentityProviderId[] = (config?.identityProviders ?? []).filter((p) => p.enabled).map((p) => p.id);
  const legal = (key: string, fallback: string) => config?.legal.find((l) => l.key === key)?.href ?? fallback;
  return {
    returnTo,
    invitation,
    invitedEmail,
    providers,
    demo: mocksEnabled(),
    smsProgram: config?.smsProgramName ?? 'AfroTransact Alerts',
    links: { terms: legal('terms', '/legal/terms'), privacy: legal('privacy', '/legal/privacy'), sms: legal('sms', '/legal/sms') }
  };
}
