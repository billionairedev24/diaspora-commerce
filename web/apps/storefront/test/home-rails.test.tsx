import { atinOf } from '@afrotransact/api-client/mocks';
import { screen, within } from '@testing-library/react';
import { beforeEach, describe, expect, it } from 'vitest';

import { RecentlyViewed } from '@/components/home/RecentlyViewed';
import { TrackView } from '@/components/product/TrackView';
import { normalizeViewed, railItems, readViewed, RECENTLY_VIEWED_SHOWN, RECENTLY_VIEWED_STORED, rememberViewed } from '@/lib/recent/recentlyViewed';

import { installMockBff } from './mockBff';
import { renderWithProviders } from './render';

installMockBff();

beforeEach(() => window.localStorage.clear());

describe('recently viewed memory', () => {
  it('keeps the newest first, moves a repeat to the front and stops at the limit', () => {
    ['ATAAAAAAA1', 'ATAAAAAAA2', 'ATAAAAAAA1'].forEach(rememberViewed);
    expect(readViewed()).toEqual(['ATAAAAAAA1', 'ATAAAAAAA2']);
    for (let i = 0; i < RECENTLY_VIEWED_STORED + 5; i += 1) rememberViewed(`ATZZZZZ${String(i).padStart(3, '0')}`);
    expect(readViewed()).toHaveLength(RECENTLY_VIEWED_STORED);
    expect(readViewed()[0]).toBe(`ATZZZZZ${String(RECENTLY_VIEWED_STORED + 4).padStart(3, '0')}`);
  });

  it('never stores one product twice, whatever the case or spacing, and moves it to the front', () => {
    ['ATAAAAAAA1', 'ATBBBBBBB2', ' ataaaaaaa1 ', 'ATAAAAAAA1'].forEach(rememberViewed);
    expect(readViewed()).toEqual(['ATAAAAAAA1', 'ATBBBBBBB2']);
  });

  it('collapses duplicates already in storage when reading, keeping the most recent position and every other entry', () => {
    const stale = ['ATDRESS001', 'ATRICE0001', 'ATDRESS001', 'atdress001', 'ATGARI0001', 'ATDRESS001', 'ATRICE0001'];
    window.localStorage.setItem('at.recentlyViewed', JSON.stringify(stale));
    expect(readViewed()).toEqual(['ATDRESS001', 'ATRICE0001', 'ATGARI0001']);
    // reading is not a migration: the stored value is untouched until the next view
    expect(JSON.parse(window.localStorage.getItem('at.recentlyViewed') ?? '[]')).toEqual(stale);
    expect(normalizeViewed('nope')).toEqual([]);
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

  describe('first-party cookie at_rv for the server render (X-Home-Exclude)', () => {
    const clearCookie = (n: string) => {
      document.cookie = `${n}=; path=/; max-age=0`;
    };
    const rv = () => document.cookie.split('; ').find((c) => c.startsWith('at_rv='));
    beforeEach(() => ['at_rv', 'at_consent'].forEach(clearCookie));
    const refuse = (functional: boolean) => {
      document.cookie = `at_consent=${encodeURIComponent(JSON.stringify({ necessary: true, functional, analytics: false, advertising: false, dns: false, gpc: false, v: '1', at: '2026-11-01T00:00:00Z' }))}; path=/`;
    };

    it('holds the 12 newest ATINs, a few hundred bytes at most', () => {
      for (let i = 0; i < 20; i += 1) rememberViewed(`ATCOOKIE${String(i).padStart(2, '0')}`);
      const value = rv()?.slice('at_rv='.length) ?? '';
      expect(value.split('-')).toHaveLength(12);
      expect(value.split('-')[0]).toBe('ATCOOKIE19');
      expect(value.length).toBeLessThan(300);
    });

    it('is written when the shopper has not chosen or allows Functional cookies', () => {
      rememberViewed('ATAAAAAAA1');
      expect(rv()).toBe('at_rv=ATAAAAAAA1');
      refuse(true);
      rememberViewed('ATAAAAAAA2');
      expect(rv()).toBe('at_rv=ATAAAAAAA2-ATAAAAAAA1');
    });

    it('is not written, and is cleared, when the shopper refuses Functional cookies', () => {
      rememberViewed('ATAAAAAAA1');
      expect(rv()).toBeDefined();
      refuse(false);
      rememberViewed('ATAAAAAAA2');
      expect(rv()).toBeUndefined();
      expect(readViewed()[0]).toBe('ATAAAAAAA2'); // the rail itself still works from local storage
    });
  });
});

describe('the rail items', () => {
  const card = (atin: string) => ({ atin });
  it('lists each product once, most recent first (the four Ankara dresses of the bug report are one card)', () => {
    const items = railItems(['ATANKARA01', 'ATANKARA01', 'ATRICE0001', 'ATANKARA01', 'ATANKARA01', 'ATBEANS001'].map(card));
    expect(items.map((p) => p.atin)).toEqual(['ATANKARA01', 'ATRICE0001', 'ATBEANS001']);
  });

  it('caps the rail', () => {
    const many = Array.from({ length: RECENTLY_VIEWED_STORED }, (_, i) => card(`ATMANY${String(i).padStart(4, '0')}`));
    expect(railItems(many)).toHaveLength(RECENTLY_VIEWED_SHOWN);
  });

  it('is empty when nothing is left', () => {
    expect(railItems([])).toEqual([]);
  });
});

describe('the Recently viewed rail', () => {
  it('shows a product once however often it was remembered, with no delivery date and no fulfilled-by badge', async () => {
    window.localStorage.setItem('at.recentlyViewed', JSON.stringify([atinOf('p11'), atinOf('p11'), atinOf('P11'), atinOf('p9'), atinOf('p11')]));
    renderWithProviders(<RecentlyViewed />);
    const rail = await screen.findByRole('region', { name: 'Recently viewed' });
    expect(within(rail).getAllByRole('listitem')).toHaveLength(2);
    expect(within(rail).getAllByText(/Basmati Rice/)).toHaveLength(1);
    expect(within(rail).queryByTestId('card-availability')).not.toBeInTheDocument();
    expect(rail.textContent ?? '').not.toMatch(/deliver|fulfilled by|arrives|tomorrow|ships from/i);
    expect(within(rail).queryByText(/\b0 reviews\b/)).not.toBeInTheDocument();
    expect(within(rail).getAllByText(/\$/).length).toBeGreaterThan(0);
  });

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
