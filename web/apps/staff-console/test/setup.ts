import '@testing-library/jest-dom/vitest';

import { cleanup } from '@testing-library/react';
import { useSyncExternalStore } from 'react';
import { afterEach, vi } from 'vitest';

import { mockApi } from './mock-api';

afterEach(() => cleanup());

/** In-memory URL for next/navigation: router.replace/push update it and re-render readers. */
const listeners = new Set<() => void>();
export const nav = {
  pathname: '/',
  search: '',
  set(url: string) {
    const [p, s = ''] = url.split('?');
    nav.pathname = p ?? '/';
    nav.search = s;
    listeners.forEach((l) => l());
  },
  push: vi.fn((url: string) => nav.set(url)),
  replace: vi.fn((url: string) => nav.set(url))
};
const subscribe = (l: () => void) => {
  listeners.add(l);
  return () => listeners.delete(l);
};

vi.mock('next/navigation', () => ({
  useRouter: () => ({ push: nav.push, replace: nav.replace, refresh: vi.fn(), back: vi.fn(), prefetch: vi.fn() }),
  usePathname: () => useSyncExternalStore(subscribe, () => nav.pathname),
  useSearchParams: () => {
    const s = useSyncExternalStore(subscribe, () => nav.search);
    return new URLSearchParams(s);
  },
  notFound: () => {
    throw new Error('not found');
  }
}));

// Every screen talks to the in-process staff MSW runtime (the same handlers `pnpm dev:mocks` serves).
vi.mock('@/lib/api', () => ({ staffApi: () => mockApi() }));

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
