import { beforeEach, describe, expect, it } from 'vitest';

import { createNavGuard } from '../src/shell/nav-guard';

/* The back-office shells' redirect-loop guard: redirects counted per destination across page loads (sessionStorage),
   a stop after 3 round trips within 30 s, and a loading clock that survives the loader being mounted again. */

function clock(start = 1_000_000) {
  const c = { t: start, now: () => c.t, advance: (ms: number) => (c.t += ms) };
  return c;
}

describe('createNavGuard', () => {
  beforeEach(() => window.sessionStorage.clear());

  it('allows three redirects to one destination within 30 s and stops the fourth, naming it', () => {
    const c = clock();
    const guard = createNavGuard({ key: 'test.nav', now: c.now, dedupeMs: 0 });
    for (let i = 0; i < 3; i++) {
      expect(guard.allow('/onboarding')).toBe(true);
      expect(guard.allow('/')).toBe(true);
      c.advance(2_000);
    }
    expect(guard.loop()).toBeNull();
    expect(guard.allow('/onboarding')).toBe(false);
    expect(guard.loop()).toEqual({ destination: '/onboarding', count: 4, windowMs: 30_000 });
  });

  it('counts per destination and forgets redirects older than the window', () => {
    const c = clock();
    const guard = createNavGuard({ key: 'test.nav', now: c.now, dedupeMs: 0 });
    expect(guard.allow('/a')).toBe(true);
    expect(guard.allow('/a')).toBe(true);
    expect(guard.allow('/b')).toBe(true);
    c.advance(31_000);
    expect(guard.allow('/a')).toBe(true);
    expect(guard.allow('/a')).toBe(true);
    expect(guard.allow('/a')).toBe(true);
    expect(guard.allow('/b')).toBe(true);
    expect(guard.loop()).toBeNull();
  });

  it('keeps counting across full page loads (a new guard reads the same tab storage)', () => {
    const c = clock();
    const url = 'http://localhost:3010/api/bff/v1/auth/login?returnTo=http%3A%2F%2Flocalhost%3A3010%2F';
    for (let load = 0; load < 3; load++) {
      expect(createNavGuard({ key: 'test.nav', now: c.now }).allow(url)).toBe(true);
      c.advance(800);
    }
    const page = createNavGuard({ key: 'test.nav', now: c.now });
    expect(page.allow(url)).toBe(false);
    // The page loaded after the stop still shows it (until Try again or the window passes).
    expect(createNavGuard({ key: 'test.nav', now: c.now }).loop()?.destination).toBe(url);
    c.advance(31_000);
    expect(createNavGuard({ key: 'test.nav', now: c.now }).loop()).toBeNull();
  });

  it('counts a redirect repeated at once in the same page (React re-running an effect) once', () => {
    const c = clock();
    const guard = createNavGuard({ key: 'test.nav', now: c.now });
    expect(guard.allow('/onboarding')).toBe(true);
    for (let i = 1; i < 10; i++) expect(guard.allow('/onboarding')).toBe(false);
    expect(guard.loop()).toBeNull();
  });

  it('same-origin URLs and paths are one destination', () => {
    const c = clock();
    const guard = createNavGuard({ key: 'test.nav', now: c.now, dedupeMs: 0 });
    guard.allow('/onboarding');
    guard.allow(`${window.location.origin}/onboarding`);
    guard.allow('/onboarding');
    expect(guard.allow(`${window.location.origin}/onboarding`)).toBe(false);
    expect(guard.loop()?.destination).toBe('/onboarding');
  });

  it('Try again forgets the loop, the counts and the loading clock, and tells subscribers', () => {
    const c = clock();
    const guard = createNavGuard({ key: 'test.nav', now: c.now, dedupeMs: 0 });
    let calls = 0;
    const off = guard.subscribe(() => calls++);
    for (let i = 0; i < 4; i++) guard.allow('/');
    expect(guard.loop()).not.toBeNull();
    guard.reset();
    expect(guard.loop()).toBeNull();
    expect(guard.allow('/')).toBe(true);
    expect(calls).toBe(2);
    off();
  });

  it('the loading clock survives the loader being mounted again, and starts afresh after a page was shown', () => {
    const c = clock();
    const guard = createNavGuard({ key: 'test.nav', now: c.now });
    const first = guard.loadingSince();
    c.advance(4_000);
    // A full page load in a loop: another guard instance, the same streak.
    expect(createNavGuard({ key: 'test.nav', now: c.now }).loadingSince()).toBe(first);
    c.advance(4_000);
    expect(guard.loadingSince()).toBe(first);
    guard.loaded();
    expect(guard.loadingSince()).toBe(c.t);
  });

  it('a loader not seen for a while is a new visit, not the same streak', () => {
    const c = clock();
    const guard = createNavGuard({ key: 'test.nav', now: c.now });
    guard.loadingSince();
    c.advance(60_000);
    expect(guard.loadingSince()).toBe(c.t);
  });

  it('works in memory when storage is blocked', () => {
    const c = clock();
    const blocked = () => {
      throw new Error('blocked');
    };
    const guard = createNavGuard({ key: 'test.nav', now: c.now, dedupeMs: 0, storage: blocked });
    for (let i = 0; i < 3; i++) expect(guard.allow('/')).toBe(true);
    expect(guard.allow('/')).toBe(false);
    const since = guard.loadingSince();
    c.advance(1_000);
    expect(guard.loadingSince()).toBe(since);
  });
});
