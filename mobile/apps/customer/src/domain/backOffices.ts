/* The signed-in person's back offices (GET /v1/me user.backOffices) on the account screen: Seller Center, Provider Hub
   and the staff console. They are web apps: the storefront's /back-office/<app> link sends the browser to the
   environment's configured app URL (SELLER_CENTER_URL, STAFF_CONSOLE_URL), signed in through single sign-on. */
import { type BackOffice } from '@afrotransact/api-client';

export type BackOfficeLabelKey =
  | 'cu.account.sellerCenter'
  | 'cu.account.sellerApplication'
  | 'cu.account.providerHub'
  | 'cu.account.providerApplication'
  | 'cu.account.staffConsole';

/** The account screen label ("Continue your seller application" while it is in progress). */
export function backOfficeLabel({ app, state }: BackOffice): BackOfficeLabelKey {
  switch (app) {
    case 'seller-center':
      return state === 'application' ? 'cu.account.sellerApplication' : 'cu.account.sellerCenter';
    case 'provider-hub':
      return state === 'application' ? 'cu.account.providerApplication' : 'cu.account.providerHub';
    default:
      return 'cu.account.staffConsole';
  }
}

/** The storefront link (EXPO_PUBLIC_WEB_URL) that opens the back office. */
export function backOfficeUrl(webUrl: string, { app, state }: BackOffice): string {
  const base = `${webUrl.replace(/\/+$/, '')}/back-office/${app}`;
  return state === 'application' ? `${base}?state=application` : base;
}
