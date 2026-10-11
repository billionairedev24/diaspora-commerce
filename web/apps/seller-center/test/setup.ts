import '@testing-library/jest-dom/vitest';

import { cleanup } from '@testing-library/react';
import { afterEach, vi } from 'vitest';

import { mockApi, mockSettingsApi } from './mock-api';

afterEach(() => cleanup());

export const nav = { pathname: '/', push: vi.fn(), replace: vi.fn() };

vi.mock('next/navigation', () => ({
  useRouter: () => ({ push: nav.push, replace: nav.replace, refresh: vi.fn(), back: vi.fn(), prefetch: vi.fn() }),
  usePathname: () => nav.pathname,
  useSearchParams: () => new URLSearchParams()
}));

// Every screen talks to the in-process seller MSW runtime (the same handlers `pnpm dev:mocks` serves).
vi.mock('@/lib/api', () => ({ sellerApi: () => mockApi() }));
vi.mock('@/lib/settings', () => ({ settingsApi: () => mockSettingsApi() }));

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
