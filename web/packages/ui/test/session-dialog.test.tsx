import { act, fireEvent, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';

import { SessionEndedScreen, SessionTimeoutDialog, type SessionTimeoutDialogProps, type SessionTimeoutLabels } from '../src';

const labels: SessionTimeoutLabels = {
  warnTitle: 'You are about to be signed out',
  idleBody: 'You’ll be signed out in {time} because you’ve been inactive.',
  absoluteBody: 'For your security you need to sign in again at {clock}.',
  absoluteNote: 'Staying active cannot extend this session.',
  timeLeft: 'Time left',
  announceIdle: 'You’ll be signed out in {time}.',
  announceAbsolute: 'You’ll need to sign in again in {time}.',
  stay: 'Stay signed in',
  signOutNow: 'Sign out now',
  dismiss: 'Got it',
  close: 'Close',
  failed: 'We couldn’t reach the server. Try again.',
  endedTitle: 'You’ve been signed out',
  endedIdle: 'You were inactive for a while.',
  endedAbsolute: 'Your session reached its maximum length.',
  endedSignedOut: 'You signed out in another window.',
  signInAgain: 'Sign in again',
  signingOut: 'Signing you out…'
};

const base = (over: Partial<SessionTimeoutDialogProps> = {}): SessionTimeoutDialogProps => ({
  open: true,
  kind: 'idle',
  secondsLeft: 45,
  endsAt: Date.parse('2026-11-03T16:15:00Z'),
  busy: false,
  failed: false,
  labels,
  locale: 'en',
  onStay: vi.fn(),
  onSignOut: vi.fn(),
  onDismiss: vi.fn(),
  ...over
});

const polite = () => screen.getByTestId('session-live-polite');
const assertive = () => screen.getByTestId('session-live-assertive');

describe('SessionTimeoutDialog', () => {
  it('explains plainly, shows the countdown and puts focus on "Stay signed in"', () => {
    render(<SessionTimeoutDialog {...base()} />);
    const dialog = screen.getByRole('dialog', { name: labels.warnTitle });
    expect(within(dialog).getByText('You’ll be signed out in 0:45 because you’ve been inactive.')).toBeInTheDocument();
    expect(screen.getByTestId('session-countdown')).toHaveTextContent('0:45');
    expect(screen.getByRole('button', { name: 'Stay signed in' })).toHaveFocus();
    expect(screen.getByRole('button', { name: 'Sign out now' })).toBeInTheDocument();
  });

  it('keeps keyboard focus inside while it is open', async () => {
    const user = userEvent.setup();
    render(
      <>
        <button type="button">Behind the dialog</button>
        <SessionTimeoutDialog {...base()} />
      </>
    );
    const dialog = screen.getByRole('dialog');
    for (let i = 0; i < 6; i += 1) {
      await user.tab();
      expect(dialog.contains(document.activeElement)).toBe(true);
    }
    for (let i = 0; i < 6; i += 1) {
      await user.tab({ shift: true });
      expect(dialog.contains(document.activeElement)).toBe(true);
    }
    // what is behind it is hidden from assistive technology
    expect(screen.queryByRole('button', { name: 'Behind the dialog' })).not.toBeInTheDocument();
  });

  it('"Stay signed in" and "Sign out now" do what they say; Esc and the close button stay signed in', async () => {
    const user = userEvent.setup();
    const props = base();
    render(<SessionTimeoutDialog {...props} />);
    await user.click(screen.getByRole('button', { name: 'Stay signed in' }));
    expect(props.onStay).toHaveBeenCalledTimes(1);
    await user.click(screen.getByRole('button', { name: 'Sign out now' }));
    expect(props.onSignOut).toHaveBeenCalledTimes(1);
    await user.keyboard('{Escape}');
    expect(props.onStay).toHaveBeenCalledTimes(2);
    expect(props.onDismiss).not.toHaveBeenCalled();
  });

  it('shows the busy state and the failure', () => {
    const { rerender } = render(<SessionTimeoutDialog {...base({ busy: true })} />);
    expect(screen.getByRole('button', { name: 'Stay signed in' })).toBeDisabled();
    rerender(<SessionTimeoutDialog {...base({ failed: true })} />);
    expect(screen.getByText(labels.failed)).toBeInTheDocument();
  });

  it('absolute limit: gives the sign-in time, says staying active cannot extend it, offers no "Stay signed in"', async () => {
    const user = userEvent.setup();
    const props = base({ kind: 'absolute', locale: 'en-US' });
    render(<SessionTimeoutDialog {...props} />);
    const dialog = screen.getByRole('dialog');
    expect(dialog).toHaveTextContent(/For your security you need to sign in again at \d{1,2}:15\s?(AM|PM)\./);
    expect(dialog).toHaveTextContent('Staying active cannot extend this session.');
    expect(screen.queryByRole('button', { name: 'Stay signed in' })).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Got it' }));
    expect(props.onDismiss).toHaveBeenCalledTimes(1);
    await user.keyboard('{Escape}');
    expect(props.onDismiss).toHaveBeenCalledTimes(2);
    expect(props.onStay).not.toHaveBeenCalled();
  });

  describe('announcements', () => {
    it('speaks politely at the start and then only every 15 seconds', () => {
      const { rerender } = render(<SessionTimeoutDialog {...base({ secondsLeft: 60 })} />);
      expect(polite()).toHaveTextContent('You’ll be signed out in');
      expect(polite()).toHaveAttribute('aria-live', 'polite');
      const first = polite().textContent;
      rerender(<SessionTimeoutDialog {...base({ secondsLeft: 59 })} />);
      rerender(<SessionTimeoutDialog {...base({ secondsLeft: 50 })} />);
      expect(polite().textContent).toBe(first);
      rerender(<SessionTimeoutDialog {...base({ secondsLeft: 45 })} />);
      expect(polite().textContent).not.toBe(first);
      const second = polite().textContent;
      rerender(<SessionTimeoutDialog {...base({ secondsLeft: 31 })} />);
      expect(polite().textContent).toBe(second);
      rerender(<SessionTimeoutDialog {...base({ secondsLeft: 30 })} />);
      expect(polite().textContent).not.toBe(second);
      expect(assertive()).toBeEmptyDOMElement();
    });

    it('switches to assertive, every second, for the last 10 seconds', () => {
      const { rerender } = render(<SessionTimeoutDialog {...base({ secondsLeft: 12 })} />);
      expect(assertive()).toBeEmptyDOMElement();
      expect(assertive()).toHaveAttribute('aria-live', 'assertive');
      rerender(<SessionTimeoutDialog {...base({ secondsLeft: 10 })} />);
      expect(assertive()).toHaveTextContent('10');
      expect(polite()).toBeEmptyDOMElement();
      rerender(<SessionTimeoutDialog {...base({ secondsLeft: 9 })} />);
      expect(assertive()).toHaveTextContent('9');
      rerender(<SessionTimeoutDialog {...base({ secondsLeft: 8 })} />);
      expect(assertive()).toHaveTextContent('8');
    });

    it('the visible countdown is not itself a live region (no chatter)', () => {
      render(<SessionTimeoutDialog {...base()} />);
      expect(screen.getByTestId('session-countdown').closest('[aria-live]')).toBeNull();
    });

    it('starts again from the top after it was closed', () => {
      const { rerender } = render(<SessionTimeoutDialog {...base({ secondsLeft: 60 })} />);
      rerender(<SessionTimeoutDialog {...base({ open: false, secondsLeft: 0 })} />);
      rerender(<SessionTimeoutDialog {...base({ secondsLeft: 60 })} />);
      expect(polite()).toHaveTextContent('You’ll be signed out in');
    });
  });

  it('renders nothing while closed', () => {
    render(<SessionTimeoutDialog {...base({ open: false })} />);
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });
});

describe('SessionEndedScreen', () => {
  it.each([
    ['SESSION_IDLE_TIMEOUT', labels.endedIdle],
    ['SESSION_ABSOLUTE_TIMEOUT', labels.endedAbsolute],
    ['SIGNED_OUT', labels.endedSignedOut]
  ] as const)('says why for %s and offers signing in again', async (reason, text) => {
    const onSignIn = vi.fn();
    render(<SessionEndedScreen reason={reason} labels={labels} onSignIn={onSignIn} />);
    const screenEl = screen.getByRole('alertdialog', { name: labels.endedTitle });
    expect(within(screenEl).getByText(text)).toBeInTheDocument();
    await userEvent.click(within(screenEl).getByRole('button', { name: 'Sign in again' }));
    expect(onSignIn).toHaveBeenCalledTimes(1);
  });

  it('cannot be dismissed with Esc, and traps focus', async () => {
    render(
      <>
        <button type="button">Account data</button>
        <SessionEndedScreen reason="SESSION_IDLE_TIMEOUT" labels={labels} onSignIn={vi.fn()} />
      </>
    );
    act(() => {
      fireEvent.keyDown(document.activeElement ?? document.body, { key: 'Escape' });
    });
    expect(screen.getByTestId('session-ended')).toBeInTheDocument();
    await userEvent.tab();
    expect(screen.getByTestId('session-ended').contains(document.activeElement)).toBe(true);
    expect(screen.queryByRole('button', { name: 'Account data' })).not.toBeInTheDocument();
  });

  it('shows that the sign-out is being finished', () => {
    render(<SessionEndedScreen reason="SESSION_IDLE_TIMEOUT" labels={labels} onSignIn={vi.fn()} busy />);
    expect(screen.getByRole('status')).toHaveTextContent(labels.signingOut);
  });
});
