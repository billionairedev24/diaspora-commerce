import { act, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import {
  createBroadcastSessionChannel,
  createStorageSessionChannel,
  SessionEndedScreen,
  type SessionEndReason,
  SessionTimeoutGuard,
  type SessionTimeoutLabels,
  type TimerPolicy
} from '../src';

const T0 = Date.parse('2026-11-03T10:00:00.000Z');
const SEC = 1000;

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
  failed: 'We couldn’t reach the server.',
  endedTitle: 'You’ve been signed out',
  endedIdle: 'You were inactive for a while.',
  endedAbsolute: 'Your session reached its maximum length.',
  endedSignedOut: 'You signed out in another window.',
  signInAgain: 'Sign in again',
  signingOut: 'Signing you out…'
};

function harness(opts: { enabled?: boolean } = {}) {
  const server = { last: T0, idle: 900, warn: 60 };
  const policy = (): TimerPolicy => ({
    idleTimeoutSeconds: server.idle,
    absoluteTimeoutSeconds: 28_800,
    warnSeconds: server.warn,
    lastActivityAt: new Date(server.last).toISOString(),
    expiresAt: new Date(server.last + server.idle * SEC).toISOString()
  });
  const loadPolicy = vi.fn(async (_fresh: boolean) => ({ policy: policy(), serverTime: Math.floor(Date.now() / SEC) * SEC }));
  const reportActivity = vi.fn(async () => {
    server.last = Date.now();
  });
  const endListeners = new Set<(r: SessionEndReason) => void>();
  const subscribeEnd = vi.fn((l: (r: SessionEndReason) => void) => {
    endListeners.add(l);
    return () => endListeners.delete(l);
  });
  const onEnded = vi.fn();
  const onSignOut = vi.fn();
  const ui = (
    <SessionTimeoutGuard
      labels={labels}
      locale="en"
      loadPolicy={loadPolicy}
      reportActivity={reportActivity}
      subscribeEnd={subscribeEnd}
      onEnded={onEnded}
      onSignOut={onSignOut}
      channel={null}
      enabled={opts.enabled}
      renderEnded={(reason) => <SessionEndedScreen reason={reason} labels={labels} onSignIn={vi.fn()} />}
    >
      <main>Signed-in page with account data</main>
    </SessionTimeoutGuard>
  );
  return { ui, server, loadPolicy, reportActivity, subscribeEnd, endListeners, onEnded, onSignOut };
}

const advance = (ms: number) => act(async () => void (await vi.advanceTimersByTimeAsync(ms)));

describe('SessionTimeoutGuard', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    vi.setSystemTime(T0);
  });
  afterEach(() => {
    vi.useRealTimers();
  });

  it('shows the page, then the warning, and "Stay signed in" closes it and keeps the page', async () => {
    const h = harness();
    render(h.ui);
    await advance(0);
    expect(screen.getByText('Signed-in page with account data')).toBeInTheDocument();
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    await advance(850 * SEC);
    expect(screen.getByRole('dialog', { name: labels.warnTitle })).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Stay signed in' }));
    await advance(0);
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(h.reportActivity).toHaveBeenCalledTimes(1);
    expect(screen.getByText('Signed-in page with account data')).toBeInTheDocument();
  });

  it('counts down on screen every second', async () => {
    const h = harness();
    render(h.ui);
    await advance(850 * SEC);
    const first = screen.getByTestId('session-countdown').textContent;
    await advance(5 * SEC);
    expect(screen.getByTestId('session-countdown').textContent).not.toBe(first);
  });

  it('"Sign out now" hands over to the app', async () => {
    const h = harness();
    render(h.ui);
    await advance(850 * SEC);
    fireEvent.click(screen.getByRole('button', { name: 'Sign out now' }));
    expect(h.onSignOut).toHaveBeenCalledTimes(1);
  });

  it('at zero the page is dropped, not hidden: only the signed-out screen remains', async () => {
    const h = harness();
    render(h.ui);
    await advance(0);
    await advance(920 * SEC);
    expect(screen.queryByText('Signed-in page with account data')).not.toBeInTheDocument();
    expect(screen.getByRole('alertdialog', { name: labels.endedTitle })).toHaveTextContent(labels.endedIdle);
    expect(h.onEnded).toHaveBeenCalledTimes(1);
    expect(h.onEnded).toHaveBeenCalledWith('SESSION_IDLE_TIMEOUT', 'timer');
  });

  it('a 401 reported by the request layer ends it at once with the server\'s reason', async () => {
    const h = harness();
    render(h.ui);
    await advance(0);
    act(() => h.endListeners.forEach((l) => l('SESSION_ABSOLUTE_TIMEOUT')));
    expect(screen.getByRole('alertdialog')).toHaveTextContent(labels.endedAbsolute);
    expect(screen.queryByText('Signed-in page with account data')).not.toBeInTheDocument();
    expect(h.onEnded).toHaveBeenCalledWith('SESSION_ABSOLUTE_TIMEOUT', 'server');
  });

  describe('real input', () => {
    it.each(['pointerdown', 'keydown', 'wheel', 'touchstart'])('%s is reported', async (type) => {
      const h = harness();
      render(h.ui);
      await advance(0);
      act(() => void fireEvent(window, new Event(type, { bubbles: true })));
      await advance(0);
      expect(h.reportActivity).toHaveBeenCalledTimes(1);
    });

    it('scroll (which does not bubble) is reported', async () => {
      const h = harness();
      render(h.ui);
      await advance(0);
      act(() => void fireEvent.scroll(document.body));
      await advance(0);
      expect(h.reportActivity).toHaveBeenCalledTimes(1);
    });

    it('mouse movement, timers and focus are not input', async () => {
      const h = harness();
      render(h.ui);
      await advance(0);
      act(() => {
        fireEvent.mouseMove(window);
        fireEvent.pointerMove(window);
        fireEvent.focus(window);
      });
      await advance(5 * 60 * SEC);
      expect(h.reportActivity).not.toHaveBeenCalled();
    });

    it('many events inside a second and a minute make one report', async () => {
      const h = harness();
      render(h.ui);
      await advance(0);
      for (let i = 0; i < 50; i += 1) {
        act(() => void fireEvent.keyDown(window, { key: 'a' }));
        await advance(500);
      }
      expect(h.reportActivity).toHaveBeenCalledTimes(1);
    });

    it('coming back to the tab is input and also re-checks the clock', async () => {
      const h = harness();
      render(h.ui);
      await advance(0);
      vi.setSystemTime(T0 + 3600 * SEC); // the laptop slept for an hour
      Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => 'visible' });
      act(() => void fireEvent(document, new Event('visibilitychange')));
      await advance(0);
      expect(screen.getByRole('alertdialog')).toHaveTextContent(labels.endedIdle);
    });
  });

  it('does nothing for a guest (enabled=false): no policy read, no listeners, children untouched', async () => {
    const h = harness({ enabled: false });
    render(h.ui);
    act(() => void fireEvent.keyDown(window, { key: 'a' }));
    await advance(2000 * SEC);
    expect(h.loadPolicy).not.toHaveBeenCalled();
    expect(h.reportActivity).not.toHaveBeenCalled();
    expect(h.subscribeEnd).not.toHaveBeenCalled();
    expect(screen.getByText('Signed-in page with account data')).toBeInTheDocument();
  });

  it('stops listening when unmounted', async () => {
    const h = harness();
    const { unmount } = render(h.ui);
    await advance(0);
    unmount();
    expect(h.endListeners.size).toBe(0);
    act(() => void fireEvent.keyDown(window, { key: 'a' }));
    await advance(2000 * SEC);
    expect(h.reportActivity).not.toHaveBeenCalled();
    expect(h.onEnded).not.toHaveBeenCalled();
  });
});

describe('tab channels', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('BroadcastChannel: posts to the other instances, ignores malformed and unknown messages', () => {
    const instances: FakeBroadcast[] = [];
    class FakeBroadcast {
      onmessage: ((e: MessageEvent<unknown>) => void) | null = null;
      constructor(public name: string) {
        instances.push(this);
      }
      postMessage(data: unknown) {
        instances.filter((i) => i !== this && i.name === this.name).forEach((i) => i.onmessage?.({ data } as MessageEvent<unknown>));
      }
    }
    vi.stubGlobal('BroadcastChannel', FakeBroadcast);
    const a = createBroadcastSessionChannel();
    const b = createBroadcastSessionChannel();
    const heard = vi.fn();
    b?.subscribe(heard);
    a?.post({ type: 'activity', at: 5 });
    a?.post({ type: 'ended', reason: 'SIGNED_OUT' });
    instances[0]?.postMessage({ type: 'ended', reason: 'SOMETHING_ELSE' });
    instances[0]?.postMessage('garbage');
    instances[0]?.postMessage({ type: 'activity', at: 'now' });
    expect(heard.mock.calls).toEqual([[{ type: 'activity', at: 5 }], [{ type: 'ended', reason: 'SIGNED_OUT' }]]);
  });

  it('falls back to the storage event, and writes nothing that stays in storage', () => {
    vi.stubGlobal('BroadcastChannel', undefined);
    const channel = createStorageSessionChannel();
    const heard = vi.fn();
    channel?.subscribe(heard);
    window.dispatchEvent(new StorageEvent('storage', { key: 'at.session-timeout.message', newValue: JSON.stringify({ type: 'ended', reason: 'SESSION_IDLE_TIMEOUT', nonce: 1 }) }));
    window.dispatchEvent(new StorageEvent('storage', { key: 'at.session-timeout.message', newValue: null }));
    window.dispatchEvent(new StorageEvent('storage', { key: 'other', newValue: JSON.stringify({ type: 'ended', reason: 'SIGNED_OUT' }) }));
    window.dispatchEvent(new StorageEvent('storage', { key: 'at.session-timeout.message', newValue: '{not json' }));
    expect(heard.mock.calls).toEqual([[{ type: 'ended', reason: 'SESSION_IDLE_TIMEOUT' }]]);
    channel?.post({ type: 'activity', at: 1 });
    expect(window.localStorage.getItem('at.session-timeout.message')).toBeNull();
  });
});
