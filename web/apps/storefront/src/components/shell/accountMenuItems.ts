import { type Me } from '@afrotransact/api-client';

import { type MessageKey } from '@/i18n/t';
import { signInHref, signUpHref } from '@/lib/links';

export type AccountMenuIcon = 'user' | 'package' | 'returns' | 'pin' | 'card' | 'shield' | 'sparkle' | 'store' | 'calendar' | 'signin' | 'signup';

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
      { id: 'returns', label: 'account.returns', icon: 'returns', href: '/account/returns', count: c?.returns },
      { id: 'addresses', label: 'account.addresses', icon: 'pin', href: '/account/addresses', count: c?.addresses },
      { id: 'payments', label: 'account.payments', icon: 'card', href: '/account/payments', count: c?.paymentMethods },
      { id: 'security', label: 'account.security', icon: 'shield', href: '/account/security' },
      { id: 'lola', label: 'account.askLola', icon: 'sparkle', action: 'lola' },
      { id: 'sell', label: 'account.sell', icon: 'store', href: '/sell', external: true },
      { id: 'provide', label: 'account.provide', icon: 'calendar', href: '/provide', external: true }
    ]
  };
}
