import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { ReferView } from '@/components/refer/ReferView';
import { referralSignUpHref } from '@/lib/links';

import { installMockBff, signInDemo } from './mockBff';
import { renderWithProviders } from './render';

installMockBff();

const redirect = vi.fn((url: string) => {
  throw new Error(`NEXT_REDIRECT ${url}`);
});
vi.mock('next/navigation', async () => ({ ...(await vi.importActual<Record<string, unknown>>('next/navigation')), redirect: (url: string) => redirect(url) }));

describe('Account, Refer a friend (S322)', () => {
  it('shows the code and share link, the offer, the friends by name and what each paid', async () => {
    await signInDemo();
    renderWithProviders(<ReferView />);
    expect(await screen.findByTestId('refer-code')).toHaveTextContent('KDM7-XR3Q');
    expect(screen.getByTestId('refer-link')).toHaveTextContent('/r/KDM7XR3Q');
    expect(screen.getByText(/first order of at least \$30\.00 is delivered, you get \$10\.00 credit and they get \$10\.00 credit/)).toBeInTheDocument();

    const friends = await screen.findAllByTestId('refer-friend');
    expect(friends).toHaveLength(3);
    expect(friends[0]).toHaveTextContent('Amara O.');
    expect(friends[0]).toHaveTextContent('Rewarded');
    expect(friends[0]).toHaveTextContent('You earned $10.00 credit');
    expect(friends[1]).toHaveTextContent('Kofi B.');
    expect(friends[1]).toHaveTextContent('Delivered, reward after the return window');
    expect(friends[1]).toHaveTextContent(/You’ll earn \$10\.00 credit on /);
    expect(friends[2]).toHaveTextContent('Zainab K.');
    expect(friends[2]).toHaveTextContent('Waiting for their first order');

    expect(screen.getByTestId('refer-invited')).toHaveTextContent('3');
    expect(screen.getByTestId('refer-rewarded')).toHaveTextContent('1');
    expect(screen.getByTestId('refer-earned')).toHaveTextContent('$10.00');
    expect(screen.getByTestId('refer-pending')).toHaveTextContent('$10.00');
    // names, never ids
    expect(within(screen.getByTestId('refer-friends')).queryByText(/rf-\d|[0-9a-f]{8}-/)).not.toBeInTheDocument();
  });

  it('offers copy and share buttons, and nothing that takes credit out', async () => {
    await signInDemo();
    const user = userEvent.setup();
    const writeText = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true });
    renderWithProviders(<ReferView />);
    await user.click(await screen.findByTestId('refer-copy-code'));
    await waitFor(() => expect(writeText).toHaveBeenCalledWith('KDM7-XR3Q'));
    await user.click(screen.getByTestId('refer-copy-link'));
    await waitFor(() => expect(writeText).toHaveBeenLastCalledWith(expect.stringContaining('/r/KDM7XR3Q')));

    const whatsapp = screen.getByTestId('refer-whatsapp');
    expect(whatsapp.getAttribute('href')).toMatch(/^https:\/\/wa\.me\/\?text=/);
    expect(decodeURIComponent(whatsapp.getAttribute('href') ?? '')).toContain('/r/KDM7XR3Q');
    expect(whatsapp).toHaveAttribute('rel', 'noopener noreferrer');
    expect(screen.getByTestId('refer-email').getAttribute('href')).toMatch(/^mailto:\?subject=/);
    expect(screen.getByTestId('refer-sms').getAttribute('href')).toMatch(/^sms:/);
    expect(screen.queryByRole('button', { name: /withdraw|cash ?out|payout|transfer|claim/i })).not.toBeInTheDocument();
    expect(screen.getByText(/can’t be withdrawn/)).toBeInTheDocument();
  });
});

describe('a friend’s share link /r/<code> (S322)', () => {
  beforeEach(() => {
    redirect.mockClear();
  });

  it('opens the hosted sign-up with the code filled in', () => {
    expect(referralSignUpHref('KDM7-XR3Q')).toBe('/api/bff/v1/auth/login?prompt=create&referral=KDM7-XR3Q');
    expect(referralSignUpHref('kdm7xr3q')).toBe('/api/bff/v1/auth/login?prompt=create&referral=kdm7xr3q');
  });

  it('sends anything that is not shaped like a code to the plain sign-up', () => {
    for (const bad of ['', 'a', '<script>', 'KDM7-XR3Q-KDM7-XR3Q-TOO-LONG', 'a%26b%3Dc', '%E0%A4%A']) {
      expect(referralSignUpHref(bad)).toBe('/api/bff/v1/auth/login?prompt=create');
    }
  });

  it('the route redirects there', async () => {
    const { default: Page } = await import('@/app/(auth)/r/[code]/page');
    await expect(Page({ params: Promise.resolve({ code: 'KDM7-XR3Q' }) })).rejects.toThrow('NEXT_REDIRECT');
    expect(redirect).toHaveBeenCalledWith('/api/bff/v1/auth/login?prompt=create&referral=KDM7-XR3Q');
  });
});
