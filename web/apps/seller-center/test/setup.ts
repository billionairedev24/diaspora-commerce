import '@testing-library/jest-dom/vitest';

import { sessionEndBus } from '@afrotransact/api-client';
import { cleanup } from '@testing-library/react';
import { afterEach, vi } from 'vitest';

import { navGuard } from '@/lib/nav-guard';

import { mockApi, mockSessionApi, mockSettingsApi } from './mock-api';

afterEach(() => {
  cleanup();
  // One page, one session: a test that ended the session must not end the next one.
  sessionEndBus.reset();
  navGuard.reset();
  try {
    window.sessionStorage.clear();
  } catch {
    // storage may be unavailable in the test environment
  }
});

export const nav = { pathname: '/', search: new URLSearchParams(), push: vi.fn(), replace: vi.fn() };

vi.mock('next/navigation', () => ({
  useRouter: () => ({ push: nav.push, replace: nav.replace, refresh: vi.fn(), back: vi.fn(), prefetch: vi.fn() }),
  usePathname: () => nav.pathname,
  useSearchParams: () => nav.search
}));

// Every screen talks to the in-process seller MSW runtime (the same handlers `pnpm dev:mocks` serves).
vi.mock('@/lib/api', () => ({ sellerApi: () => mockApi() }));
vi.mock('@/lib/settings', () => ({ settingsApi: () => mockSettingsApi() }));
vi.mock('@/lib/session-api', () => ({ sessionApi: () => mockSessionApi() }));

// Radix overlays and Recharts measure elements; jsdom lacks these APIs.
class RO {
  observe() {}
  unobserve() {}
  disconnect() {}
}
globalThis.ResizeObserver ??= RO as unknown as typeof ResizeObserver;
Element.prototype.scrollIntoView ??= () => undefined;
Element.prototype.hasPointerCapture ??= () => false;
Element.prototype.releasePointerCapture ??= () => undefined;
