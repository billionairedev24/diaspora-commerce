import { createApiClient } from '@afrotransact/api-client';
import { homeMock, resetHomeMock } from '@afrotransact/api-client/mocks';
import { handleMockRequest } from '@afrotransact/api-client/mocks/runtime';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import CommunityPage from '@/app/(shop)/communities/[[...slug]]/page';

/* /communities/<key> asks the gateway whether the community exists (above the staff thresholds): a hidden or unknown one is a plain
   404 and never a "0 results" page; an alias or a case variant redirects to the canonical key; a visible one opens its products. */

vi.mock('next/navigation', () => ({
  notFound: () => {
    throw Object.assign(new Error('NEXT_NOT_FOUND'), { kind: 'notFound' });
  },
  redirect: (to: string) => {
    throw Object.assign(new Error('NEXT_REDIRECT'), { kind: 'redirect', to });
  },
  permanentRedirect: (to: string) => {
    throw Object.assign(new Error('NEXT_REDIRECT'), { kind: 'permanentRedirect', to });
  },
  useRouter: () => ({}),
  usePathname: () => '/',
  useSearchParams: () => new URLSearchParams()
}));
vi.mock('@/lib/api/server', () => ({
  serverApi: async () => createApiClient({ baseUrl: 'http://storefront.test/api/bff', fetch: (r) => handleMockRequest(r) })
}));

async function visit(slug?: string[]) {
  try {
    await CommunityPage({ params: Promise.resolve({ slug }) });
  } catch (e) {
    const err = e as { kind?: string; to?: string };
    if (err.kind) return { kind: err.kind, to: err.to };
    throw e;
  }
  return { kind: 'rendered', to: undefined };
}

beforeEach(() => resetHomeMock());

describe('community routes', () => {
  it('below the thresholds (10 products) the community is a 404, not a zero-result search', async () => {
    homeMock.catalogue = 10;
    expect(await visit(['nigerian'])).toEqual({ kind: 'notFound', to: undefined });
    expect(await visit(['ethiopian'])).toEqual({ kind: 'notFound', to: undefined });
  });

  it('with 0 products every community is a 404', async () => {
    homeMock.catalogue = 0;
    expect(await visit(['nigerian'])).toMatchObject({ kind: 'notFound' });
  });

  it('above the thresholds a community opens its products; the search filter is its English name', async () => {
    homeMock.catalogue = 100;
    expect(await visit(['nigerian'])).toEqual({ kind: 'redirect', to: '/s?community=Nigerian' });
  });

  it('a case variant or alias redirects permanently to the canonical key', async () => {
    homeMock.catalogue = 100;
    expect(await visit(['Nigerian'])).toEqual({ kind: 'permanentRedirect', to: '/communities/nigerian' });
  });

  it('an unknown community and a deeper path are 404; /communities itself goes home', async () => {
    homeMock.catalogue = 100;
    expect(await visit(['atlantis'])).toMatchObject({ kind: 'notFound' });
    expect(await visit(['nigerian', 'extra'])).toMatchObject({ kind: 'notFound' });
    expect(await visit(undefined)).toEqual({ kind: 'redirect', to: '/' });
  });

  it('a community staff raised the bar on disappears from the route as it does from the home and the footer', async () => {
    homeMock.catalogue = 10000;
    expect(await visit(['nigerian'])).toMatchObject({ kind: 'redirect' });
    homeMock.settings = { ...homeMock.settings, modules: { ...homeMock.settings.modules, communities: { ...homeMock.settings.modules.communities!, unitMinProducts: 50000 } } };
    expect(await visit(['nigerian'])).toMatchObject({ kind: 'notFound' });
  });
});
