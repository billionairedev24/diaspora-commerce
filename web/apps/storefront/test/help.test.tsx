import { createApiClient } from '@afrotransact/api-client';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';

import { HelpArticleList } from '@/components/help/HelpArticleList';
import { helpTopicOf } from '@/lib/help';

import { installMockBff } from './mockBff';
import { renderWithProviders } from './render';

const nav = vi.hoisted(() => ({ search: '', replace: vi.fn() }));

/* Next's navigation helpers throw control-flow errors; here they throw recognisable ones. */
vi.mock('next/navigation', () => ({
  notFound: () => {
    throw new Error('NEXT_NOT_FOUND');
  },
  permanentRedirect: (url: string) => {
    throw new Error(`NEXT_REDIRECT ${url}`);
  },
  usePathname: () => '/help',
  useSearchParams: () => new URLSearchParams(nav.search),
  useRouter: () => ({ push: vi.fn(), refresh: vi.fn(), replace: nav.replace })
}));
vi.mock('@/lib/api/server', () => ({ serverApi: async () => createApiClient({ baseUrl: `${window.location.origin}/api/bff` }) }));
vi.mock('@/i18n/server', async () => {
  const { tEn: t } = await import('@/i18n/t');
  return { getLocale: async () => 'en', getT: async () => t };
});

installMockBff();

describe('help center routing', () => {
  const article = async (slug: string) => {
    const { default: Page } = await import('@/app/(shop)/help/[slug]/page');
    return Page({ params: Promise.resolve({ slug }) });
  };

  it('renders a help page from content with the design’s title and sections, under a Help center breadcrumb', async () => {
    renderWithProviders(await article('shipping'));
    expect(screen.getByRole('heading', { level: 1, name: 'Shipping & delivery' })).toBeInTheDocument();
    for (const h of ['Carrier shipping', 'AfroTransact Delivery', 'Pickup']) expect(screen.getByRole('heading', { level: 2, name: h })).toBeInTheDocument();
    expect(within(screen.getByRole('navigation', { name: 'Breadcrumb' })).getByRole('link', { name: 'Help center' })).toHaveAttribute('href', '/help');
  });

  it('turns Markdown links and email addresses into links', async () => {
    renderWithProviders(await article('press'));
    expect(screen.getByRole('link', { name: 'press@afrotransact.com' })).toHaveAttribute('href', 'mailto:press@afrotransact.com');
  });

  it('404s unknown pages and redirects the hub and mixed-case slugs', async () => {
    await expect(article('nope')).rejects.toThrow('NEXT_NOT_FOUND');
    await expect(article('../etc')).rejects.toThrow('NEXT_NOT_FOUND');
    await expect(article('help-center')).rejects.toThrow('NEXT_REDIRECT /help');
    await expect(article('Press')).rejects.toThrow('NEXT_REDIRECT /help/press');
  });

  it('renders the hub with its sections and the article list', async () => {
    const { default: Hub } = await import('@/app/(shop)/help/page');
    renderWithProviders(await Hub({ searchParams: Promise.resolve({}) }));
    expect(screen.getByRole('heading', { level: 1, name: 'Help center' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Your orders' })).toHaveAttribute('href', '/orders');
    expect(await screen.findByRole('link', { name: /Shipping & delivery/ })).toHaveAttribute('href', '/help/shipping');
  });
});

describe('help article list', () => {
  it('lists every article but the hub, with topic chips that change the URL', async () => {
    nav.search = '';
    renderWithProviders(<HelpArticleList />);
    const list = await screen.findByTestId('help-articles');
    const titles = within(list).getAllByRole('link').map((a) => a.getAttribute('href'));
    expect(titles).toEqual(['/help/shipping', '/help/returns', '/help/fulfilled-by-afrotransact', '/help/advertise', '/help/press']);
    await userEvent.click(screen.getByRole('button', { name: 'Selling & advertising' }));
    expect(nav.replace).toHaveBeenCalledWith('/help?topic=SELLING', { scroll: false });
  });

  it('filters by the topic in the URL', async () => {
    nav.search = 'topic=selling';
    renderWithProviders(<HelpArticleList />);
    const list = await screen.findByTestId('help-articles');
    await waitFor(() => expect(within(list).getAllByRole('link')).toHaveLength(2));
    expect(screen.getByRole('button', { name: 'Selling & advertising' })).toHaveAttribute('aria-pressed', 'true');
  });

  it('reads topics from the URL in any case and ignores unknown ones', () => {
    expect(helpTopicOf('selling')).toBe('SELLING');
    expect(helpTopicOf('gossip')).toBeUndefined();
    expect(helpTopicOf(null)).toBeUndefined();
  });
});
