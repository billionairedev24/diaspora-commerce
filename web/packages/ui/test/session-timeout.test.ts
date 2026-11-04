import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { createSessionTimeout, reportIntervalMs, type SessionChannel, type SessionMessage, type SessionOverReason, type TimerPolicy } from '../src';

const T0 = Date.parse('2026-11-03T10:00:00.000Z');
const SEC = 1000;
const MIN = 60 * SEC;

/** A stand-in for the BFF: its clock, the session's times and the three calls the timer makes. */
function fakeServer(init: { idle?: number; warn?: number; absolute?: number; skewMs?: number; signedInAgoMs?: number } = {}) {
  const s = {
    idle: init.idle ?? 900,
    warn: init.warn ?? 60,
    absolute: init.absolute ?? 28_800,
    skew: init.skewMs ?? 0,
    created: 0,
    last: 0,
    expired: null as 'idle' | 'absolute' | null
  };
  const serverNow = () => Date.now() + s.skew;
  s.created = serverNow() - (init.signedInAgoMs ?? 0);
  s.last = serverNow();
  const policy = (): TimerPolicy => ({
    idleTimeoutSeconds: s.idle,
    absoluteTimeoutSeconds: s.absolute,
    warnSeconds: s.warn,
    lastActivityAt: new Date(s.last).toISOString(),
    expiresAt: new Date(Math.min(s.last + s.idle * SEC, s.created + s.absolute * SEC)).toISOString()
  });
  const loadPolicy = vi.fn(async (_fresh: boolean) => ({ policy: policy(), serverTime: Math.floor(serverNow() / SEC) * SEC }));
  const reportActivity = vi.fn(async () => {
    s.last = serverNow();
  });
  return { s, loadPolicy, reportActivity, policy, serverNow };
}

/** Two tabs' channels wired together: a post reaches the other, never itself. */
function channelPair(): [SessionChannel, SessionChannel, SessionMessage[], SessionMessage[]] {
  const l1 = new Set<(m: SessionMessage) => void>();
  const l2 = new Set<(m: SessionMessage) => void>();
  const sent1: SessionMessage[] = [];
  const sent2: SessionMessage[] = [];
  const mk = (own: Set<(m: SessionMessage) => void>, other: Set<(m: SessionMessage) => void>, sent: SessionMessage[]): SessionChannel => ({
    post: (m) => {
      sent.push(m);
      [...other].forEach((l) => l(m));
    },
    subscribe: (l) => {
      own.add(l);
      return () => own.delete(l);
    }
  });
  return [mk(l1, l2, sent1), mk(l2, l1, sent2), sent1, sent2];
}

function setup(init: Parameters<typeof fakeServer>[0] = {}, channel: SessionChannel | null = null) {
  const server = fakeServer(init);
  const ended: { reason: SessionOverReason; source: string }[] = [];
  const timer = createSessionTimeout({
    loadPolicy: server.loadPolicy,
    reportActivity: server.reportActivity,
    onEnded: (reason, source) => ended.push({ reason, source }),
    channel
  });
  return { server, timer, ended };
}

const advance = (ms: number) => vi.advanceTimersByTimeAsync(ms);

describe('session timer', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    vi.setSystemTime(T0);
  });
  afterEach(() => {
    vi.useRealTimers();
  });

  it('is silent until warnSeconds before the end, then counts down and signs out at zero', async () => {
    const { timer, ended, server } = setup(); // idle 15 min, warn 60 s
    timer.start();
    await advance(0);
    expect(timer.getSnapshot()).toEqual({ phase: 'active' });
    await advance(838 * SEC);
    expect(timer.getSnapshot().phase).toBe('active');
    await advance(3 * SEC);
    const view = timer.getSnapshot();
    expect(view).toMatchObject({ phase: 'warning', kind: 'idle', busy: false, failed: false });
    expect(view.phase === 'warning' && view.secondsLeft).toBeLessThanOrEqual(60);
    expect(view.phase === 'warning' && view.secondsLeft).toBeGreaterThanOrEqual(57);
    // the server was asked again before the warning showed (other tabs may have kept the session alive)
    expect(server.loadPolicy).toHaveBeenLastCalledWith(true);

    await advance(30 * SEC);
    const later = timer.getSnapshot();
    expect(later.phase === 'warning' && later.secondsLeft).toBeLessThanOrEqual(30);
    expect(ended).toEqual([]);

    await advance(31 * SEC);
    expect(timer.getSnapshot()).toEqual({ phase: 'ended', reason: 'SESSION_IDLE_TIMEOUT' });
    expect(ended).toEqual([{ reason: 'SESSION_IDLE_TIMEOUT', source: 'timer' }]);
  });

  it('does not sign out when the server says the session lives on (activity the tab did not see)', async () => {
    const { timer, ended, server } = setup();
    timer.start();
    await advance(0);
    await advance(800 * SEC);
    server.s.last = server.serverNow(); // the person worked in another browser window of the same session
    await advance(40 * SEC);
    expect(timer.getSnapshot().phase).toBe('active');
    expect(ended).toEqual([]);
  });

  it('"Stay signed in" reports activity, reads the policy again and closes the warning; a new warning comes later', async () => {
    const { timer, server, ended } = setup();
    timer.start();
    await advance(850 * SEC);
    expect(timer.getSnapshot().phase).toBe('warning');
    server.reportActivity.mockClear();
    server.loadPolicy.mockClear();
    await timer.staySignedIn();
    expect(server.reportActivity).toHaveBeenCalledTimes(1);
    expect(server.loadPolicy).toHaveBeenCalledWith(true);
    expect(timer.getSnapshot()).toEqual({ phase: 'active' });
    await advance(800 * SEC);
    expect(timer.getSnapshot().phase).toBe('active');
    await advance(60 * SEC);
    expect(timer.getSnapshot().phase).toBe('warning');
    expect(ended).toEqual([]);
  });

  it('a failed "Stay signed in" keeps the warning and says so', async () => {
    const { timer, server } = setup();
    timer.start();
    await advance(850 * SEC);
    server.reportActivity.mockRejectedValueOnce(new Error('offline'));
    await timer.staySignedIn();
    expect(timer.getSnapshot()).toMatchObject({ phase: 'warning', busy: false, failed: true });
  });

  it('while the warning shows, input does not extend the session (only the button does)', async () => {
    const { timer, server } = setup();
    timer.start();
    await advance(850 * SEC);
    server.reportActivity.mockClear();
    timer.activity();
    await advance(1);
    expect(server.reportActivity).not.toHaveBeenCalled();
    expect(timer.getSnapshot().phase).toBe('warning');
  });

  it('the absolute limit warns the same way but cannot be extended', async () => {
    // signed in 7 h 55 min ago of 8 h: five minutes of session left although the idle timeout would allow 15
    const { timer, server, ended } = setup({ signedInAgoMs: (7 * 60 + 55) * MIN });
    timer.start();
    await advance(0);
    await advance(4 * MIN + 5 * SEC);
    const view = timer.getSnapshot();
    expect(view).toMatchObject({ phase: 'warning', kind: 'absolute' });
    expect(view.phase === 'warning' && view.endsAt).toBeGreaterThan(Date.now());
    server.reportActivity.mockClear();
    await timer.staySignedIn();
    expect(server.reportActivity).not.toHaveBeenCalled();
    expect(timer.getSnapshot()).toMatchObject({ phase: 'warning', kind: 'absolute' });

    // "Got it" hides it, and it comes back for the last ten seconds
    timer.dismiss();
    expect(timer.getSnapshot()).toMatchObject({ phase: 'warning', dismissed: true });
    await advance(40 * SEC);
    expect(timer.getSnapshot()).toMatchObject({ phase: 'warning', dismissed: true });
    await advance(10 * SEC);
    expect(timer.getSnapshot()).toMatchObject({ phase: 'warning', dismissed: false });
    await advance(15 * SEC);
    expect(ended).toEqual([{ reason: 'SESSION_ABSOLUTE_TIMEOUT', source: 'timer' }]);
  });

  it('input near the absolute limit is not reported (it could not extend anything)', async () => {
    const { timer, server } = setup({ signedInAgoMs: (7 * 60 + 55) * MIN });
    timer.start();
    await advance(0);
    server.reportActivity.mockClear();
    await advance(2 * MIN);
    timer.activity();
    expect(server.reportActivity).not.toHaveBeenCalled();
  });

  describe('server clock', () => {
    it.each([
      ['a device clock 5 minutes behind the server', 5 * MIN],
      ['a device clock 5 minutes ahead of the server', -5 * MIN]
    ])('warns at the right moment with %s', async (_name, skewMs) => {
      // The server's session ends 900 s from now on the SERVER's clock; the device clock is off by skewMs.
      const { timer, server } = setup({ skewMs });
      timer.start();
      await advance(0);
      await advance(838 * SEC);
      expect(timer.getSnapshot().phase).toBe('active');
      await advance(4 * SEC);
      expect(timer.getSnapshot().phase).toBe('warning');
      expect(server.loadPolicy).toHaveBeenCalled();
    });

    it('without a Date header the device clock is used', async () => {
      const server = fakeServer();
      const timer = createSessionTimeout({
        loadPolicy: async () => ({ policy: server.policy(), serverTime: null }),
        reportActivity: server.reportActivity,
        onEnded: vi.fn()
      });
      timer.start();
      await advance(850 * SEC);
      expect(timer.getSnapshot().phase).toBe('warning');
    });
  });

  describe('reporting real input', () => {
    it('is throttled to about once a minute however much the person types', async () => {
      const { timer, server } = setup();
      timer.start();
      await advance(0);
      server.reportActivity.mockClear();
      for (let i = 0; i < 100; i += 1) {
        timer.activity();
        await advance(300);
      } // 30 s of typing
      expect(server.reportActivity).toHaveBeenCalledTimes(1);
      await advance(31 * SEC);
      timer.activity();
      await advance(0);
      expect(server.reportActivity).toHaveBeenCalledTimes(2);
    });

    it('the interval is a minute, shorter for tiny timeouts, longer for day-long ones', () => {
      expect(reportIntervalMs(900)).toBe(MIN);
      expect(reportIntervalMs(20)).toBe(5 * SEC);
      expect(reportIntervalMs(2)).toBe(SEC);
      expect(reportIntervalMs(30 * 86_400)).toBe(10 * MIN);
    });

    it('never reports from timers alone', async () => {
      const { timer, server } = setup();
      timer.start();
      await advance(10 * MIN);
      expect(server.reportActivity).not.toHaveBeenCalled();
    });

    it('a person who keeps working is never warned', async () => {
      const { timer, server } = setup();
      timer.start();
      await advance(0);
      for (let minute = 0; minute < 60; minute += 1) {
        timer.activity();
        await advance(MIN);
        expect(timer.getSnapshot().phase).toBe('active');
      }
      expect(server.reportActivity.mock.calls.length).toBeGreaterThanOrEqual(55);
    });
  });

  describe('long and absent warnings', () => {
    it('a customer session (30 days, no warning) never warns and survives a timer that would overflow', async () => {
      const { timer, ended } = setup({ idle: 30 * 86_400, warn: 0, absolute: 90 * 86_400 });
      timer.start();
      await advance(0);
      await advance(29 * 86_400 * SEC);
      expect(timer.getSnapshot()).toEqual({ phase: 'active' });
      expect(ended).toEqual([]);
      await advance(2 * 86_400 * SEC);
      expect(ended).toEqual([{ reason: 'SESSION_IDLE_TIMEOUT', source: 'timer' }]);
    });
  });

  describe('the server has the last word', () => {
    it('a 401 reported by the request layer ends the session once', async () => {
      const { timer, ended } = setup();
      timer.start();
      await advance(0);
      timer.end('SESSION_ABSOLUTE_TIMEOUT', 'server');
      timer.end('SESSION_IDLE_TIMEOUT', 'server');
      expect(ended).toEqual([{ reason: 'SESSION_ABSOLUTE_TIMEOUT', source: 'server' }]);
      expect(timer.getSnapshot()).toEqual({ phase: 'ended', reason: 'SESSION_ABSOLUTE_TIMEOUT' });
      // nothing runs afterwards
      await advance(60 * MIN);
      expect(ended).toHaveLength(1);
    });

    it('a timer that reaches its deadline while the server is unreachable signs out anyway', async () => {
      const { timer, ended, server } = setup();
      timer.start();
      await advance(0);
      server.loadPolicy.mockRejectedValue(new Error('offline'));
      await advance(16 * MIN);
      expect(ended).toEqual([{ reason: 'SESSION_IDLE_TIMEOUT', source: 'timer' }]);
    });

    it('retries when the first policy read fails', async () => {
      const { timer, server } = setup();
      server.loadPolicy.mockRejectedValueOnce(new Error('offline'));
      timer.start();
      await advance(0);
      expect(server.loadPolicy).toHaveBeenCalledTimes(1);
      await advance(31 * SEC);
      expect(server.loadPolicy).toHaveBeenCalledTimes(2);
      await advance(850 * SEC);
      expect(timer.getSnapshot().phase).toBe('warning');
    });

    it('a tab that was asleep checks the clock when it wakes up', async () => {
      const { timer, ended } = setup();
      timer.start();
      await advance(0);
      vi.setSystemTime(T0 + 3 * 3600 * SEC); // the laptop slept; no timer ran
      timer.resync();
      await advance(0);
      expect(ended).toEqual([{ reason: 'SESSION_IDLE_TIMEOUT', source: 'timer' }]);
    });
  });

  describe('several tabs', () => {
    it('activity reported in one tab keeps the other alive and closes its warning', async () => {
      const [a, b] = channelPair();
      const one = setup({}, a);
      const two = setup({}, b);
      one.timer.start();
      two.timer.start();
      await advance(0);
      await advance(850 * SEC);
      expect(two.timer.getSnapshot().phase).toBe('warning');
      // the person clicks "Stay signed in" in tab one
      one.server.s.last = one.server.serverNow();
      two.server.s.last = one.server.s.last; // same server session
      await one.timer.staySignedIn();
      expect(two.timer.getSnapshot()).toEqual({ phase: 'active' });
      expect(two.ended).toEqual([]);
    });

    it('a tab posts its own reported activity to the others', async () => {
      const [a, b, sentByA] = channelPair();
      const one = setup({}, a);
      setup({}, b).timer.start();
      one.timer.start();
      await advance(0);
      one.timer.activity();
      await advance(0);
      expect(sentByA).toEqual([{ type: 'activity', at: T0 }]);
    });

    it('a timeout in one tab signs out the others, which do not repeat the sign-out call', async () => {
      const [a, b, sentByA, sentByB] = channelPair();
      const one = setup({}, a);
      const two = setup({}, b);
      one.timer.start();
      two.timer.start();
      await advance(0);
      one.timer.end('SESSION_IDLE_TIMEOUT', 'server');
      expect(sentByA).toEqual([{ type: 'ended', reason: 'SESSION_IDLE_TIMEOUT' }]);
      expect(two.ended).toEqual([{ reason: 'SESSION_IDLE_TIMEOUT', source: 'tab' }]);
      expect(two.timer.getSnapshot()).toEqual({ phase: 'ended', reason: 'SESSION_IDLE_TIMEOUT' });
      expect(sentByB).toEqual([]); // no echo
    });

    it('a manual sign-out announced by one tab ends the others as signed out', async () => {
      const [a, b] = channelPair();
      const one = setup({}, a);
      const two = setup({}, b);
      one.timer.start();
      two.timer.start();
      await advance(0);
      one.timer.announceSignOut();
      expect(two.ended).toEqual([{ reason: 'SIGNED_OUT', source: 'tab' }]);
      expect(one.ended).toEqual([]); // the signing-out tab navigates by itself
    });

    it('after the end nothing a tab says changes anything', async () => {
      const [a, b] = channelPair();
      const one = setup({}, a);
      const two = setup({}, b);
      one.timer.start();
      two.timer.start();
      await advance(0);
      two.timer.end('SESSION_IDLE_TIMEOUT', 'server');
      one.timer.announceSignOut();
      expect(two.ended).toHaveLength(1);
    });
  });
});
