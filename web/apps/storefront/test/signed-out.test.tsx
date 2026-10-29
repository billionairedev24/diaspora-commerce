import { screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { signOutReturnTo } from '@/components/shell/accountMenuItems';
import { SignedOutNotice, withoutSignedOutFlag } from '@/components/shell/SignedOutNotice';

import { renderWithProviders } from './render';

const nav = vi.hoisted(() => ({ params: new URLSearchParams(), pathname: '/', replace: vi.fn() }));
vi.mock('next/navigation', () => ({
  useRouter: () => ({ push: vi.fn(), refresh: vi.fn(), replace: nav.replace, back: vi.fn(), prefetch: vi.fn() }),
  usePathname: () => nav.pathname,
  useSearchParams: () => nav.params
}));

describe('signing out of the storefront (single sign-out through identity)', () => {
  afterEach(() => {
    nav.replace.mockReset();
    nav.params = new URLSearchParams();
    nav.pathname = '/';
  });

  it('comes back to the page a guest may see, never to an account page (no sign-in wall)', () => {
    expect(signOutReturnTo('/p/AT000000P2', '?v=2')).toBe('/p/AT000000P2?v=2');
    expect(signOutReturnTo('/s', '?q=garri')).toBe('/s?q=garri');
    expect(signOutReturnTo('/cart')).toBe('/cart');
    expect(signOutReturnTo('/')).toBeUndefined();
    for (const page of ['/account', '/account/security', '/orders', '/orders/AT-1', '/checkout']) {
      expect(signOutReturnTo(page)).toBeUndefined();
    }
    expect(signOutReturnTo('/accounting-tips')).toBe('/accounting-tips');
  });

  it('shows “You’re signed out” once and drops the flag from the address', async () => {
    nav.pathname = '/p/AT000000P2';
    nav.params = new URLSearchParams('v=2&signedOut=1');
    renderWithProviders(<SignedOutNotice />);
    expect(await screen.findByText('You’re signed out')).toBeInTheDocument();
    expect(nav.replace).toHaveBeenCalledWith('/p/AT000000P2?v=2', { scroll: false });
  });

  it('says nothing on an ordinary visit', () => {
    renderWithProviders(<SignedOutNotice />);
    expect(screen.queryByText('You’re signed out')).not.toBeInTheDocument();
    expect(nav.replace).not.toHaveBeenCalled();
    expect(withoutSignedOutFlag('/', new URLSearchParams('signedOut=1'))).toBe('/');
  });
});
