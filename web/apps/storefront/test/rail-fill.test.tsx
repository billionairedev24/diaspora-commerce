import { type ProductSummary, type Rail, type RailFill } from '@afrotransact/api-client';
import { home, resetDb } from '@afrotransact/api-client/mocks';
import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { RailSection } from '@/components/home/HomeSections';
import { RailFillTile } from '@/components/home/RailFillTile';

import { installMockBff, sent } from './mockBff';
import { renderWithProviders } from './render';

vi.mock('@/i18n/server', async () => {
  const { tEn: t } = await import('@/i18n/t');
  return { getLocale: async () => 'en', getT: async () => t };
});

installMockBff();

const hou = { zip: '77042', city: 'Houston', state: 'TX', label: 'Houston 77042', serviceable: true };
const now = new Date('2026-11-04T12:00:00Z');
const products = (): ProductSummary[] => home(hou, now).blocks.flatMap((b) => b.rail?.items ?? []);
const sponsoredTile = (over: Partial<RailFill> = {}): RailFill => ({ kind: 'placement', id: 'plc_fill_paid', sponsored: true, advertiser: 'Kingston Kitchen', title: 'Jerk season', body: 'Wings and sides.', cta: 'Shop Kingston Kitchen', href: '/s?q=jerk', imageUrl: 'https://img.afrotransact.dev/products/a.webp', ...over });
const rail = (n: number, fill?: RailFill): Rail => ({ id: 'r_short', title: 'Pantry staples', href: '/s', items: products().slice(0, n), fill });
const adEvents = () => sent.filter((r) => r.method === 'POST' && r.url.endsWith('/v1/home/ad-events'));

beforeEach(() => {
  resetDb();
  window.sessionStorage.clear();
  sent.length = 0;
});

describe('rail fill tile', () => {
  it('a paid tile is labelled Sponsored with its advertiser, visibly and in its accessible name', () => {
    renderWithProviders(<ul><RailFillTile fill={sponsoredTile()} /></ul>);
    const tile = screen.getByRole('article', { name: 'Sponsored · Kingston Kitchen' });
    expect(within(tile).getByText('Sponsored')).toBeInTheDocument();
    expect(within(tile).getByText('Kingston Kitchen')).toBeInTheDocument();
    expect(within(tile).getByRole('heading', { name: 'Jerk season' })).toBeInTheDocument();
    expect(within(tile).getByRole('link', { name: 'Shop Kingston Kitchen' })).toHaveAttribute('rel', 'sponsored');
  });

  it('an editorial tile carries no ad label and no ad note', () => {
    renderWithProviders(<ul><RailFillTile fill={sponsoredTile({ sponsored: false, advertiser: undefined, id: 'plc_fill_editorial', title: 'Back to school' })} /></ul>);
    expect(screen.queryByText('Sponsored')).toBeNull();
    expect(screen.queryByText(/^Ad ·/)).toBeNull();
    expect(screen.getByRole('heading', { name: 'Back to school' })).toBeInTheDocument();
  });

  it('counts the click through the existing placement counter', async () => {
    renderWithProviders(<ul><RailFillTile fill={sponsoredTile()} /></ul>);
    fireEvent.click(screen.getByRole('link', { name: 'Shop Kingston Kitchen' }));
    await waitFor(() => expect(adEvents().length).toBeGreaterThan(0));
    const bodies = await Promise.all(adEvents().map((r) => r.clone().json() as Promise<{ placementId: string; event: string }>));
    expect(bodies).toContainEqual({ placementId: 'plc_fill_paid', event: 'click' });
  });

  it('never shows an icon, emoji or placeholder instead of a photo: no real photo means text only', () => {
    const { container } = renderWithProviders(<ul><RailFillTile fill={sponsoredTile({ imageUrl: 'https://cdn.example/placeholder.svg' })} /></ul>);
    expect(container.querySelector('img')).toBeNull();
  });

  it('sponsored products: only the products given, each linked, a click is reported with the search id and ad token', async () => {
    const [a, b] = products().slice(0, 2) as [ProductSummary, ProductSummary];
    const fill: RailFill = { kind: 'sponsored-products', sponsored: true, advertiser: 'Marketplace sellers', title: 'Groceries', href: '/s', searchId: 'srch_1', products: [{ ...a, sponsor: { advertiser: 'S', searchId: 'srch_1', adToken: 'tok-a' } as ProductSummary['sponsor'] }, b] };
    renderWithProviders(<ul><RailFillTile fill={fill} /></ul>);
    const tile = screen.getByRole('article', { name: 'Sponsored · Marketplace sellers' });
    expect(within(tile).getAllByRole('link').filter((l) => l.getAttribute('href')?.startsWith('/p/'))).toHaveLength(2);
    fireEvent.click(within(tile).getAllByRole('link')[0] as HTMLElement);
    await waitFor(() => expect(sent.some((r) => r.url.endsWith('/v1/search/clicks'))).toBe(true));
    const click = (await sent.find((r) => r.url.endsWith('/v1/search/clicks'))!.clone().json()) as { searchId: string; atin: string; adToken?: string };
    expect(click).toMatchObject({ searchId: 'srch_1', atin: a.atin, adToken: 'tok-a' });
  });
});

describe('rail with a fill', () => {
  it('puts the tile last in the row (focus order) and marks the track so the tile takes the empty columns', async () => {
    const { container } = renderWithProviders(await RailSection({ rail: rail(3, sponsoredTile()) }));
    const track = container.querySelector('ul[data-fill]') as HTMLElement;
    expect(track.dataset.fill).toBe('true');
    const items = Array.from(track.children);
    expect(items).toHaveLength(4);
    expect(items.at(-1)?.getAttribute('data-testid')).toBe('rail-fill');
    expect(container.querySelector('section')?.dataset.short).toBe('false');
  });

  it('with no fill, a short rail narrows to its own width instead of leaving a hollow area; a long one does not', async () => {
    const short = renderWithProviders(await RailSection({ rail: rail(3) }));
    expect(short.container.querySelector('section')?.dataset.short).toBe('true');
    expect(short.container.querySelector('[data-testid=rail-fill]')).toBeNull();
    short.unmount();
    const long = renderWithProviders(await RailSection({ rail: rail(7) }));
    expect(long.container.querySelector('section')?.dataset.short).toBe('false');
  });
});
