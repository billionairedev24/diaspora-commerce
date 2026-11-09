import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

import { act, render, renderHook, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { actionIcon, Donut, EmptyPrompt, HelpStrip, MiniBars, ModuleGrid, ModuleSection, ModuleSummaries, type ModuleSummaryData, PeriodSelect, Plus, Sparkline, usePeriod, Wallet } from '../src';

const pts = [
  { label: 'Mon', value: 10 },
  { label: 'Tue', value: 25 },
  { label: 'Wed', value: 15 }
];
const usd = (v: number) => `$${v}`;

describe('charts have a text alternative', () => {
  it('Sparkline names itself, summarises the range and carries a data table', () => {
    render(<Sparkline label="Revenue per day" points={pts} format={usd} />);
    const img = screen.getByRole('img', { name: /Revenue per day\. 3 values from Mon to Wed, lowest \$10, highest \$25, latest \$15/ });
    expect(img).toBeInTheDocument();
    const table = screen.getByRole('table', { name: 'Revenue per day' });
    expect(within(table).getAllByRole('row')).toHaveLength(4);
    expect(within(table).getByRole('cell', { name: '$25' })).toBeInTheDocument();
  });

  it('a single point does not break the line', () => {
    const { container } = render(<Sparkline label="One day" points={[{ label: 'Mon', value: 3 }]} />);
    expect(container.querySelector('path[d^="M"]')).not.toBeNull();
    expect(container.innerHTML).not.toContain('NaN');
  });

  it('MiniBars draws one bar per point', () => {
    const { container } = render(<MiniBars label="Bookings" points={pts} />);
    expect(container.querySelectorAll('rect')).toHaveLength(3);
    expect(container.innerHTML).not.toContain('NaN');
  });

  it('Donut states every slice with its value in text (colour is never the only signal)', () => {
    render(
      <Donut
        label="Orders by status"
        points={[
          { label: 'Shipped', value: 6, tone: 'green' },
          { label: 'Late', value: 2, tone: 'red' },
          { label: 'Empty slice', value: 0 }
        ]}
        centre="8"
      />
    );
    expect(screen.getByRole('img', { name: /Total 8\. Shipped 6, Late 2\./ })).toBeInTheDocument();
    const table = screen.getByRole('table', { name: 'Orders by status' });
    expect(within(table).queryByText('Empty slice')).toBeNull();
    expect(within(table).getByRole('row', { name: /Late 2/ })).toBeInTheDocument();
  });
});

describe('ModuleSection', () => {
  const actions = [
    { label: 'All orders', href: '/orders' },
    { label: 'Add product', href: '/listings/new' },
    { label: 'Payouts', href: '/payouts' },
    { label: 'Fourth', href: '/x' }
  ];

  it('ready: icon, name, one headline with label and period, chart slot, total line and at most three quick actions', () => {
    render(
      <ModuleSection
        title="Sales"
        icon={<svg data-testid="icon" />}
        headline={{ value: '$4,820', label: 'revenue', period: 'Last 30 days' }}
        chart={<Sparkline label="Revenue" points={pts} />}
        totalLine="128 orders"
        actions={actions}
      />
    );
    const section = screen.getByRole('region', { name: 'Sales' });
    expect(within(section).getByText('$4,820')).toBeInTheDocument();
    expect(within(section).getByText('Last 30 days')).toBeInTheDocument();
    expect(within(section).getByText('128 orders')).toBeInTheDocument();
    const links = within(screen.getByRole('list', { name: 'Sales quick actions' })).getAllByRole('link');
    expect(links.map((l) => l.textContent)).toEqual(['All orders', 'Add product', 'Payouts']);
    expect(links[0]).toHaveAttribute('href', '/orders');
  });

  it('quick actions are keyboard focusable', async () => {
    render(<ModuleSection title="Sales" actions={actions} />);
    await userEvent.tab();
    expect(screen.getByRole('link', { name: 'All orders' })).toHaveFocus();
  });

  it('empty: shows the prompt with one primary action, no figures and no quick actions', () => {
    render(
      <ModuleSection
        title="Listings"
        state="empty"
        headline={{ value: '0', label: 'live' }}
        actions={actions}
        empty={<EmptyPrompt art="listings" prompt="Add your first product to start selling." action={{ label: 'Add your first product', href: '/listings/new' }} />}
      />
    );
    expect(screen.getByText('Add your first product to start selling.')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Add your first product' })).toHaveAttribute('href', '/listings/new');
    expect(screen.queryByText('0')).toBeNull();
    expect(screen.queryByRole('list')).toBeNull();
    expect(document.querySelector('svg[data-art="listings"]')).toHaveAttribute('aria-hidden', 'true');
  });

  it('loading: a busy skeleton with a status name', () => {
    render(<ModuleSection title="Sales" state="loading" headline={{ value: '1', label: 'x' }} />);
    expect(screen.getByRole('region', { name: 'Sales' })).toHaveAttribute('aria-busy', 'true');
    expect(screen.getByRole('status', { name: 'Loading Sales' })).toBeInTheDocument();
    expect(screen.queryByText('1')).toBeNull();
  });

  it('error: an alert with Try again that retries', async () => {
    const onRetry = vi.fn();
    render(<ModuleSection title="Payouts" state="error" onRetry={onRetry} />);
    expect(screen.getByRole('alert')).toHaveTextContent('We couldn’t load this just now.');
    await userEvent.click(screen.getByRole('button', { name: 'Try again' }));
    expect(onRetry).toHaveBeenCalledOnce();
  });

  it('uses the given link component (e.g. the app router link)', () => {
    const Fake = ({ href, children, className }: { href: string; children?: React.ReactNode; className?: string }) => (
      <a data-fake href={`/app${href}`} className={className}>
        {children}
      </a>
    );
    render(<ModuleSection title="Sales" actions={[{ label: 'All orders', href: '/orders' }]} linkAs={Fake} />);
    expect(screen.getByRole('link', { name: 'All orders' })).toHaveAttribute('href', '/app/orders');
  });

  it('ModuleGrid and HelpStrip render', () => {
    render(
      <>
        <ModuleGrid>
          <ModuleSection title="A" />
        </ModuleGrid>
        <HelpStrip description="Talk to us." links={[{ label: 'Help centre', href: '/help' }]} />
      </>
    );
    expect(screen.getByRole('heading', { name: 'We’re here to help' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Help centre' })).toHaveAttribute('href', '/help');
  });
});

describe('PeriodSelect / usePeriod', () => {
  afterEach(() => {
    window.localStorage.clear();
    vi.restoreAllMocks();
  });

  it('offers 7, 30 and 90 days and marks the current one', async () => {
    const onChange = vi.fn();
    render(<PeriodSelect value="30" onChange={onChange} />);
    expect(screen.getByRole('button', { name: '30 days' })).toHaveAttribute('aria-pressed', 'true');
    await userEvent.click(screen.getByRole('button', { name: '7 days' }));
    expect(onChange).toHaveBeenCalledWith('7');
  });

  it('remembers the choice per viewer', () => {
    window.localStorage.setItem('k', '90');
    const { result } = renderHook(() => usePeriod('k'));
    expect(result.current[0]).toBe('90');
    act(() => result.current[1]('7'));
    expect(window.localStorage.getItem('k')).toBe('7');
  });

  it('ignores a corrupt saved value', () => {
    window.localStorage.setItem('k', 'abc');
    const { result } = renderHook(() => usePeriod('k'));
    expect(result.current[0]).toBe('30');
  });

  it('still works when storage throws', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('blocked');
    });
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('blocked');
    });
    const { result } = renderHook(() => usePeriod('k'));
    expect(result.current[0]).toBe('30');
    act(() => result.current[1]('7'));
    expect(result.current[0]).toBe('7');
  });
});

describe('design rules', () => {
  const css = ['dashboard.module.css', 'charts.module.css'].map((f) => readFileSync(resolve(__dirname, '../src/dashboard', f), 'utf8')).join('\n');
  it('uses tokens only: no raw colours and no shadows', () => {
    expect(css).not.toMatch(/#[0-9a-fA-F]{3,8}\b/);
    expect(css).not.toMatch(/rgba?\(|hsla?\(/);
    expect(css).not.toMatch(/box-shadow|drop-shadow/);
  });
});

describe('ModuleSummaries (built from the BFF summaries)', () => {
  const modules: ModuleSummaryData[] = [
    {
      id: 'sales', icon: 'sales', title: 'Sales', state: 'ready',
      headline: { value: '$4,820.50', label: 'in sales', period: 'Last 30 days', tone: 'positive' },
      chart: { kind: 'sparkline', label: 'Sales per day, last 30 days', valueFormat: 'money', points: pts },
      totalLine: '128 orders', actions: [{ label: 'All orders', href: '/orders' }, { label: 'Add product', href: '/listings/new' }]
    },
    {
      id: 'orders', icon: 'orders', title: 'Orders', state: 'ready',
      headline: { value: '7', label: 'to ship now', period: null, tone: 'attention' },
      chart: { kind: 'donut', label: 'Orders by fulfilment', valueFormat: 'count', points: [{ label: 'Ship yourself', value: 4, tone: 'ink' }, { label: 'Local driver', value: 3, tone: 'gold' }] },
      totalLine: null, actions: []
    },
    { id: 'listings', icon: 'listings', title: 'Listings', state: 'empty', headline: null, chart: null, totalLine: null, actions: [], empty: { prompt: 'Add your first product to start selling.', art: 'listings', action: { label: 'Add your first product', href: '/listings/new' } } },
    { id: 'payouts', icon: 'payouts', title: 'Payouts', state: 'unavailable', headline: null, chart: null, totalLine: null, actions: [] }
  ];

  it('renders ready, empty and unavailable modules, with a retry for the one that failed', async () => {
    const onRetry = vi.fn();
    render(<ModuleSummaries modules={modules} onRetry={onRetry} />);
    expect(screen.getAllByRole('region')).toHaveLength(4);
    expect(screen.getByRole('img', { name: /Sales per day, last 30 days/ })).toBeInTheDocument();
    expect(screen.getByRole('table', { name: 'Orders by fulfilment' })).toBeInTheDocument();
    expect(screen.getByText('Add your first product to start selling.')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Try again' }));
    expect(onRetry).toHaveBeenCalledOnce();
  });

  it('shows skeleton modules while loading', () => {
    render(<ModuleSummaries modules={[]} loading />);
    expect(screen.getAllByRole('status')).toHaveLength(4);
  });

  it('picks quick-action icons from where the action goes', () => {
    expect(actionIcon('/listings/new', 'Add product')).toBe(Plus);
    expect(actionIcon('/payouts', 'Payouts')).toBe(Wallet);
  });
});
