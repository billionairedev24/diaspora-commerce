/* The signed-in person's back offices (GET /v1/me user.backOffices): Seller Center, Provider Hub and the staff console.
   The account menu links to /back-office/<app> on the storefront; that route sends the browser to the app's configured
   URL (SELLER_CENTER_URL, STAFF_CONSOLE_URL; resolved per request, never a hard-coded host or port) through the
   app's hosted sign-in, which single sign-on passes straight through, so the person lands there signed in. */

import { type BackOffice } from '@afrotransact/api-client';

import { onboardingUrl } from './sell/entry';

export type BackOfficeApp = BackOffice['app'];

export const BACK_OFFICE_APPS: readonly BackOfficeApp[] = ['seller-center', 'provider-hub', 'staff-console'];

/** The storefront link for a back office (an application in progress continues onboarding). */
export function backOfficeHref({ app, state }: BackOffice): string {
  return state === 'application' ? `/back-office/${app}?state=application` : `/back-office/${app}`;
}

export interface BackOfficeUrls {
  /** SELLER_CENTER_URL (Seller Center and Provider Hub), without a trailing slash. */
  sellerCenterUrl: string;
  /** STAFF_CONSOLE_URL, without a trailing slash. */
  staffConsoleUrl: string;
}

/** Where /back-office/<app> sends the browser, or null for an unknown app. */
export function backOfficeUrl(app: string, state: string | null, urls: BackOfficeUrls): string | null {
  const signedIn = (origin: string, destination: string) =>
    `${origin}/api/bff/v1/auth/login?${new URLSearchParams({ returnTo: destination }).toString()}`;
  const sc = urls.sellerCenterUrl.replace(/\/+$/, '');
  const st = urls.staffConsoleUrl.replace(/\/+$/, '');
  const application = state === 'application';
  switch (app) {
    case 'seller-center':
      return signedIn(sc, application ? onboardingUrl(sc, 'seller') : `${sc}/`);
    case 'provider-hub':
      return signedIn(sc, application ? onboardingUrl(sc, 'provider') : `${sc}/`);
    case 'staff-console':
      return signedIn(st, `${st}/`);
    default:
      return null;
  }
}
