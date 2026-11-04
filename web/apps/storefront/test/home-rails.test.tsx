import { atinOf } from '@afrotransact/api-client/mocks';
import { screen, within } from '@testing-library/react';
import { beforeEach, describe, expect, it } from 'vitest';

import { RecentlyViewed } from '@/components/home/RecentlyViewed';
import { TrackView } from '@/components/product/TrackView';
import { readViewed, RECENTLY_VIEWED_MAX, rememberViewed } from '@/lib/recent/recentlyViewed';

import { installMockBff } from './mockBff';
import { renderWithProviders } from './render';

installMockBff();

beforeEach(() => window.localStorage.clear());

describe('recently viewed memory', () => {
  it('keeps the newest first, moves a repeat to the front and stops at the limit', () => {
    ['ATAAAAAAA1', 'ATAAAAAAA2', 'ATAAAAAAA1'].forEach(rememberViewed);
    expect(readViewed()).toEqual(['ATAAAAAAA1', 'ATAAAAAAA2']);
    for (let i = 0; i < RECENTLY_VIEWED_MAX + 5; i += 1) rememberViewed(`ATZZZZZ${String(i).padStart(3, '0')}`);
    expect(readViewed()).toHaveLength(RECENTLY_VIEWED_MAX);
  });

  it('shrugs off damaged storage', () => {
    window.localStorage.setItem('at.recentlyViewed', '{not json');
    expect(readViewed()).toEqual([]);
    window.localStorage.setItem('at.recentlyViewed', JSON.stringify([1, 'ATAAAAAAA1', null]));
    expect(readViewed()).toEqual(['ATAAAAAAA1']);
  });

  it('TrackView remembers the product it is on', () => {
    renderWithProviders(<TrackView atin="ATAAAAAAA9" />);
    expect(readViewed()).toEqual(['ATAAAAAAA9']);
  });
});

describe('the Recently viewed rail', () => {
  it('shows nothing at all when no product was viewed: no heading, no empty state', async () => {
    renderWithProviders(<RecentlyViewed />);
    await new Promise((r) => setTimeout(r, 50));
    expect(screen.queryByRole('region')).not.toBeInTheDocument();
    expect(screen.queryByRole('heading')).not.toBeInTheDocument();
    expect(screen.queryByRole('list')).not.toBeInTheDocument();
    expect(screen.queryByText(/Recently viewed/)).not.toBeInTheDocument();
  });

  it('shows the viewed products, most recent first, and drops unknown ones', async () => {
    [atinOf('p11'), 'ATNOPE0000', atinOf('p9')].forEach(rememberViewed);
    renderWithProviders(<RecentlyViewed />);
    const rail = await screen.findByRole('region', { name: 'Recently viewed' });
    const names = within(rail).getAllByRole('listitem');
    expect(names).toHaveLength(2);
    expect(names[0]).toHaveTextContent(/Blue Mountain Jerk Paste|Jerk/i);
  });
});
