import { screen, waitFor, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { HelpArticleList } from '@/components/help/HelpArticleList';

import { installMockBff } from './mockBff';
import { renderWithProviders } from './render';

/* Two articles a page so the list needs three pages; the sentinel is "seen" as soon as it is observed. */
vi.mock('@/lib/help', () => ({ HELP_PAGE_SIZE: 2, helpTopicOf: () => undefined }));
vi.mock('next/navigation', () => ({
  usePathname: () => '/help',
  useSearchParams: () => new URLSearchParams(),
  useRouter: () => ({ push: vi.fn(), refresh: vi.fn(), replace: vi.fn() })
}));

class SeenObserver {
  constructor(private readonly cb: IntersectionObserverCallback) {}
  observe(el: Element) {
    queueMicrotask(() => this.cb([{ isIntersecting: true, target: el } as IntersectionObserverEntry], this as unknown as IntersectionObserver));
  }
  disconnect() {}
  unobserve() {}
  takeRecords() {
    return [];
  }
}

installMockBff();
afterEach(() => vi.unstubAllGlobals());

describe('help article list paging', () => {
  it('loads the next page as the shopper scrolls to the end, with no page numbers', async () => {
    vi.stubGlobal('IntersectionObserver', SeenObserver);
    renderWithProviders(<HelpArticleList />);
    const list = await screen.findByTestId('help-articles');
    await waitFor(() => expect(within(list).getAllByRole('link')).toHaveLength(5), { timeout: 4000 });
    expect(screen.queryByRole('navigation', { name: /pag/i })).toBeNull();
    expect(screen.queryByRole('link', { name: '2' })).toBeNull();
  });
});
