import { type BackOffice, type Me } from '@afrotransact/api-client';

import { type MessageKey } from '@/i18n/t';
import { backOfficeHref } from '@/lib/back-office';
import { signInHref, signUpHref } from '@/lib/links';

export type AccountMenuIcon = 'user' | 'package' | 'help' | 'pin' | 'card' | 'shield' | 'sparkle' | 'store' | 'calendar' | 'desktop' | 'signin' | 'signup';

export interface AccountMenuEntry {
  id: string;
  label: MessageKey;
  icon: AccountMenuIcon;
  href?: string;
  /** Full page navigation (other apps, e.g. Seller Center via /sell, and identity's hosted sign-in). */
  external?: boolean;
  action?: 'lola' | 'signout';
  count?: number;
}

export interface AccountMenuModel {
  variant: 'guest' | 'user';
  initials: string;
  title: string;
  subtitle: string;
  items: AccountMenuEntry[];
  showSignOut: boolean;
}

/** Guest vs signed-in avatar menu, exactly as in the design. */
export function accountMenuModel(me: Me | undefined, returnTo = '/'): AccountMenuModel {
  const user = me?.authenticated ? me.user : null;
  if (!user) {
    return {
      variant: 'guest',
      initials: '?',
      title: 'account.welcomeGuest',
      subtitle: 'account.guestSub',
      showSignOut: false,
      items: [
        // Identity's hosted sign-in / sign-up pages (single sign-on), started by the BFF.
        { id: 'signin', label: 'account.signIn', icon: 'signin', href: signInHref(returnTo), external: true },
        { id: 'signup', label: 'account.createAccount', icon: 'signup', href: signUpHref(returnTo), external: true }
      ]
    };
  }
  const c = me?.counts;
  return {
    variant: 'user',
    initials: user.initials.toUpperCase().slice(0, 2),
    title: `${user.firstName} ${user.lastName}`,
    subtitle: user.email,
    showSignOut: true,
    items: [
      { id: 'account', label: 'account.yourAccount', icon: 'user', href: '/account' },
      { id: 'orders', label: 'account.orders', icon: 'package', href: '/orders', count: c?.orders },
      { id: 'help', label: 'account.help', icon: 'help', href: '/account/help' },
      { id: 'addresses', label: 'account.addresses', icon: 'pin', href: '/account/addresses', count: c?.addresses },
      { id: 'payments', label: 'account.payments', icon: 'card', href: '/account/payments', count: c?.paymentMethods },
      { id: 'security', label: 'account.security', icon: 'shield', href: '/account/security' },
      { id: 'lola', label: 'account.askLola', icon: 'sparkle', action: 'lola' },
      ...backOfficeEntries(user.backOffices)
    ]
  };
}

/**
 * The person's back offices (GET /v1/me user.backOffices): "Seller Center" for an approved seller organisation or
 * "Continue your seller application" while it is in progress (else "Sell on AfroTransact"), the same for Provider Hub
 * (else "Offer your services"), and "Staff console" for staff. Each opens the app's configured URL (/back-office).
 */
export function backOfficeEntries(backOffices: readonly BackOffice[] = []): AccountMenuEntry[] {
  const of = (app: BackOffice['app']) => backOffices.find((b) => b.app === app);
  const seller = of('seller-center');
  const provider = of('provider-hub');
  const staff = of('staff-console');
  const out: AccountMenuEntry[] = [
    seller
      ? { id: 'seller-center', label: seller.state === 'active' ? 'account.sellerCenter' : 'account.sellerApplication', icon: 'store', href: backOfficeHref(seller), external: true }
      : { id: 'sell', label: 'account.sell', icon: 'store', href: '/sell', external: true },
    provider
      ? { id: 'provider-hub', label: provider.state === 'active' ? 'account.providerHub' : 'account.providerApplication', icon: 'calendar', href: backOfficeHref(provider), external: true }
      : { id: 'provide', label: 'account.provide', icon: 'calendar', href: '/provide', external: true }
  ];
  if (staff) out.push({ id: 'staff-console', label: 'account.staffConsole', icon: 'desktop', href: backOfficeHref(staff), external: true });
  return out;
}

/** Pages only a signed-in customer can see: signing out there lands on the home page instead (no sign-in wall). */
const PRIVATE_PREFIXES = ['/account', '/orders', '/checkout'];

/**
 * Where the storefront comes back to after signing out (single sign-out through identity): the page the person was on
 * when a guest may see it, else undefined (the home page).
 */
export function signOutReturnTo(pathname: string, search = ''): string | undefined {
  if (!pathname.startsWith('/') || pathname === '/') return undefined;
  if (PRIVATE_PREFIXES.some((p) => pathname === p || pathname.startsWith(`${p}/`))) return undefined;
  return `${pathname}${search}`;
}
