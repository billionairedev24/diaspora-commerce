/* The browser side of the dynamic home: module analytics (S427, consent-gated, no identity), the novelty memory cookie, the
   department bar / All menu / community routes that read live counts (S419, S420). */
import { homeMock, resetHomeMock } from '@afrotransact/api-client/mocks';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { HomeVisitMemory } from '@/components/home/HomeVisitMemory';
import { ModuleFrame } from '@/components/home/modules/ModuleFrame';
import { Header } from '@/components/shell/Header';
import { buildConsent, CONSENT_COOKIE } from '@/lib/consent/consent';
import { ConsentProvider } from '@/lib/consent/ConsentProvider';

import { installMockBff, sent } from './mockBff';
import { renderWithProviders } from './render';

installMockBff();

const consent = (analytics: boolean, functional = true) => buildConsent({ functional, analytics, advertising: false, dns: false }, { gpc: false, version: '1' });
const events = async () => Promise.all(sent.filter((r) => r.method === 'POST' && r.url.endsWith('/v1/home/events')).map((r) => r.clone().json() as Promise<Record<string, unknown>>));

/** An IntersectionObserver the test drives by hand. */
let observers: Array<{ cb: IntersectionObserverCallback; el: Element }> = [];
class FakeIO {
  constructor(private cb: IntersectionObserverCallback) {}
  observe(el: Element) {
    observers.push({ cb: this.cb, el });
  }
  unobserve() {}
  disconnect() {}
  takeRecords() {
    return [];
  }
}
const see = (visible: boolean, boundingHeight = 200, intersectionHeight = 120) =>
  act(() => {
    for (const o of observers) {
      o.cb([{ isIntersecting: visible, boundingClientRect: { height: boundingHeight }, intersectionRect: { height: visible ? intersectionHeight : 0 }, target: o.el } as unknown as IntersectionObserverEntry], {} as IntersectionObserver);
    }
  });
import { act } from '@testing-library/react';

beforeEach(() => {
  observers = [];
  vi.stubGlobal('IntersectionObserver', FakeIO);
  vi.useFakeTimers({ shouldAdvanceTime: true });
  resetHomeMock();
  document.cookie = 'at_hm=; max-age=0; path=/';
  document.cookie = 'at_hs=; max-age=0; path=/';
});
afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
  cleanup();
});

const tracking = { module: 'department-tiles', position: 3, variant: 'control' };
const frame = (analytics: boolean) =>
  render(
    <ConsentProvider initial={consent(analytics)}>
      <ModuleFrame type="department-tiles" tracking={tracking}>
        <a href="/s?dept=Groceries%20%26%20Food">Groceries</a>
        <a href="/p/AT00000001">A product</a>
      </ModuleFrame>
    </ConsentProvider>
  );

describe('module analytics (S427)', () => {
  it('counts ONE impression per page view once half the module has been visible for half a second', async () => {
    frame(true);
    see(true);
    vi.advanceTimersByTime(400);
    expect(await events()).toEqual([]);
    vi.advanceTimersByTime(150);
    see(true);
    await waitFor(async () => expect(await events()).toHaveLength(1));
    see(true);
    vi.advanceTimersByTime(2000);
    expect(await events()).toEqual([{ event: 'impression', module: 'department-tiles', position: 3, variant: 'control' }]);
  });

  it('a module scrolled past too quickly, or less than half visible, is not counted', async () => {
    frame(true);
    see(true);
    vi.advanceTimersByTime(300);
    see(false);
    vi.advanceTimersByTime(1000);
    see(true, 200, 60);
    vi.advanceTimersByTime(1000);
    expect(await events()).toEqual([]);
  });

  it('a module taller than the screen counts when half of the screen is its own', async () => {
    frame(true);
    // 3000 px tall, 400 px of it on a 768 px screen: more than half of the viewport.
    see(true, 3000, 400);
    vi.advanceTimersByTime(600);
    await waitFor(async () => expect(await events()).toHaveLength(1));
  });

  it('a click carries the module, position, variant and what was clicked, and nothing that identifies the shopper', async () => {
    frame(true);
    fireEvent.click(screen.getByRole('link', { name: 'Groceries' }));
    fireEvent.click(screen.getByRole('link', { name: 'A product' }));
    await waitFor(async () => expect(await events()).toHaveLength(2));
    const [dept, product] = await events();
    expect(dept).toEqual({ event: 'click', module: 'department-tiles', position: 3, variant: 'control', target: 'department' });
    expect(product).toMatchObject({ event: 'click', target: 'product' });
    expect(Object.keys(dept ?? {}).sort()).toEqual(['event', 'module', 'position', 'target', 'variant']);
    // The last click is remembered 24 hours for order attribution (module:position), as a first-party cookie.
    expect(document.cookie).toContain('at_hm=department-tiles%3A3');
  });

  it('sends nothing without the Analytics cookie choice', async () => {
    frame(false);
    see(true);
    vi.advanceTimersByTime(2000);
    fireEvent.click(screen.getByRole('link', { name: 'Groceries' }));
    expect(await events()).toEqual([]);
    expect(document.cookie).not.toContain('at_hm=');
  });

  it('paid modules are not counted here (they count by placement)', async () => {
    render(
      <ConsentProvider initial={consent(true)}>
        <ModuleFrame type="brand-banner" tracking={undefined}>
          <a href="/b/acme">Acme</a>
        </ModuleFrame>
      </ConsentProvider>
    );
    see(true);
    vi.advanceTimersByTime(1000);
    fireEvent.click(screen.getByRole('link', { name: 'Acme' }));
    expect(await events()).toEqual([]);
  });
});

describe('guest novelty memory', () => {
  const memory = (props: { window: string; seen: string[]; deterministic?: boolean }, c = consent(false)) =>
    render(
      <ConsentProvider initial={c}>
        <HomeVisitMemory window={props.window} seen={props.seen} deterministic={props.deterministic ?? false} />
      </ConsentProvider>
    );
  const jar = () => document.cookie.split('; ').find((c) => c.startsWith('at_hs='))?.slice(6);

  it('writes what this visit showed apart from earlier visits; a refresh changes nothing; the next visit moves it to the earlier ones', () => {
    memory({ window: 'w1', seen: ['aa', 'bb'] }).unmount();
    expect(jar()).toBe('w1~aa-bb~');
    memory({ window: 'w1', seen: ['aa', 'bb'] }).unmount();
    expect(jar()).toBe('w1~aa-bb~');
    memory({ window: 'w2', seen: ['cc'] }).unmount();
    expect(jar()).toBe('w2~cc~aa-bb');
  });

  it('writes nothing for the crawler page, or after Functional cookies were refused', () => {
    memory({ window: 'w1', seen: ['aa'], deterministic: true }).unmount();
    expect(jar()).toBeUndefined();
    memory({ window: 'w1', seen: ['aa'] }, consent(false, false)).unmount();
    expect(jar()).toBeUndefined();
    expect(CONSENT_COOKIE).toBe('at_consent');
  });
});

describe('department bar, All menu and Services read the live counts (S419)', () => {
  const nav = async (size: number) => {
    homeMock.catalogue = size;
    renderWithProviders(<Header />);
    const bar = screen.getByRole('navigation', { name: 'Departments' });
    await screen.findByRole('button', { name: 'All' });
    return bar;
  };

  it('10 products: only the departments that have products are linked; Electronics, Pets and Baby are not; Services shows because providers exist', async () => {
    const bar = await nav(10);
    await waitFor(() => expect(within(bar).getByRole('link', { name: 'Groceries' })).toHaveAttribute('href', '/s?dept=Groceries%20%26%20Food'));
    expect(within(bar).getByRole('link', { name: 'Fashion' })).toBeInTheDocument();
    for (const gone of ['Electronics', 'Pets', 'Baby & Kids', 'Party', 'Books']) expect(within(bar).queryByRole('link', { name: gone })).not.toBeInTheDocument();
    expect(within(bar).getByRole('link', { name: 'Services' })).toHaveAttribute('href', '/services');
    expect(within(bar).queryByRole('link', { name: 'Deals' })).not.toBeInTheDocument();
  });

  it('0 products: no department, no Services, no Deals, no Brand; Sell and Provide stay', async () => {
    const bar = await nav(0);
    await waitFor(() => expect(within(bar).getByRole('link', { name: 'Sell on AfroTransact' })).toBeInTheDocument());
    for (const gone of ['Groceries', 'Services', 'Deals', 'AfroTransact Brand']) expect(within(bar).queryByRole('link', { name: gone })).not.toBeInTheDocument();
    expect(within(bar).getByRole('link', { name: 'Offer your services' })).toBeInTheDocument();
  });

  it('10,000 products: the bar holds the 7 largest, Electronics and Pets appear, and All lists every department and the communities', async () => {
    const bar = await nav(10000);
    await waitFor(() => expect(within(bar).getByRole('link', { name: 'Groceries' })).toBeInTheDocument());
    const departmentLinks = within(bar)
      .getAllByRole('link')
      .filter((a) => a.getAttribute('href')?.startsWith('/s?dept='));
    expect(departmentLinks).toHaveLength(7);
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
    await user.click(within(bar).getByRole('button', { name: 'All' }));
    const menu = within(document.getElementById(within(bar).getByRole('button', { name: 'All' }).getAttribute('aria-controls') as string) as HTMLElement);
    expect(menu.getByRole('heading', { name: 'All departments' })).toBeInTheDocument();
    for (const d of ['Electronics', 'Pets', 'Baby & Kids']) expect(menu.getByRole('link', { name: d })).toBeInTheDocument();
    expect(menu.getByRole('link', { name: 'All products' })).toHaveAttribute('href', '/s');
    expect(menu.getByRole('heading', { name: 'Communities' })).toBeInTheDocument();
    expect(menu.getAllByRole('link', { name: /Nigerian|Ghanaian|Kenyan/ }).length).toBeGreaterThan(0);
  });

  it('the All menu is a disclosure: it opens, Escape closes it and returns focus to the button', async () => {
    const bar = await nav(100);
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
    const button = within(bar).getByRole('button', { name: 'All' });
    expect(button).toHaveAttribute('aria-expanded', 'false');
    await user.click(button);
    expect(button).toHaveAttribute('aria-expanded', 'true');
    await user.keyboard('{Escape}');
    expect(button).toHaveAttribute('aria-expanded', 'false');
    expect(button).toHaveFocus();
  });

  it('the search box offers the same departments', async () => {
    homeMock.catalogue = 10;
    renderWithProviders(<Header />);
    const select = await screen.findByRole('combobox', { name: 'Department' });
    await waitFor(() => expect(within(select).getAllByRole('option').length).toBe(1 + 3));
    expect(within(select).queryByRole('option', { name: 'Electronics' })).not.toBeInTheDocument();
  });
});
