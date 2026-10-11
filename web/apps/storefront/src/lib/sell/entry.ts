/* "Start selling" (/sell) and "Offer your services" (/provide): the storefront hands over to Seller Center's
   onboarding through the BFF's hosted sign-in on Seller Center's own origin (the session cookie is set there).
   - Signed in here: plain sign-in. Identity already has the session (single sign-on), so it passes straight through
     and the browser lands in onboarding signed in, where the application is started and prefilled.
   - Not signed in: identity's create-account page (prompt=create) with the seller copy (intent=sell|provide); after
     sign-up and the email code the same request continues into onboarding. "Already have an account? Sign in" there
     continues to onboarding too. */

export type SellRole = 'seller' | 'provider';

export interface SellEntryInput {
  role: SellRole;
  /** Whether the shopper is signed in on the storefront (an identity session exists). */
  signedIn: boolean;
  /** Seller Center origin (SELLER_CENTER_URL), without a trailing slash. */
  sellerCenterUrl: string;
}

/** Seller Center onboarding for the role: where the browser lands signed in. */
export function onboardingUrl(sellerCenterUrl: string, role: SellRole): string {
  return `${sellerCenterUrl.replace(/\/+$/, '')}/onboarding?role=${role}`;
}

/** The hosted sign-in (or sign-up) start on Seller Center's origin that ends in its onboarding. */
export function sellEntryUrl({ role, signedIn, sellerCenterUrl }: SellEntryInput): string {
  const origin = sellerCenterUrl.replace(/\/+$/, '');
  const q = new URLSearchParams({ returnTo: onboardingUrl(origin, role) });
  if (!signedIn) {
    q.set('prompt', 'create');
    q.set('intent', role === 'provider' ? 'provide' : 'sell');
  }
  return `${origin}/api/bff/v1/auth/login?${q.toString()}`;
}
